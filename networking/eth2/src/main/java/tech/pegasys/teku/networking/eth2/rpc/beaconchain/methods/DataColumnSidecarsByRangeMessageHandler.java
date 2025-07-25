/*
 * Copyright Consensys Software Inc., 2023
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

package tech.pegasys.teku.networking.eth2.rpc.beaconchain.methods;

import static tech.pegasys.teku.networking.eth2.rpc.core.RpcResponseStatus.INVALID_REQUEST_CODE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSortedMap;
import java.nio.channels.ClosedChannelException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.eth2.peers.Eth2Peer;
import tech.pegasys.teku.networking.eth2.peers.RequestApproval;
import tech.pegasys.teku.networking.eth2.rpc.core.PeerRequiredLocalMessageHandler;
import tech.pegasys.teku.networking.eth2.rpc.core.ResponseCallback;
import tech.pegasys.teku.networking.eth2.rpc.core.RpcException;
import tech.pegasys.teku.networking.p2p.rpc.StreamClosedException;
import tech.pegasys.teku.spec.config.SpecConfigFulu;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.networking.libp2p.rpc.DataColumnSidecarsByRangeRequestMessage;
import tech.pegasys.teku.spec.datastructures.util.DataColumnSlotAndIdentifier;
import tech.pegasys.teku.statetransition.datacolumns.log.rpc.DasReqRespLogger;
import tech.pegasys.teku.statetransition.datacolumns.log.rpc.LoggingPeerId;
import tech.pegasys.teku.statetransition.datacolumns.log.rpc.ReqRespResponseLogger;
import tech.pegasys.teku.storage.client.CombinedChainDataClient;

/**
 * <a
 * href="https://github.com/ethereum/consensus-specs/blob/master/specs/fulu/p2p-interface.md#datacolumnsidecarsbyrange-v1">DataColumnSidecarsByRange
 * v1</a>
 */
