/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.io.netty.udp;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ProtocolFamily;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Computations;
import reactor.core.scheduler.Timer;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;
import reactor.core.util.ExecutorUtils;
import reactor.core.util.Logger;
import reactor.io.ipc.Channel;
import reactor.io.ipc.ChannelHandler;
import reactor.io.netty.common.MonoChannelFuture;
import reactor.io.netty.common.NettyChannel;
import reactor.io.netty.common.NettyChannelHandler;
import reactor.io.netty.common.Peer;
import reactor.io.netty.config.ServerOptions;
import reactor.io.netty.tcp.TcpChannel;
import reactor.io.netty.util.NettyNativeDetector;

/**
 *
 * @author Stephane Maldini
 */
final public class UdpServer extends Peer<ByteBuf, ByteBuf, NettyChannel> {

	public static final int DEFAULT_UDP_THREAD_COUNT = Integer.parseInt(
	  System.getProperty("reactor.udp.ioThreadCount",
		"" + Computations.DEFAULT_POOL_SIZE)
	);

	/**
	 * Bind a new UDP server to the "loopback" address. The default server implementation is scanned from the
	 * classpath on Class init. Support for Netty is provided as long as the relevant library dependencies are on the
	 * classpath. <p> <p> From the emitted {@link Channel}, one can decide to add in-channel consumers to read
	 * any incoming data. <p> To reply data on the active connection, {@link Channel#send} can subscribe to
	 * any passed {@link Publisher}. <p> Note that {@link reactor.core.state.Backpressurable#getCapacity}
	 * will be used to switch on/off a channel in auto-read / flush on write mode. If the capacity is Long.MAX_Value,
	 * write on flush and auto read will apply. Otherwise, data will be flushed every capacity batch size and read will
	 * pause when capacity number of elements have been dispatched. <p> Emitted channels will run on the same thread
	 * they have beem receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static UdpServer create() {
		return create(DEFAULT_BIND_ADDRESS);
	}

	/**
	 * Bind a new UDP server to the given bind address. The default server implementation is scanned from the
	 * classpath on Class init. Support for Netty is provided as long as the relevant library dependencies are on the
	 * classpath. <p> <p> From the emitted {@link Channel}, one can decide to add in-channel consumers to read
	 * any incoming data. <p> To reply data on the active connection, {@link Channel#send} can subscribe to
	 * any passed {@link Publisher}. <p> Note that {@link reactor.core.state.Backpressurable#getCapacity}
	 * will be used to switch on/off a channel in auto-read / flush on write mode. If the capacity is Long.MAX_Value,
	 * write on flush and auto read will apply. Otherwise, data will be flushed every capacity batch size and read will
	 * pause when capacity number of elements have been dispatched. <p> Emitted channels will run on the same thread
	 * they have beem receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the server on the passed port
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static UdpServer create(String bindAddress) {
		return create(bindAddress, DEFAULT_PORT);
	}

	/**
	 * Bind a new UDP server to the "loopback" address and specified port. The default server implementation
	 * is scanned from the classpath on Class init. Support for Netty is provided as long as the relevant library
	 * dependencies are on the classpath. <p> <p> From the emitted {@link Channel}, one can decide to add
	 * in-channel consumers to read any incoming data. <p> To reply data on the active connection, {@link
	 * Channel#send} can subscribe to any passed {@link Publisher}. <p> Note that
	 * {@link reactor.core.state.Backpressurable#getCapacity} will be used to switch on/off a channel in auto-read / flush on
	 * write mode. If the capacity is Long.MAX_Value, write on flush and auto read will apply. Otherwise, data will be
	 * flushed every capacity batch size and read will pause when capacity number of elements have been dispatched. <p>
	 * Emitted channels will run on the same thread they have beem receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param port the port to listen on the passed bind address
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static UdpServer create(int port) {
		return create(DEFAULT_BIND_ADDRESS, port);
	}

	/**
	 * Bind a new UDP server to the given bind address and port. The default server implementation is scanned
	 * from the classpath on Class init. Support for Netty is provided as long as the relevant library dependencies are
	 * on the classpath. <p> <p> From the emitted {@link Channel}, one can decide to add in-channel consumers to
	 * read any incoming data. <p> To reply data on the active connection, {@link Channel#send} can
	 * subscribe to any passed {@link Publisher}. <p> Note that {@link
	 * reactor.core.state.Backpressurable#getCapacity} will be used to switch on/off a channel in auto-read / flush on write
	 * mode. If the capacity is Long.MAX_Value, write on flush and auto read will apply. Otherwise, data will be flushed
	 * every capacity batch size and read will pause when capacity number of elements have been dispatched. <p> Emitted
	 * channels will run on the same thread they have beem receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param port the port to listen on the passed bind address
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the server on the passed port
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static UdpServer create(String bindAddress, int port) {
		return create(ServerOptions.create()
		                                  .listen(bindAddress, port)
		                                  .timer(Timer.globalOrNull()));
	}/**
	 * Bind a new UDP server to the given bind address and port. The default server implementation is scanned
	 * from the classpath on Class init. Support for Netty is provided as long as the relevant library dependencies are
	 * on the classpath. <p> <p> From the emitted {@link Channel}, one can decide to add in-channel consumers to
	 * read any incoming data. <p> To reply data on the active connection, {@link Channel#send} can
	 * subscribe to any passed {@link Publisher}. <p> Note that {@link
	 * reactor.core.state.Backpressurable#getCapacity} will be used to switch on/off a channel in auto-read / flush on write
	 * mode. If the capacity is Long.MAX_Value, write on flush and auto read will apply. Otherwise, data will be flushed
	 * every capacity batch size and read will pause when capacity number of elements have been dispatched. <p> Emitted
	 * channels will run on the same thread they have beem receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param options
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static UdpServer create(ServerOptions options) {
		return new UdpServer(options);
	}

	final Bootstrap      bootstrap;
	final EventLoopGroup ioGroup;

	final InetSocketAddress listenAddress;
	final NetworkInterface  multicastInterface;
	final ServerOptions     options;

	volatile DatagramChannel channel;

	UdpServer(ServerOptions options) {
		super(options.timer(), options.prefetch());
		this.listenAddress = options.listenAddress();
		this.multicastInterface = options.multicastInterface();
		this.options = options.toImmutable();

		if (null != options.eventLoopGroup()) {
			this.ioGroup = options.eventLoopGroup();
		} else {
			int ioThreadCount = DEFAULT_UDP_THREAD_COUNT;
			this.ioGroup = options.protocolFamily() == null ?
							NettyNativeDetector.newEventLoopGroup(ioThreadCount, ExecutorUtils.newNamedFactory
									("reactor-udp-io")) :
							new NioEventLoopGroup(ioThreadCount, ExecutorUtils.newNamedFactory
									("reactor-udp-io"));
		}

		this.bootstrap = new Bootstrap()
				.group(ioGroup)
				.option(ChannelOption.AUTO_READ, false)
		;

		if ((options.protocolFamily() == null) &&
				NettyNativeDetector.getDatagramChannel(ioGroup).getSimpleName().startsWith("Epoll")) {
			bootstrap.channel(NettyNativeDetector.getDatagramChannel(ioGroup));
		} else {
			bootstrap.channelFactory(() -> new NioDatagramChannel(toNettyFamily(options.protocolFamily())));
		}

			bootstrap.option(ChannelOption.SO_RCVBUF, options.rcvbuf())
			         .option(ChannelOption.SO_SNDBUF, options.sndbuf())
			         .option(ChannelOption.SO_REUSEADDR, options.reuseAddr())
			         .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, options.timeout());

		if (null != listenAddress) {
			bootstrap.localAddress(listenAddress);
		} else {
			bootstrap.localAddress(NetUtil.LOCALHOST, 3000);
		}
		if (null != multicastInterface) {
			bootstrap.option(ChannelOption.IP_MULTICAST_IF, multicastInterface);
		}
	}

	/**
	 * Get the address to which this server is bound.
	 *
	 * @return the bind address
	 */
	public InetSocketAddress getListenAddress() {
		return listenAddress;
	}

