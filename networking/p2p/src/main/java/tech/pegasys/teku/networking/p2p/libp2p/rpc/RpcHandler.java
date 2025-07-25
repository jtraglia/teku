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

package tech.pegasys.teku.networking.p2p.libp2p.rpc;

import static tech.pegasys.teku.infrastructure.async.FutureUtil.ignoreFuture;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import io.libp2p.core.Connection;
import io.libp2p.core.ConnectionClosedException;
import io.libp2p.core.P2PChannel;
import io.libp2p.core.Stream;
import io.libp2p.core.StreamPromise;
import io.libp2p.core.multistream.ProtocolBinding;
import io.libp2p.core.multistream.ProtocolDescriptor;
import io.libp2p.etc.util.netty.mux.RemoteWriteClosed;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.jetbrains.annotations.NotNull;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.async.SafeFuture.Interruptor;
import tech.pegasys.teku.infrastructure.exceptions.ExceptionUtil;
import tech.pegasys.teku.networking.p2p.libp2p.LibP2PNodeId;
import tech.pegasys.teku.networking.p2p.libp2p.rpc.RpcHandler.Controller;
import tech.pegasys.teku.networking.p2p.peer.NodeId;
import tech.pegasys.teku.networking.p2p.peer.PeerDisconnectedException;
import tech.pegasys.teku.networking.p2p.rpc.RpcMethod;
import tech.pegasys.teku.networking.p2p.rpc.RpcRequestHandler;
import tech.pegasys.teku.networking.p2p.rpc.RpcResponseHandler;
import tech.pegasys.teku.networking.p2p.rpc.RpcStream;
import tech.pegasys.teku.networking.p2p.rpc.RpcStreamController;
import tech.pegasys.teku.networking.p2p.rpc.StreamClosedException;
import tech.pegasys.teku.networking.p2p.rpc.StreamTimeoutException;
import tech.pegasys.teku.spec.datastructures.networking.libp2p.rpc.RpcRequest;
import tech.pegasys.teku.spec.datastructures.networking.libp2p.rpc.bodyselector.RpcRequestBodySelector;
import tech.pegasys.teku.spec.datastructures.networking.libp2p.rpc.bodyselector.SingleRpcRequestBodySelector;

