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

package tech.pegasys.teku.spec.schemas.registry;

import java.util.Set;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.schemas.registry.SchemaTypes.SchemaId;

interface SchemaProvider<T> {
  T getSchema(SchemaRegistry registry);

  Set<SpecMilestone> getSupportedMilestones();

  SpecMilestone getBaseMilestone(SpecMilestone version);

  boolean alwaysCreateNewSchema();

  SchemaId<T> getSchemaId();
}
