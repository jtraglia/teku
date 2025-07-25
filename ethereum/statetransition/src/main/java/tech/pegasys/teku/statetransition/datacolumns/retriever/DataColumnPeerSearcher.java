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

package tech.pegasys.teku.statetransition.datacolumns.retriever;

import tech.pegasys.teku.infrastructure.unsigned.UInt64;

public interface DataColumnPeerSearcher {

  DataColumnPeerSearcher NOOP =
      new DataColumnPeerSearcher() {
        private static final PeerSearchRequest NOOP_REQUEST = () -> {};

        @Override
        public PeerSearchRequest requestPeers(UInt64 slot, UInt64 columnIndex) {
          return NOOP_REQUEST;
        }
      };

  PeerSearchRequest requestPeers(UInt64 slot, UInt64 columnIndex);

  interface PeerSearchRequest {

    // stop search
    void dispose();
  }
}
