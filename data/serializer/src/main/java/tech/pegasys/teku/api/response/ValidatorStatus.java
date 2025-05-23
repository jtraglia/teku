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

package tech.pegasys.teku.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

@SuppressWarnings("JavaCase")
@Schema(description = "[Validator status specification](https://hackmd.io/ofFJ5gOmQpu1jjHilHbdQQ)")
public enum ValidatorStatus {
  pending_initialized(false),
  pending_queued(false),
  active_ongoing(false),
  active_exiting(false),
  active_slashed(false),
  exited_unslashed(true),
  exited_slashed(true),
  withdrawal_possible(true),
  withdrawal_done(true);

  private final boolean hasExited;

  ValidatorStatus(final boolean hasExited) {
    this.hasExited = hasExited;
  }

  public boolean hasExited() {
    return hasExited;
  }
}
