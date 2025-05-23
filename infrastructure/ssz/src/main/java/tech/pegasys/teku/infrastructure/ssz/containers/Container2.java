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

package tech.pegasys.teku.infrastructure.ssz.containers;

import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.impl.AbstractSszImmutableContainer;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;

/** Autogenerated by tech.pegasys.teku.ssz.backing.ContainersGenerator */
public class Container2<C extends Container2<C, V0, V1>, V0 extends SszData, V1 extends SszData>
    extends AbstractSszImmutableContainer {

  protected Container2(final ContainerSchema2<C, V0, V1> schema) {
    super(schema);
  }

  protected Container2(final ContainerSchema2<C, V0, V1> schema, final TreeNode backingNode) {
    super(schema, backingNode);
  }

  protected Container2(final ContainerSchema2<C, V0, V1> schema, final V0 arg0, final V1 arg1) {
    super(schema, arg0, arg1);
  }

  protected V0 getField0() {
    return getAny(0);
  }

  protected V1 getField1() {
    return getAny(1);
  }
}