	/**
	 * Get the {@link NetworkInterface} on which multicast will be performed.
	 *
	 * @return the multicast NetworkInterface
	 */
	public NetworkInterface getMulticastInterface() {
		return multicastInterface;
	}

	/**
	 * Get the {@link ServerOptions} currently in effect.
	 *
	 * @return the server options in use
	 */
	public ServerOptions getOptions() {
		return options;
	}

	/**
	 * Join a multicast group.
	 *
	 * @param multicastAddress multicast address of the group to join
	 * @return a {@link Publisher} that will be complete when the group has been joined
	 */
	public Mono<Void> join(InetAddress multicastAddress) {
		return join(multicastAddress, null);
	}

	/**
	 * Join a multicast group.
	 *
	 * @param multicastAddress multicast address of the group to join
	 * @return a {@link Publisher} that will be complete when the group has been joined
	 */
	public Mono<Void> join(final InetAddress multicastAddress, NetworkInterface iface) {
		if (null == channel) {
			throw new IllegalStateException("UdpServer not running.");
		}

		if (null == iface && null != getMulticastInterface()) {
			iface = getMulticastInterface();
		}

		final ChannelFuture future;
		if (null != iface) {
			future = channel.joinGroup(new InetSocketAddress(multicastAddress, getListenAddress().getPort()), iface);
		} else {
			future = channel.joinGroup(multicastAddress);
		}

		return new MonoChannelFuture<Future<?>>(future){
			@Override
			protected void doComplete(Future<?> future, Subscriber<? super Void> s) {
				log.info("JOIN {}", multicastAddress);
				super.doComplete(future, s);
			}
		};
	}

