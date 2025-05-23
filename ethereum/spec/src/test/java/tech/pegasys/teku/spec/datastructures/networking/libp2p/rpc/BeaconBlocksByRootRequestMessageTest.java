/*
 * Copyright Consensys Software Inc., 2025
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

package tech.pegasys.teku.spec.datastructures.networking.libp2p.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.pegasys.teku.infrastructure.ssz.SszDataAssert.assertThatSszData;

import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.config.SpecConfig;
import tech.pegasys.teku.spec.config.SpecConfigDeneb;

class BeaconBlocksByRootRequestMessageTest {

  final Spec spec = TestSpecFactory.createMinimalDeneb();
  final BeaconBlocksByRootRequestMessage.BeaconBlocksByRootRequestMessageSchema schema =
      spec.getGenesisSchemaDefinitions().getBeaconBlocksByRootRequestMessageSchema();

  @Test
  public void shouldRoundTripViaSsz() {
    final BeaconBlocksByRootRequestMessage request =
        new BeaconBlocksByRootRequestMessage(
            schema,
            List.of(
                Bytes32.fromHexString(
                    "0x1111111111111111111111111111111111111111111111111111111111111111"),
                Bytes32.fromHexString(
                    "0x2222222222222222222222222222222222222222222222222222222222222222")));
    final Bytes data = request.sszSerialize();
    final BeaconBlocksByRootRequestMessage result = schema.sszDeserialize(data);
    assertThatSszData(result).isEqualByAllMeansTo(request);
  }

  @Test
  public void verifyMaxLengthOfContainerIsGreaterOrEqualToMaxRequestBlocks() {
    final SpecConfig config = spec.forMilestone(SpecMilestone.DENEB).getConfig();
    final SpecConfigDeneb specConfigDeneb = SpecConfigDeneb.required(config);
    assertThat(schema.getMaxLength())
        .isGreaterThanOrEqualTo(spec.getNetworkingConfig().getMaxRequestBlocks())
        .isGreaterThanOrEqualTo(specConfigDeneb.getMaxRequestBlocksDeneb());
  }
}