public class RpcHandler<
        TOutgoingHandler extends RpcRequestHandler,
        TRequest extends RpcRequest,
        TRespHandler extends RpcResponseHandler<?>>
    implements ProtocolBinding<Controller<TOutgoingHandler>> {

  private static final Logger LOG = LogManager.getLogger();

  private static final Duration STREAM_INITIALIZE_TIMEOUT = Duration.ofSeconds(5);

  private final AsyncRunner asyncRunner;
  private final RpcMethod<TOutgoingHandler, TRequest, TRespHandler> rpcMethod;

  public RpcHandler(
      final AsyncRunner asyncRunner,
      final RpcMethod<TOutgoingHandler, TRequest, TRespHandler> rpcMethod) {
    this.asyncRunner = asyncRunner;
    this.rpcMethod = rpcMethod;
  }

  public RpcMethod<TOutgoingHandler, TRequest, TRespHandler> getRpcMethod() {
    return rpcMethod;
  }

  public SafeFuture<RpcStreamController<TOutgoingHandler>> sendRequest(
      final Connection connection, final TRequest request, final TRespHandler responseHandler) {
    return sendRequestWithBodySelector(
        connection, new SingleRpcRequestBodySelector<>(request), responseHandler);
  }

  public SafeFuture<RpcStreamController<TOutgoingHandler>> sendRequestWithBodySelector(
      final Connection connection,
      final RpcRequestBodySelector<TRequest> bodySelector,
      final TRespHandler responseHandler) {
    final Interruptor closeInterruptor =
        SafeFuture.createInterruptor(connection.closeFuture(), PeerDisconnectedException::new);
    final Interruptor timeoutInterruptor =
        SafeFuture.createInterruptor(
            asyncRunner.getDelayedFuture(STREAM_INITIALIZE_TIMEOUT),
            () ->
                new StreamTimeoutException(
                    "Timed out waiting to initialize stream for protocol(s): "
                        + String.join(",", rpcMethod.getIds())));

    return SafeFuture.notInterrupted(closeInterruptor)
        .thenApply(__ -> connection.muxerSession().createStream(this))
        // waiting for a stream or interrupt
        .thenWaitFor(StreamPromise::getStream)
        .orInterrupt(closeInterruptor, timeoutInterruptor)
        .thenCompose(
            streamPromise -> {
              final SafeFuture<String> protocolIdFuture =
                  SafeFuture.of(streamPromise.getStream().thenCompose(Stream::getProtocol));
              // waiting for controller, writing initial payload or interrupt
              return SafeFuture.of(streamPromise.getController())
                  .<String, RpcStreamController<TOutgoingHandler>>thenCombine(
                      protocolIdFuture,
                      (controller, protocolId) -> {
                        final TOutgoingHandler handler =
                            rpcMethod.createOutgoingRequestHandler(
                                protocolId,
                                requiredRequestBodyForProtocolId(bodySelector, protocolId),
                                responseHandler);
                        controller.setOutgoingRequestHandler(handler);
                        return controller;
                      })
                  .orInterrupt(closeInterruptor, timeoutInterruptor)
                  .thenWaitFor(
                      controller -> {
                        return protocolIdFuture.thenCompose(
                            protocolId -> {
                              final Bytes initialPayload;
                              try {
                                initialPayload =
                                    rpcMethod.encodeRequest(
                                        requiredRequestBodyForProtocolId(bodySelector, protocolId));
                              } catch (Exception e) {
                                return SafeFuture.failedFuture(e);
                              }
                              return controller.getRpcStream().writeBytes(initialPayload);
                            });
                      })
                  .orInterrupt(closeInterruptor, timeoutInterruptor)
                  // closing the stream in case of any errors or interruption
                  .whenException(err -> closeStreamAbruptly(streamPromise.getStream().join()));
            })
        .catchAndRethrow(
            err -> {
              if (ExceptionUtil.hasCause(err, ConnectionClosedException.class)) {
                throw new PeerDisconnectedException(err);
              }
            });
  }

  private TRequest requiredRequestBodyForProtocolId(
      final RpcRequestBodySelector<TRequest> bodySelector, final String protocolId) {
    return bodySelector
        .getBody()
        .apply(protocolId)
        .orElseThrow(
            () -> new IllegalStateException("No request body for protocolId: " + protocolId));
  }

  private void closeStreamAbruptly(final Stream stream) {
    SafeFuture.of(stream.close()).ifExceptionGetsHereRaiseABug();
  }

  @NotNull
  @Override
  public ProtocolDescriptor getProtocolDescriptor() {
    return new ProtocolDescriptor(rpcMethod.getIds());
  }

  @NotNull
  @Override
  public SafeFuture<Controller<TOutgoingHandler>> initChannel(
      final P2PChannel channel, final String selectedProtocol) {
    final Connection connection = ((Stream) channel).getConnection();
    final NodeId nodeId = new LibP2PNodeId(connection.secureSession().getRemoteId());

    final Controller<TOutgoingHandler> controller = new Controller<>(nodeId, channel);
    if (!channel.isInitiator()) {
      controller.setIncomingRequestHandler(
          rpcMethod.createIncomingRequestHandler(selectedProtocol));
    }
    channel.pushHandler(controller);
    return controller.activeFuture;
  }

  static class Controller<TOutgoingHandler extends RpcRequestHandler>
      extends SimpleChannelInboundHandler<ByteBuf>
      implements RpcStreamController<TOutgoingHandler> {

    private final NodeId nodeId;
    private final P2PChannel p2pChannel;
    private Optional<TOutgoingHandler> outgoingRequestHandler = Optional.empty();
    private Optional<RpcRequestHandler> rpcRequestHandler = Optional.empty();
    private RpcStream rpcStream;
    private boolean readCompleted = false;

    protected final SafeFuture<Controller<TOutgoingHandler>> activeFuture = new SafeFuture<>();

    private Controller(final NodeId nodeId, final P2PChannel p2pChannel) {
      this.nodeId = nodeId;
      this.p2pChannel = p2pChannel;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
      rpcStream = new LibP2PRpcStream(nodeId, p2pChannel, ctx);
      activeFuture.complete(this);
    }

    @Override
    public RpcStream getRpcStream() {
      return rpcStream;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final ByteBuf msg) {
      runHandler(h -> h.processData(nodeId, rpcStream, msg));
    }

    public void setIncomingRequestHandler(final RpcRequestHandler requestHandler) {
      setRequestHandler(requestHandler);
      setFullyActive(requestHandler);
    }

    public void setOutgoingRequestHandler(final TOutgoingHandler requestHandler) {
      setRequestHandler(requestHandler);
      this.outgoingRequestHandler = Optional.of(requestHandler);
      setFullyActive(requestHandler);
    }

    private void setRequestHandler(final RpcRequestHandler rpcRequestHandler) {
      if (this.rpcRequestHandler.isPresent()) {
        throw new IllegalStateException("Attempt to set an already set data handler");
      }
      this.rpcRequestHandler = Optional.of(rpcRequestHandler);
    }

    private void setFullyActive(final RpcRequestHandler rpcRequestHandler) {
      activeFuture.finish(
          () -> {
            rpcRequestHandler.active(nodeId, rpcStream);
          },
          err -> {
            if (err != null) {
              if (Throwables.getRootCause(err) instanceof StreamClosedException) {
                LOG.debug("Stream closed while processing rpc input", err);
              } else {
                LOG.error("Unhandled exception while processing rpc input", err);
              }
            }
          });
    }

    @Override
    public Optional<TOutgoingHandler> getOutgoingRequestHandler() {
      return outgoingRequestHandler;
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
      LOG.error("Unhandled error while processes req/response", cause);
      final IllegalStateException exception = new IllegalStateException("Channel exception", cause);
      activeFuture.completeExceptionally(exception);
      closeAbruptly();
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
      onChannelClosed();
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) {
      if (evt instanceof RemoteWriteClosed) {
        onRemoteWriteClosed();
      }
    }

    private void onRemoteWriteClosed() {
      if (!readCompleted) {
        readCompleted = true;
        runHandler(h -> h.readComplete(nodeId, rpcStream));
      }
    }

    private void onChannelClosed() {
      try {
        onRemoteWriteClosed();
        runHandler(h -> h.closed(nodeId, rpcStream));
      } finally {
        rpcRequestHandler = Optional.empty();
      }
    }

    private void runHandler(final Consumer<RpcRequestHandler> action) {
      rpcRequestHandler.ifPresentOrElse(action, this::closeAbruptly);
    }

    @VisibleForTesting
    void closeAbruptly() {
      // We're listening for the result of the close future above, so we can ignore this future
      ignoreFuture(p2pChannel.close());

      // Make sure to complete activation future in case we are never activated
      activeFuture.completeExceptionally(new StreamClosedException());
    }
  }
}