	/**
	 * Leave a multicast group.
	 *
	 * @param multicastAddress multicast address of the group to leave
	 * @return a {@link Publisher} that will be complete when the group has been left
	 */
	public Mono<Void> leave(InetAddress multicastAddress) {
		return leave(multicastAddress, null);
	}

	/**
	 * Leave a multicast group.
	 *
	 * @param multicastAddress multicast address of the group to leave
	 * @return a {@link Publisher} that will be complete when the group has been left
	 */
	public Mono<Void> leave(final InetAddress multicastAddress, NetworkInterface iface) {
		if (null == channel) {
			throw new IllegalStateException("UdpServer not running.");
		}

		if (null == iface && null != getMulticastInterface()) {
			iface = getMulticastInterface();
		}

		final ChannelFuture future;
		if (null != iface) {
			future = channel.leaveGroup(new InetSocketAddress(multicastAddress, getListenAddress().getPort()), iface);
		} else {
			future = channel.leaveGroup(multicastAddress);
		}

		return new MonoChannelFuture<Future<?>>(future){
			@Override
			protected void doComplete(Future<?> future, Subscriber<? super Void> s) {
				log.info("LEAVE {}", multicastAddress);
				super.doComplete(future, s);
			}
		};
	}

	@SuppressWarnings("unchecked")
	@Override
	protected Mono<Void> doStart(final ChannelHandler<ByteBuf, ByteBuf, NettyChannel> channelHandler) {
		return new Mono<Void>() {

			@Override
			public void subscribe(final Subscriber<? super Void> subscriber) {
				ChannelFuture future = bootstrap.handler(new ChannelInitializer<DatagramChannel>() {
					@Override
					public void initChannel(final DatagramChannel ch) throws Exception {
						if (null != getOptions() && null != getOptions().pipelineConfigurer()) {
							getOptions().pipelineConfigurer()
							            .accept(ch.pipeline());
						}

						bindChannel(channelHandler, ch);
					}
				})
				                                .bind();
				future.addListener(new ChannelFutureListener() {
					@Override
					public void operationComplete(ChannelFuture future) throws Exception {
						subscriber.onSubscribe(EmptySubscription.INSTANCE);
						if (future.isSuccess()) {
							log.info("BIND {}",
									future.channel()
									      .localAddress());
							channel = (DatagramChannel) future.channel();
							subscriber.onComplete();
						}
						else {
							Exceptions.throwIfFatal(future.cause());
							subscriber.onError(future.cause());
						}
					}
				});
			}
		};
	}

	@Override
	@SuppressWarnings("unchecked")
	protected Mono<Void> doShutdown() {
		return new MonoChannelFuture<ChannelFuture>(channel.close()) {
			@Override
			protected void doComplete(ChannelFuture future, Subscriber<? super Void> s) {
				if (null == getOptions() || null == getOptions().eventLoopGroup()) {
					MonoChannelFuture.from(ioGroup.shutdownGracefully()).subscribe(s);
				}
				else {
					super.doComplete(future, s);
				}
			}
		};
	}

	void bindChannel(ChannelHandler<ByteBuf, ByteBuf, NettyChannel> handler,
			Object _ioChannel) {
		DatagramChannel ioChannel = (DatagramChannel) _ioChannel;
		TcpChannel netChannel = new TcpChannel(
				getDefaultPrefetchSize(),
				ioChannel
		);

		ChannelPipeline pipeline = ioChannel.pipeline();

		if (log.isDebugEnabled()) {
			pipeline.addLast(new LoggingHandler(UdpServer.class));
		}

		pipeline.addLast(
				new NettyChannelHandler(handler, netChannel),
				new ChannelOutboundHandlerAdapter());
	}

	InternetProtocolFamily toNettyFamily(ProtocolFamily family) {
		if (family == null) {
			return null;
		}
		switch (family.name()) {
			case "INET":
				return InternetProtocolFamily.IPv4;
			case "INET6":
				return InternetProtocolFamily.IPv6;
			default:
				throw new IllegalArgumentException("Unsupported protocolFamily: " + family.name());
		}
	}
	final static Logger log = Logger.getLogger(UdpServer.class);

}
