/*
 * Copyright Consensys Software Inc., 2024
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

package tech.pegasys.teku.validator.remote.typedef.handlers;

import static tech.pegasys.teku.ethereum.json.types.validator.PostSyncCommitteeData.SYNC_COMMITTEE_SUBSCRIPTION;
import static tech.pegasys.teku.infrastructure.json.types.DeserializableTypeDefinition.listOf;
import static tech.pegasys.teku.validator.remote.apiclient.ValidatorApiMethod.SUBSCRIBE_TO_SYNC_COMMITTEE_SUBNET;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import tech.pegasys.teku.ethereum.json.types.validator.PostSyncCommitteeData;
import tech.pegasys.teku.ethereum.json.types.validator.SyncCommitteeSubnetSubscription;
import tech.pegasys.teku.validator.remote.typedef.ResponseHandler;

public class SendSubscribeToSyncCommitteeSubnetsRequest extends AbstractTypeDefRequest {

  public SendSubscribeToSyncCommitteeSubnetsRequest(
      final HttpUrl baseEndpoint, final OkHttpClient okHttpClient) {
    super(baseEndpoint, okHttpClient);
  }

  public void subscribeToSyncCommitteeSubnets(
      final Collection<SyncCommitteeSubnetSubscription> subscriptions) {
    final List<PostSyncCommitteeData> requestData =
        subscriptions.stream().map(PostSyncCommitteeData::new).toList();
    postJson(
        SUBSCRIBE_TO_SYNC_COMMITTEE_SUBNET,
        Collections.emptyMap(),
        requestData,
        listOf(SYNC_COMMITTEE_SUBSCRIPTION),
        new ResponseHandler<>());
  }
}