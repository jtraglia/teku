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
import org.opentest4j.TestAbortedException;
import tech.pegasys.teku.ethtests.finder.TestDefinition;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.async.eventthread.InlineEventThread;
import tech.pegasys.teku.infrastructure.metrics.StubMetricsSystem;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.reference.TestDataUtils;
import tech.pegasys.teku.reference.TestExecutor;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.state.AnchorPoint;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.executionlayer.ExecutionLayerChannelStub;
import tech.pegasys.teku.spec.logic.common.statetransition.results.BlockImportResult;
import tech.pegasys.teku.statetransition.block.ReceivedBlockEventsChannel;
import tech.pegasys.teku.statetransition.forkchoice.ForkChoice;
import tech.pegasys.teku.statetransition.forkchoice.ForkChoiceStateProvider;
import tech.pegasys.teku.statetransition.forkchoice.MergeTransitionBlockValidator;
import tech.pegasys.teku.statetransition.forkchoice.NoopForkChoiceNotifier;
import tech.pegasys.teku.statetransition.forkchoice.TickProcessor;
import tech.pegasys.teku.statetransition.util.DebugDataDumper;
import tech.pegasys.teku.statetransition.validation.BlockBroadcastValidator;
import tech.pegasys.teku.statetransition.validation.BlockGossipValidator;
import tech.pegasys.teku.statetransition.validation.GossipValidationHelper;
import tech.pegasys.teku.statetransition.validation.InternalValidationResult;
import tech.pegasys.teku.statetransition.validation.ValidationResultCode;
import tech.pegasys.teku.storage.client.RecentChainData;
import tech.pegasys.teku.storage.storageSystem.InMemoryStorageSystemBuilder;
import tech.pegasys.teku.storage.storageSystem.StorageSystem;

public class GossipBeaconBlockTestExecutor implements TestExecutor {
  private static final String SSZ_SNAPPY_EXTENSION = ".ssz_snappy";

  private static final Set<String> IGNORED_TESTS =
      Set.of(
          "gossip_beacon_block__reject_finalized_checkpoint_not_ancestor",
          "gossip_beacon_block__reject_parent_failed_validation",
          "gossip_beacon_block__ignore_slot_not_greater_than_finalized");

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

    // Initialize chain from anchor
    final UInt64 genesisTime = anchorState.getGenesisTime();
    final AnchorPoint anchorPoint = AnchorPoint.fromInitialState(spec, anchorState);
    recentChainData.initializeFromAnchorPoint(anchorPoint, genesisTime);

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
      for (final BlockEntry blockEntry : metaData.blocks) {
        // Skip failed blocks - they shouldn't be imported
        if (blockEntry.failed != null && blockEntry.failed) {
          continue;
        }
        final SignedBeaconBlock block = loadBlock(testDefinition, spec, blockEntry.block);
        if (!recentChainData.containsBlock(block.getRoot())) {
          // Set time to allow block import (need to be at the right slot)
          final int secondsPerSlot = spec.getGenesisSpecConfig().getSecondsPerSlot();
          final UInt64 blockSlotTime =
              genesisTime.times(1000).plus(block.getSlot().times(secondsPerSlot).times(1000));
          forkChoice.onTick(blockSlotTime, Optional.empty());
          final SafeFuture<BlockImportResult> importResult =
              forkChoice.onBlock(
                  block, Optional.empty(), BlockBroadcastValidator.NOOP, executionLayer);
          assertThat(importResult).isCompleted();
          final BlockImportResult result = safeJoin(importResult);
          assertThat(result.isSuccessful())
              .describedAs("Failed to import block %s: %s", block.getRoot(), result)
              .isTrue();
        }
      }
    }

    // Set up gossip validator
    final StubMetricsSystem metricsSystem = new StubMetricsSystem();
    final GossipValidationHelper gossipValidationHelper =
        new GossipValidationHelper(spec, recentChainData, metricsSystem);
    final BlockGossipValidator blockGossipValidator =
        new BlockGossipValidator(
            spec,
            gossipValidationHelper,
            new ReceivedBlockEventsChannel() {
              @Override
              public void onBlockValidated(final SignedBeaconBlock block) {}

              @Override
              public void onBlockImported(
                  final SignedBeaconBlock block, final boolean executionOptimistic) {}
            });

    // Set the current time
    // The test's current_time_ms is relative to genesis, so we add genesis time to get absolute
    // time
    final UInt64 genesisTimeMillis = genesisTime.times(1000);
    final UInt64 currentTimeMs = genesisTimeMillis.plus(metaData.currentTimeMs);
    forkChoice.onTick(currentTimeMs, Optional.empty());

    // Process each message
    for (final Message message : metaData.messages) {
      final UInt64 offsetMs = message.offsetMs != null ? message.offsetMs : UInt64.ZERO;
      final UInt64 messageTimeMs = currentTimeMs.plus(offsetMs);
      forkChoice.onTick(messageTimeMs, Optional.empty());

      final SignedBeaconBlock block = loadBlock(testDefinition, spec, message.message);

      final SafeFuture<InternalValidationResult> resultFuture =
          blockGossipValidator.validate(block, true);
      assertThat(resultFuture).isCompleted();
      final InternalValidationResult result = safeJoin(resultFuture);

      assertValidationResult(message.expected, result, block);
    }
  }

  private void assertValidationResult(
      final String expected, final InternalValidationResult result, final SignedBeaconBlock block) {
    switch (expected) {
      case "valid" ->
          assertThat(result.code())
              .describedAs("Expected block %s to be valid but got %s", block.getRoot(), result)
              .isEqualTo(ValidationResultCode.ACCEPT);
      case "ignore" ->
          assertThat(result.code())
              .describedAs("Expected block %s to be ignored but got %s", block.getRoot(), result)
              .isIn(ValidationResultCode.IGNORE, ValidationResultCode.SAVE_FOR_FUTURE);
      case "reject" ->
          assertThat(result.code())
              .describedAs("Expected block %s to be rejected but got %s", block.getRoot(), result)
              .isEqualTo(ValidationResultCode.REJECT);
      default -> throw new IllegalArgumentException("Unknown expected result: " + expected);
    }
  }

  private SignedBeaconBlock loadBlock(
      final TestDefinition testDefinition, final Spec spec, final String blockName) {
    return TestDataUtils.loadSsz(
        testDefinition, blockName + SSZ_SNAPPY_EXTENSION, spec::deserializeSignedBeaconBlock);
  }

  private MetaData loadMetaData(final TestDefinition testDefinition) throws IOException {
    return TestDataUtils.loadYaml(testDefinition, "meta.yaml", MetaData.class);
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
    @JsonProperty(value = "offset_ms", required = true)
    private UInt64 offsetMs;

    @JsonProperty(value = "message", required = true)
    private String message;

    @JsonProperty(value = "expected", required = true)
    private String expected;
  }
}
