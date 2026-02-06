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
import tech.pegasys.teku.ethtests.finder.TestDefinition;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.reference.TestDataUtils;
import tech.pegasys.teku.reference.TestExecutor;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.operations.AttesterSlashing;
import tech.pegasys.teku.spec.datastructures.state.AnchorPoint;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.statetransition.validation.AttesterSlashingValidator;
import tech.pegasys.teku.statetransition.validation.InternalValidationResult;
import tech.pegasys.teku.statetransition.validation.ValidationResultCode;
import tech.pegasys.teku.storage.client.RecentChainData;
import tech.pegasys.teku.storage.storageSystem.InMemoryStorageSystemBuilder;
import tech.pegasys.teku.storage.storageSystem.StorageSystem;

public class GossipAttesterSlashingTestExecutor implements TestExecutor {
  private static final String SSZ_SNAPPY_EXTENSION = ".ssz_snappy";

  @Override
  public void runTest(final TestDefinition testDefinition) throws Throwable {
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
    final AnchorPoint anchorPoint = AnchorPoint.fromInitialState(spec, anchorState);
    recentChainData.initializeFromAnchorPoint(anchorPoint, anchorState.getGenesisTime());

    // Set up validator
    final AttesterSlashingValidator validator =
        new AttesterSlashingValidator(recentChainData, spec);

    // Process each message
    for (final Message message : metaData.messages) {
      final AttesterSlashing slashing = loadAttesterSlashing(testDefinition, spec, message.message);
      final SafeFuture<InternalValidationResult> resultFuture =
          validator.validateForGossip(slashing);

      assertThat(resultFuture).isCompleted();
      final InternalValidationResult result = safeJoin(resultFuture);

      assertValidationResult(message.expected, result, slashing.hashTreeRoot().toHexString());
    }
  }

  private void assertValidationResult(
      final String expected, final InternalValidationResult result, final String messageId) {
    switch (expected) {
      case "valid" ->
          assertThat(result.code())
              .describedAs("Expected message %s to be valid but got %s", messageId, result)
              .isEqualTo(ValidationResultCode.ACCEPT);
      case "ignore" ->
          assertThat(result.code())
              .describedAs("Expected message %s to be ignored but got %s", messageId, result)
              .isIn(ValidationResultCode.IGNORE, ValidationResultCode.SAVE_FOR_FUTURE);
      case "reject" ->
          assertThat(result.code())
              .describedAs("Expected message %s to be rejected but got %s", messageId, result)
              .isEqualTo(ValidationResultCode.REJECT);
      default -> throw new IllegalArgumentException("Unknown expected result: " + expected);
    }
  }

  private AttesterSlashing loadAttesterSlashing(
      final TestDefinition testDefinition, final Spec spec, final String messageName) {
    return TestDataUtils.loadSsz(
        testDefinition,
        messageName + SSZ_SNAPPY_EXTENSION,
        spec.getGenesisSchemaDefinitions().getAttesterSlashingSchema());
  }

  private MetaData loadMetaData(final TestDefinition testDefinition) throws IOException {
    return TestDataUtils.loadYaml(testDefinition, "meta.yaml", MetaData.class);
  }

  @SuppressWarnings("unused")
  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class MetaData {
    @JsonProperty(value = "topic", required = true)
    private String topic;

    @JsonProperty(value = "messages", required = true)
    private List<Message> messages;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class Message {
    @JsonProperty(value = "message", required = true)
    private String message;

    @JsonProperty(value = "expected", required = true)
    private String expected;
  }
}
