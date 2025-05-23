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

package tech.pegasys.teku.beacon.pow;

import java.util.List;
import org.web3j.abi.EventEncoder;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.tx.Contract;
import tech.pegasys.teku.beacon.pow.contract.DepositContract;
import tech.pegasys.teku.beacon.pow.contract.DepositContract.DepositEventEventResponse;
import tech.pegasys.teku.infrastructure.async.SafeFuture;

public class DepositEventsAccessor {
  private final Eth1Provider eth1Provider;
  private final String contractAddress;

  public DepositEventsAccessor(final Eth1Provider eth1Provider, final String contractAddress) {
    this.eth1Provider = eth1Provider;
    this.contractAddress = contractAddress;
  }

  public SafeFuture<List<DepositEventEventResponse>> depositEventInRange(
      final DefaultBlockParameter startBlock, final DefaultBlockParameter endBlock) {
    final EthFilter filter = new EthFilter(startBlock, endBlock, this.contractAddress);
    filter.addSingleTopic(EventEncoder.encode(DepositContract.DEPOSITEVENT_EVENT));
    return SafeFuture.of(
        eth1Provider
            .ethGetLogs(filter)
            .thenApply(
                logs ->
                    logs.stream()
                        .map(log -> (Log) log.get())
                        .map(this::convertLogToDepositEventEventResponse)
                        .toList()));
  }

  private DepositContract.DepositEventEventResponse convertLogToDepositEventEventResponse(
      final Log log) {
    Contract.EventValuesWithLog eventValues = DepositContract.staticExtractDepositEventWithLog(log);
    DepositContract.DepositEventEventResponse typedResponse =
        new DepositContract.DepositEventEventResponse();
    typedResponse.log = log;
    typedResponse.pubkey = (byte[]) eventValues.getNonIndexedValues().get(0).getValue();
    typedResponse.withdrawalCredentials =
        (byte[]) eventValues.getNonIndexedValues().get(1).getValue();
    typedResponse.amount = (byte[]) eventValues.getNonIndexedValues().get(2).getValue();
    typedResponse.signature = (byte[]) eventValues.getNonIndexedValues().get(3).getValue();
    typedResponse.index = (byte[]) eventValues.getNonIndexedValues().get(4).getValue();
    return typedResponse;
  }
}
