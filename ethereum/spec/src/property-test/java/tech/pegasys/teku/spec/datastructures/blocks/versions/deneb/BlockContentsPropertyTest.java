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

package tech.pegasys.teku.spec.datastructures.blocks.versions.deneb;

import static tech.pegasys.teku.spec.propertytest.util.PropertyTestHelper.assertDeserializeMutatedThrowsExpected;
import static tech.pegasys.teku.spec.propertytest.util.PropertyTestHelper.assertRoundTrip;

import com.fasterxml.jackson.core.JsonProcessingException;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import tech.pegasys.teku.spec.datastructures.blocks.BlockContainer;
import tech.pegasys.teku.spec.propertytest.suppliers.blocks.versions.deneb.BlockContentsSupplier;

public class BlockContentsPropertyTest {

  @Property(tries = 100)
  void roundTrip(@ForAll(supplier = BlockContentsSupplier.class) final BlockContainer blockContents)
      throws JsonProcessingException {
    assertRoundTrip(blockContents);
  }

  @Property(tries = 100)
  void deserializeMutated(
      @ForAll(supplier = BlockContentsSupplier.class) final BlockContainer blockContents,
      @ForAll final int seed) {
    assertDeserializeMutatedThrowsExpected(blockContents, seed);
  }
}
