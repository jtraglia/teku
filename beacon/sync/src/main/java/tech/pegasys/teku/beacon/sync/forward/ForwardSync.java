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

package tech.pegasys.teku.beacon.sync.forward;

import java.util.Optional;
import tech.pegasys.teku.beacon.sync.events.SyncingStatus;
import tech.pegasys.teku.beacon.sync.forward.multipeer.Sync.SyncProgress;
import tech.pegasys.teku.infrastructure.async.SafeFuture;

public interface ForwardSync {

  SyncingStatus getSyncStatus();

  SafeFuture<Optional<SyncProgress>> getSyncProgress();

  boolean isSyncActive();

  long subscribeToSyncChanges(SyncSubscriber subscriber);

  void unsubscribeFromSyncChanges(long subscriberId);

  interface SyncSubscriber {
    void onSyncingChange(boolean isSyncing);
  }
}
