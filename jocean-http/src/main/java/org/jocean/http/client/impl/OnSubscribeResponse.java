package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.SocketAddress;
import java.util.concurrent.Callable;

import org.jocean.http.client.HttpClient.Feature;
import org.jocean.idiom.Features;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action1;
import rx.subscriptions.Subscriptions;

final class OnSubscribeResponse implements
	OnSubscribe<HttpObject> {

	private static final Logger LOG =
			LoggerFactory.getLogger(OnSubscribeResponse.class);
	
	OnSubscribeResponse(
			final int featuresAsInt,
			final SslContext sslCtx,
			final Callable<Channel> newChannel, 
			final SocketAddress remoteAddress,
			final Observable<HttpObject> request) {
			this._sslCtx = sslCtx;
			this._newChannel = newChannel;
			this._featuresAsInt = featuresAsInt;
			this._remoteAddress = remoteAddress;
			this._request = request;
		}
		
	@Override
	public void call(final Subscriber<? super HttpObject> responseSubscriber) {
        final Subscriber<? super HttpObject> wrapper = 
                RxSubscribers.guardUnsubscribed(responseSubscriber);
	    try {
	        if (!wrapper.isUnsubscribed()) {
	            wrapper.add(
        			Subscriptions.from(
	        			createChannel(wrapper)
	        			.connect(this._remoteAddress)
	                    .addListener(createConnectListener(wrapper))));
	        }
	    } catch (final Throwable e) {
	        wrapper.onError(e);
	    }
	}

	private Channel createChannel(final Subscriber<? super HttpObject> responseSubscriber) 
			throws Exception {
		final Channel channel = this._newChannel.call();
		
		try {
			final ChannelPipeline pipeline = channel.pipeline();
		
			if (Features.isEnabled(this._featuresAsInt, Feature.EnableLOG)) {
				pipeline.addLast(new LoggingHandler());
			}
		
			// Enable SSL if necessary.
			if (null != this._sslCtx) {
				pipeline.addLast(this._sslCtx.newHandler(channel.alloc()));
			}
		
			pipeline.addLast(new HttpClientCodec());
		
			if (!Features.isEnabled(this._featuresAsInt, Feature.DisableCompress)) {
				pipeline.addLast(new HttpContentDecompressor());
			}
		
			pipeline.addLast(createResponseHandler(responseSubscriber, channel));
			
			return channel;
		} catch (Throwable e) {
			if (null!=channel) {
				channel.close();
			}
			throw e;
		}
	}

	private SimpleChannelInboundHandler<HttpObject> createResponseHandler(
			final Subscriber<? super HttpObject> responseSubscriber,
			final Channel channel) {
		return new SimpleChannelInboundHandler<HttpObject>() {

			@Override
			public void channelActive(final ChannelHandlerContext ctx)
					throws Exception {
				if (LOG.isDebugEnabled()) {
					LOG.debug("channelActive: ch({})", ctx.channel());
				}

				ctx.fireChannelActive();
			}

			@Override
			public void channelInactive(final ChannelHandlerContext ctx)
					throws Exception {
				if (LOG.isDebugEnabled()) {
					LOG.debug("channelInactive: ch({})", ctx.channel());
				}
				ctx.fireChannelInactive();
			    responseSubscriber.onError(new RuntimeException("peer has closed."));
			}

			@Override
			public void userEventTriggered(final ChannelHandlerContext ctx,
					final Object evt) throws Exception {
				if (LOG.isDebugEnabled()) {
					LOG.debug("userEventTriggered: ch({}) with event:{}",
							ctx.channel(), evt);
				}

				ctx.fireUserEventTriggered(evt);
			}

			@Override
			public void exceptionCaught(final ChannelHandlerContext ctx,
					final Throwable cause) throws Exception {
				channel.close();
			    responseSubscriber.onError(cause);
			}

			@Override
			protected void channelRead0(
					final ChannelHandlerContext ctx,
					final HttpObject msg) throws Exception {
				responseSubscriber.onNext(msg);
				if (msg instanceof LastHttpContent) {
	                /* netty 参考代码:
	                 * https://github.com/netty/netty/blob/netty-4.0.26.Final/codec/src/main/java/io/netty/handler/codec/ByteToMessageDecoder.java#L274
	                 * https://github.com/netty/netty/blob/netty-4.0.26.Final/codec-http/src/main/java/io/netty/handler/codec/http/HttpObjectDecoder.java#L398
	                 * 从上述代码可知, 当Connection断开时，首先会检查是否满足特定条件
	                 * currentState == State.READ_VARIABLE_LENGTH_CONTENT && !in.isReadable() && !chunked
	                 * 即没有指定Content-Length头域，也不是CHUNKED传输模式，此情况下，即会自动产生一个LastHttpContent.EMPTY_LAST_CONTENT实例
	                 * 因此，无需在channelInactive处，针对该情况做特殊处理
	                 */
	                if (LOG.isDebugEnabled()) {
	                    LOG.debug("channelRead0: ch({}) recv LastHttpContent:{}",
	                            ctx.channel(), msg);
	                }
					responseSubscriber.onCompleted();
				}
			}
		};
	}
	
	private GenericFutureListener<ChannelFuture> createConnectListener(
			final Subscriber<? super HttpObject> responseSubscriber) {
		return new GenericFutureListener<ChannelFuture>() {
			@Override
			public void operationComplete(final ChannelFuture future)
					throws Exception {
				final Channel channel = future.channel();
				if (future.isSuccess()) {
					responseSubscriber.add(
						_request.subscribe(
							new RequestSubscriber(
								_featuresAsInt, 
								channel, 
								new Action1<Throwable>() {
                                    @Override
                                    public void call(final Throwable cause) {
                                        responseSubscriber.onError(cause);                                        
                                    }})));
					responseSubscriber.add(new Subscription() {
						@Override
						public void unsubscribe() {
							channel.close();
						}
						@Override
						public boolean isUnsubscribed() {
							return !channel.isActive();
						}});
				} else {
					try {
						responseSubscriber.onError(future.cause());
					} finally {
						channel.close();
					}
				}
			}
		};
	}
	
	private final int	_featuresAsInt;
	private final SocketAddress _remoteAddress;
	private final Observable<HttpObject> _request;
	private final Callable<Channel> _newChannel;
	private final SslContext _sslCtx;
}