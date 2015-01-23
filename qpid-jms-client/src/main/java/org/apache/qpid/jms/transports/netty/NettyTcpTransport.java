/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.qpid.jms.transports.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.qpid.jms.transports.Transport;
import org.apache.qpid.jms.transports.TransportListener;
import org.apache.qpid.jms.transports.TransportOptions;
import org.apache.qpid.jms.util.IOExceptionSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.net.impl.PartialPooledByteBufAllocator;

/**
 * TCP based transport that uses Netty as the underlying IO layer.
 */
public class NettyTcpTransport implements Transport {

    private static final Logger LOG = LoggerFactory.getLogger(NettyTcpTransport.class);

    private static final int QUIET_PERIOD = 20;
    private static final int SHUTDOWN_TIMEOUT = 100;

    protected Bootstrap bootstrap;
    protected EventLoopGroup group;
    protected Channel channel;
    protected TransportListener listener;
    protected TransportOptions options;
    protected final URI remote;

    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch connectLatch = new CountDownLatch(1);
    private IOException failureCause;

    /**
     * Create a new transport instance
     *
     * @param remoteLocation
     *        the URI that defines the remote resource to connect to.
     * @param options
     *        the transport options used to configure the socket connection.
     */
    public NettyTcpTransport(URI remoteLocation, TransportOptions options) {
        this(null, remoteLocation, options);
    }

    /**
     * Create a new transport instance
     *
     * @param listener
     *        the TransportListener that will receive events from this Transport.
     * @param remoteLocation
     *        the URI that defines the remote resource to connect to.
     * @param options
     *        the transport options used to configure the socket connection.
     */
    public NettyTcpTransport(TransportListener listener, URI remoteLocation, TransportOptions options) {
        this.options = options;
        this.listener = listener;
        this.remote = remoteLocation;
    }

    @Override
    public void connect() throws IOException {

        if (listener == null) {
            throw new IllegalStateException("A transport listener must be set before connection attempts.");
        }

        group = new NioEventLoopGroup(1);

        bootstrap = new Bootstrap();
        bootstrap.group(group);
        bootstrap.channel(NioSocketChannel.class);
        bootstrap.handler(new ChannelInitializer<Channel>() {

            @Override
            public void initChannel(Channel connectedChannel) throws Exception {
                configureChannel(connectedChannel);
            }
        });

        configureNetty(bootstrap, getTransportOptions());

        ChannelFuture future = bootstrap.connect(remote.getHost(), remote.getPort());
        future.addListener(new ChannelFutureListener() {

            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    handleConnected(future.channel());
                } else if (future.isCancelled()) {
                    connectionFailed(new IOException("Connection attempt was cancelled"));
                } else {
                    connectionFailed(IOExceptionSupport.create(future.cause()));
                }
            }
        });

        try {
            connectLatch.await();
        } catch (InterruptedException ex) {
            LOG.debug("Transport connection was interrupted.");
            Thread.interrupted();
            throw IOExceptionSupport.create(ex);
        }

        if (failureCause != null) {
            throw failureCause;
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            connected.set(false);
            if (channel != null) {
                channel.close().syncUninterruptibly();
            }
            if (group != null) {
                group.shutdownGracefully(QUIET_PERIOD, SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public void send(ByteBuf output) throws IOException {
        checkConnected();
        int length = output.readableBytes();
        if (length == 0) {
            return;
        }

        LOG.trace("Attempted write of: {} bytes", length);

        channel.writeAndFlush(output);
    }

    @Override
    public TransportListener getTransportListener() {
        return listener;
    }

    @Override
    public void setTransportListener(TransportListener listener) {
        this.listener = listener;
    }

    @Override
    public TransportOptions getTransportOptions() {
        if (options == null) {
            options = TransportOptions.DEFAULT_OPTIONS;
        }

        return options;
    }

    //----- Internal implementation details, can be overridden as needed --//

    protected void configureNetty(Bootstrap bootstrap, TransportOptions options) {
        bootstrap.option(ChannelOption.TCP_NODELAY, options.isTcpNoDelay());
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, options.getConnectTimeout());
        bootstrap.option(ChannelOption.SO_KEEPALIVE, options.isTcpKeepAlive());
        bootstrap.option(ChannelOption.SO_LINGER, options.getSoLinger());
        bootstrap.option(ChannelOption.ALLOCATOR, PartialPooledByteBufAllocator.INSTANCE);

        if (options.getSendBufferSize() != -1) {
            bootstrap.option(ChannelOption.SO_SNDBUF, options.getSendBufferSize());
        }

        if (options.getReceiveBufferSize() != -1) {
            bootstrap.option(ChannelOption.SO_RCVBUF, options.getReceiveBufferSize());
            bootstrap.option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(options.getReceiveBufferSize()));
        }

        if (options.getTrafficClass() != -1) {
            bootstrap.option(ChannelOption.IP_TOS, options.getTrafficClass());
        }
    }

    protected void configureChannel(Channel channel) throws Exception {
        channel.pipeline().addLast(new NettyTcpTransportHandler());
    }

    protected void handleConnected(Channel channel) throws Exception {
        connectionEstablished(channel);
    }

    //----- State change handlers and checks ---------------------------------//

    /**
     * Called when the transport has successfully connected and is ready for use.
     */
    protected void connectionEstablished(Channel connectedChannel) {
        channel = connectedChannel;
        connected.set(true);
        connectLatch.countDown();
    }

    /**
     * Called when the transport connection failed and an error should be returned.
     * @param cause
     */
    protected void connectionFailed(IOException cause) {
        failureCause = IOExceptionSupport.create(cause);
        connected.set(false);
        connectLatch.countDown();
    }

    private void checkConnected() throws IOException {
        if (!connected.get()) {
            throw new IOException("Cannot send to a non-connected transport.");
        }
    }

    //----- Handle connection events -----------------------------------------//

    private class NettyTcpTransportHandler extends SimpleChannelInboundHandler<ByteBuf> {

        @Override
        public void channelActive(ChannelHandlerContext context) throws Exception {
            LOG.trace("Channel has become active! Channel is {}", context.channel());
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) throws Exception {
            LOG.trace("Channel has gone inactive! Channel is {}", context.channel());
            if (!closed.get()) {
                connected.set(false);
                listener.onTransportClosed();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
            LOG.trace("Exception on channel! Channel is {}", context.channel());
            if (!closed.get()) {
                connected.set(false);
                listener.onTransportError(cause);
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
            LOG.trace("New data read: {} bytes incoming: {}", buffer.readableBytes(), buffer);
            listener.onData(buffer);
        }
    }
}
