/*
 * Copyright Consensys Software Inc., 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.reference.phase0.networking;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.pegasys.teku.infrastructure.async.SafeFutureAssert.safeJoin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes32;
import org.opentest4j.TestAbortedException;
import tech.pegasys.teku.bls.BLSSignatureVerifier;
import tech.pegasys.teku.ethtests.finder.TestDefinition;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.async.eventthread.InlineEventThread;
import tech.pegasys.teku.infrastructure.metrics.StubMetricsSystem;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.reference.TestDataUtils;
import tech.pegasys.teku.reference.TestExecutor;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.attestation.ValidatableAttestation;
import tech.pegasys.teku.spec.datastructures.blocks.BlockCheckpoints;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.operations.SignedAggregateAndProof;
import tech.pegasys.teku.spec.datastructures.state.AnchorPoint;
import tech.pegasys.teku.spec.datastructures.state.Checkpoint;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.executionlayer.ExecutionLayerChannelStub;
import tech.pegasys.teku.spec.logic.common.statetransition.results.BlockImportResult;
import tech.pegasys.teku.spec.logic.common.util.AsyncBLSSignatureVerifier;
import tech.pegasys.teku.statetransition.forkchoice.ForkChoice;
import tech.pegasys.teku.statetransition.forkchoice.ForkChoiceStateProvider;
import tech.pegasys.teku.statetransition.forkchoice.MergeTransitionBlockValidator;
import tech.pegasys.teku.statetransition.forkchoice.NoopForkChoiceNotifier;
import tech.pegasys.teku.statetransition.forkchoice.TickProcessor;
import tech.pegasys.teku.statetransition.util.DebugDataDumper;
import tech.pegasys.teku.statetransition.validation.AggregateAttestationValidator;
import tech.pegasys.teku.statetransition.validation.AttestationValidator;
import tech.pegasys.teku.statetransition.validation.BlockBroadcastValidator;
import tech.pegasys.teku.statetransition.validation.GossipValidationHelper;
import tech.pegasys.teku.statetransition.validation.InternalValidationResult;
import tech.pegasys.teku.statetransition.validation.ValidationResultCode;
import tech.pegasys.teku.storage.client.RecentChainData;
import tech.pegasys.teku.storage.protoarray.ForkChoiceStrategy;
import tech.pegasys.teku.storage.protoarray.ProtoArrayTestUtil;
import tech.pegasys.teku.storage.storageSystem.InMemoryStorageSystemBuilder;
import tech.pegasys.teku.storage.storageSystem.StorageSystem;

public class GossipBeaconAggregateAndProofTestExecutor implements TestExecutor {
  private static final String SSZ_SNAPPY_EXTENSION = ".ssz_snappy";

  private static final Set<String> IGNORED_TESTS =
      Set.of(
          "gossip_beacon_aggregate_and_proof__reject_block_failed_validation",
          "gossip_beacon_aggregate_and_proof__ignore_finalized_not_ancestor");

  @Override
  public void runTest(final TestDefinition testDefinition) throws Throwable {
    if (IGNORED_TESTS.contains(testDefinition.getTestName())) {
      throw new TestAbortedException(
          "Test " + testDefinition.getTestName() + " is not yet supported");
    }

    final Spec spec = testDefinition.getSpec();
    final MetaData metaData = loadMetaData(testDefinition);

    // Load initial state
    final BeaconState anchorState =
        TestDataUtils.loadStateFromSsz(testDefinition, "state" + SSZ_SNAPPY_EXTENSION);

    // Set up storage system
    final StorageSystem storageSystem =
        InMemoryStorageSystemBuilder.create().specProvider(spec).build();
    final RecentChainData recentChainData = storageSystem.recentChainData();

    // Initialize chain from anchor using the state
    final AnchorPoint anchorPoint = AnchorPoint.fromInitialState(spec, anchorState);
    recentChainData.initializeFromAnchorPoint(anchorPoint, anchorState.getGenesisTime());

    // Set up fork choice (needed for proper validation)
    final MergeTransitionBlockValidator transitionBlockValidator =
        new MergeTransitionBlockValidator(spec, recentChainData);
    final InlineEventThread eventThread = new InlineEventThread();
    final ForkChoice forkChoice =
        new ForkChoice(
            spec,
            eventThread,
            recentChainData,
            new NoopForkChoiceNotifier(),
            new ForkChoiceStateProvider(eventThread, recentChainData),
            new TickProcessor(spec, recentChainData),
            transitionBlockValidator,
            true,
            DebugDataDumper.NOOP,
            storageSystem.getMetricsSystem());

    final ExecutionLayerChannelStub executionLayer = new ExecutionLayerChannelStub(spec, false);

    // Import blocks if specified
    if (metaData.blocks != null) {
      boolean isFirstBlock = true;
      for (final BlockEntry blockEntry : metaData.blocks) {
        // Skip failed blocks - they shouldn't be imported
        if (blockEntry.failed != null && blockEntry.failed) {
          continue;
        }
        final SignedBeaconBlock block = loadBlock(testDefinition, spec, blockEntry.block);
        if (!recentChainData.containsBlock(block.getRoot())) {
          if (isFirstBlock) {
            // First block uses ProtoArray directly
            final ForkChoiceStrategy forkChoiceStrategy =
                recentChainData.getStore().getForkChoiceStrategy();
            final Bytes32 blockRoot = block.getRoot();
            final UInt64 blockEpoch = spec.computeEpochAtSlot(block.getSlot());
            final Checkpoint checkpoint = new Checkpoint(blockEpoch, blockRoot);
            final BlockCheckpoints checkpoints =
                new BlockCheckpoints(checkpoint, checkpoint, checkpoint, checkpoint);
            ProtoArrayTestUtil.addBlockToProtoArray(
                forkChoiceStrategy,
                block.getSlot(),
                blockRoot,
                block.getParentRoot(),
                block.getStateRoot(),
                checkpoints);
          } else {
            // Subsequent blocks use fork choice
            final SafeFuture<BlockImportResult> importResult =
                forkChoice.onBlock(
                    block, Optional.empty(), BlockBroadcastValidator.NOOP, executionLayer);
            assertThat(importResult).isCompleted();
          }
        }
        isFirstBlock = false;
      }
    }

    // Set the current time
    // The test's current_time_ms is relative to genesis, so we add genesis time to get absolute
    // time
    final UInt64 genesisTimeMillis = anchorState.getGenesisTime().times(1000);
    final UInt64 currentTimeMs = genesisTimeMillis.plus(metaData.currentTimeMs);
    forkChoice.onTick(currentTimeMs, Optional.empty());

    // Set up validators
    final StubMetricsSystem metricsSystem = new StubMetricsSystem();
    final GossipValidationHelper gossipValidationHelper =
        new GossipValidationHelper(spec, recentChainData, metricsSystem);
    final AsyncBLSSignatureVerifier signatureVerifier =
        AsyncBLSSignatureVerifier.wrap(BLSSignatureVerifier.SIMPLE);
    final AttestationValidator attestationValidator =
        new AttestationValidator(spec, signatureVerifier, gossipValidationHelper);
    final AggregateAttestationValidator validator =
        new AggregateAttestationValidator(spec, attestationValidator, signatureVerifier);

    // Process each message
    for (final Message message : metaData.messages) {
      final UInt64 messageTimeMs =
          genesisTimeMillis.plus(metaData.currentTimeMs).plus(message.getOffsetMs());
      forkChoice.onTick(messageTimeMs, Optional.empty());

      final SignedAggregateAndProof aggregate =
          loadAggregateAndProof(testDefinition, spec, message.message);

      final ValidatableAttestation validatableAttestation =
          ValidatableAttestation.aggregateFromValidator(spec, aggregate);
      final SafeFuture<InternalValidationResult> resultFuture =
          validator.validate(validatableAttestation);
      assertThat(resultFuture).isCompleted();
      final InternalValidationResult result = safeJoin(resultFuture);

      assertValidationResult(
          message.expected, message.reason, result, aggregate.hashTreeRoot().toHexString());
    }
  }

  private void assertValidationResult(
      final String expected,
      final String expectedReason,
      final InternalValidationResult result,
      final String messageId) {
    final String actualDescription = result.getDescription().orElse("(no description)");
    switch (expected) {
      case "valid" ->
          assertThat(result.code())
              .describedAs(
                  "Message %s: expected VALID but got %s. Expected reason: '%s'. Teku says: '%s'",
                  messageId, result.code(), expectedReason, actualDescription)
              .isEqualTo(ValidationResultCode.ACCEPT);
      case "ignore" ->
          assertThat(result.code())
              .describedAs(
                  "Message %s: expected IGNORE but got %s. Expected reason: '%s'. Teku says: '%s'",
                  messageId, result.code(), expectedReason, actualDescription)
              .isIn(ValidationResultCode.IGNORE, ValidationResultCode.SAVE_FOR_FUTURE);
      case "reject" ->
          assertThat(result.code())
              .describedAs(
                  "Message %s: expected REJECT but got %s. Expected reason: '%s'. Teku says: '%s'",
                  messageId, result.code(), expectedReason, actualDescription)
              .isEqualTo(ValidationResultCode.REJECT);
      default -> throw new IllegalArgumentException("Unknown expected result: " + expected);
    }
  }

  private SignedAggregateAndProof loadAggregateAndProof(
      final TestDefinition testDefinition, final Spec spec, final String messageName) {
    return TestDataUtils.loadSsz(
        testDefinition,
        messageName + SSZ_SNAPPY_EXTENSION,
        spec.getGenesisSchemaDefinitions().getSignedAggregateAndProofSchema());
  }

  private MetaData loadMetaData(final TestDefinition testDefinition) throws IOException {
    return TestDataUtils.loadYaml(testDefinition, "meta.yaml", MetaData.class);
  }

  private SignedBeaconBlock loadBlock(
      final TestDefinition testDefinition, final Spec spec, final String blockName) {
    return TestDataUtils.loadSsz(
        testDefinition,
        blockName + SSZ_SNAPPY_EXTENSION,
        spec.getGenesisSchemaDefinitions().getSignedBeaconBlockSchema());
  }

  @SuppressWarnings("unused")
  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class MetaData {
    @JsonProperty(value = "topic", required = true)
    private String topic;

    @JsonProperty(value = "blocks")
    private List<BlockEntry> blocks;

    @JsonProperty(value = "current_time_ms", required = true)
    private UInt64 currentTimeMs;

    @JsonProperty(value = "messages", required = true)
    private List<Message> messages;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class BlockEntry {
    @JsonProperty(value = "block", required = true)
    private String block;

    @JsonProperty(value = "failed")
    private Boolean failed;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class Message {
    @JsonProperty(value = "offset_ms")
    private UInt64 offsetMs;

    @JsonProperty(value = "message", required = true)
    private String message;

    @JsonProperty(value = "expected", required = true)
    private String expected;

    @JsonProperty(value = "reason")
    private String reason;

    public UInt64 getOffsetMs() {
      return offsetMs != null ? offsetMs : UInt64.ZERO;
    }
  }
}