public class DataColumnSidecarsByRangeMessageHandler
    extends PeerRequiredLocalMessageHandler<
        DataColumnSidecarsByRangeRequestMessage, DataColumnSidecar> {

  private static final Logger LOG = LogManager.getLogger();

  private final SpecConfigFulu specConfigFulu;
  private final CombinedChainDataClient combinedChainDataClient;
  private final LabelledMetric<Counter> requestCounter;
  private final Counter totalDataColumnSidecarsRequestedCounter;
  private final DasReqRespLogger dasLogger;

  public DataColumnSidecarsByRangeMessageHandler(
      final SpecConfigFulu specConfigFulu,
      final MetricsSystem metricsSystem,
      final CombinedChainDataClient combinedChainDataClient,
      final DasReqRespLogger dasLogger) {
    this.specConfigFulu = specConfigFulu;
    this.combinedChainDataClient = combinedChainDataClient;
    requestCounter =
        metricsSystem.createLabelledCounter(
            TekuMetricCategory.NETWORK,
            "rpc_data_column_sidecars_by_range_requests_total",
            "Total number of data column sidecars by range requests received",
            "status");
    totalDataColumnSidecarsRequestedCounter =
        metricsSystem.createCounter(
            TekuMetricCategory.NETWORK,
            "rpc_data_column_sidecars_by_range_requested_sidecars_total",
            "Total number of data column sidecars requested in accepted blob sidecars by range requests from peers");
    this.dasLogger = dasLogger;
  }

  @Override
  public Optional<RpcException> validateRequest(
      final String protocolId, final DataColumnSidecarsByRangeRequestMessage request) {
    final int maxRequestDataColumnSidecars = specConfigFulu.getMaxRequestDataColumnSidecars();
    final int requestedCount = calculateRequestedCount(request);

    if (requestedCount == -1 || requestedCount > maxRequestDataColumnSidecars) {
      requestCounter.labels("count_too_big").inc();
      return Optional.of(
          new RpcException(
              INVALID_REQUEST_CODE,
              String.format(
                  "Only a maximum of %s data column sidecars can be requested per request",
                  maxRequestDataColumnSidecars)));
    }
    return Optional.empty();
  }

  @Override
  public void onIncomingMessage(
      final String protocolId,
      final Eth2Peer peer,
      final DataColumnSidecarsByRangeRequestMessage message,
      final ResponseCallback<DataColumnSidecar> responseCallback) {
    final UInt64 startSlot = message.getStartSlot();
    final UInt64 endSlot = message.getMaxSlot();
    final List<UInt64> columns = message.getColumns();

    final ReqRespResponseLogger<DataColumnSidecar> responseLogger =
        dasLogger
            .getDataColumnSidecarsByRangeLogger()
            .onInboundRequest(
                LoggingPeerId.fromPeerAndNodeId(
                    peer.getId().toBase58(), peer.getDiscoveryNodeId().orElseThrow()),
                new DasReqRespLogger.ByRangeRequest(
                    message.getStartSlot(), message.getCount().intValue(), message.getColumns()));
    final LoggingResponseCallback<DataColumnSidecar> responseCallbackWithLogging =
        new LoggingResponseCallback<>(responseCallback, responseLogger);

    final int requestedCount = calculateRequestedCount(message);

    final Optional<RequestApproval> dataColumnSidecarsRequestApproval =
        peer.approveDataColumnSidecarsRequest(responseCallbackWithLogging, requestedCount);

    if (!peer.approveRequest() || dataColumnSidecarsRequestApproval.isEmpty()) {
      requestCounter.labels("rate_limited").inc();
      return;
    }

    requestCounter.labels("ok").inc();
    totalDataColumnSidecarsRequestedCounter.inc(message.getCount().longValue());

    UInt64 finalizedSlot = combinedChainDataClient.getFinalizedBlockSlot().orElse(UInt64.ZERO);

    final SortedMap<UInt64, Bytes32> canonicalHotRoots;
    if (endSlot.isGreaterThan(finalizedSlot)) {
      canonicalHotRoots =
          combinedChainDataClient.getAncestorRoots(startSlot, UInt64.ONE, message.getCount());

      // refresh finalized slot to avoid race condition that can occur if we finalize just
      // before getting hot canonical roots
      finalizedSlot = combinedChainDataClient.getFinalizedBlockSlot().orElse(UInt64.ZERO);
    } else {
      canonicalHotRoots = ImmutableSortedMap.of();
    }

    final RequestState initialState =
        new RequestState(
            responseCallbackWithLogging,
            specConfigFulu.getMaxRequestDataColumnSidecars(),
            startSlot,
            endSlot,
            columns,
            canonicalHotRoots,
            finalizedSlot);

    final SafeFuture<RequestState> response;
    if (requestedCount == 0 || initialState.isComplete()) {
      response = SafeFuture.completedFuture(initialState);
    } else {
      response = sendDataColumnSidecars(initialState);
    }

    response.finish(
        requestState -> {
          final int sentDataColumnSidecars = requestState.sentDataColumnSidecars.get();
          if (sentDataColumnSidecars != requestedCount) {
            peer.adjustDataColumnSidecarsRequest(
                dataColumnSidecarsRequestApproval.get(), sentDataColumnSidecars);
          }
          responseCallbackWithLogging.completeSuccessfully();
        },
        error -> {
          peer.adjustDataColumnSidecarsRequest(dataColumnSidecarsRequestApproval.get(), 0);
          handleProcessingRequestError(error, responseCallbackWithLogging);
        });
  }

  private int calculateRequestedCount(final DataColumnSidecarsByRangeRequestMessage message) {
    try {
      return message.getCount().times(message.getColumns().size()).intValue();
    } catch (final ArithmeticException e) {
      return -1;
    }
  }

  private SafeFuture<RequestState> sendDataColumnSidecars(final RequestState requestState) {
    return requestState
        .loadNextDataColumnSidecar()
        .thenCompose(
            maybeDataColumnSidecar ->
                maybeDataColumnSidecar
                    .map(requestState::sendDataColumnSidecar)
                    .orElse(SafeFuture.COMPLETE))
        .thenCompose(
            __ -> {
              if (requestState.isComplete()) {
                return SafeFuture.completedFuture(requestState);
              } else {
                return sendDataColumnSidecars(requestState);
              }
            });
  }

  private void handleProcessingRequestError(
      final Throwable error, final ResponseCallback<DataColumnSidecar> callback) {
    final Throwable rootCause = Throwables.getRootCause(error);
    if (rootCause instanceof RpcException) {
      LOG.trace("Rejecting data column sidecars by range request", error);
      callback.completeWithErrorResponse((RpcException) rootCause);
    } else {
      if (rootCause instanceof StreamClosedException
          || rootCause instanceof ClosedChannelException) {
        LOG.trace("Stream closed while sending requested data column sidecars", error);
      } else {
        LOG.error("Failed to process data column sidecars request", error);
      }
      callback.completeWithUnexpectedError(error);
    }
  }

  @VisibleForTesting
  class RequestState {

    private final AtomicInteger sentDataColumnSidecars = new AtomicInteger(0);
    private final ResponseCallback<DataColumnSidecar> callback;
    private final UInt64 maxRequestDataColumnSidecars;
    private final UInt64 startSlot;
    private final UInt64 endSlot;
    private final List<UInt64> columns;
    private final UInt64 finalizedSlot;
    private final Map<UInt64, Bytes32> canonicalHotRoots;

    // since our storage stores hot and finalized data columns sidecar on the same "table", this
    // iterator can span over hot and finalized data column sidecar
    private Optional<Iterator<DataColumnSlotAndIdentifier>> dataColumnSidecarKeysIterator =
        Optional.empty();

    RequestState(
        final ResponseCallback<DataColumnSidecar> callback,
        final int maxRequestDataColumnSidecars,
        final UInt64 startSlot,
        final UInt64 endSlot,
        final List<UInt64> columns,
        final Map<UInt64, Bytes32> canonicalHotRoots,
        final UInt64 finalizedSlot) {
      this.callback = callback;
      this.maxRequestDataColumnSidecars = UInt64.valueOf(maxRequestDataColumnSidecars);
      this.startSlot = startSlot;
      this.endSlot = endSlot;
      this.columns = columns;
      this.finalizedSlot = finalizedSlot;
      this.canonicalHotRoots = canonicalHotRoots;
    }

    SafeFuture<Void> sendDataColumnSidecar(final DataColumnSidecar dataColumnSidecar) {
      return callback.respond(dataColumnSidecar).thenRun(sentDataColumnSidecars::incrementAndGet);
    }

    SafeFuture<Optional<DataColumnSidecar>> loadNextDataColumnSidecar() {
      if (dataColumnSidecarKeysIterator.isEmpty()) {
        return combinedChainDataClient
            .getDataColumnIdentifiers(startSlot, endSlot, maxRequestDataColumnSidecars)
            .thenCompose(
                keys -> {
                  dataColumnSidecarKeysIterator =
                      Optional.of(
                          keys.stream()
                              .filter(key -> columns.contains(key.columnIndex()))
                              .iterator());
                  return getNextDataColumnSidecar(dataColumnSidecarKeysIterator.get());
                });
      } else {
        return getNextDataColumnSidecar(dataColumnSidecarKeysIterator.get());
      }
    }

    private SafeFuture<Optional<DataColumnSidecar>> getNextDataColumnSidecar(
        final Iterator<DataColumnSlotAndIdentifier> dataColumnSidecarIdentifiers) {
      if (dataColumnSidecarIdentifiers.hasNext()) {
        final DataColumnSlotAndIdentifier columnSlotAndIdentifier =
            dataColumnSidecarIdentifiers.next();

        if (finalizedSlot.isGreaterThanOrEqualTo(columnSlotAndIdentifier.slot())) {
          return combinedChainDataClient.getSidecar(columnSlotAndIdentifier);
        }

        // not finalized, let's check if it is on canonical chain
        if (isCanonicalHotDataColumnSidecar(columnSlotAndIdentifier)) {
          return combinedChainDataClient.getSidecar(columnSlotAndIdentifier);
        }

        // non-canonical, try next one
        return getNextDataColumnSidecar(dataColumnSidecarIdentifiers);
      }

      return SafeFuture.completedFuture(Optional.empty());
    }

    private boolean isCanonicalHotDataColumnSidecar(
        final DataColumnSlotAndIdentifier columnSlotAndIdentifier) {
      return Optional.ofNullable(canonicalHotRoots.get(columnSlotAndIdentifier.slot()))
          .map(blockRoot -> blockRoot.equals(columnSlotAndIdentifier.blockRoot()))
          .orElse(false);
    }

    boolean isComplete() {
      return endSlot.isLessThan(startSlot)
          || dataColumnSidecarKeysIterator.map(iterator -> !iterator.hasNext()).orElse(false);
    }
  }
}
