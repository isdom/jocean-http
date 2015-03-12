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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
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
	    try {
	        if (!responseSubscriber.isUnsubscribed()) {
	        	responseSubscriber.add(Subscriptions.from(
        			createChannel(responseSubscriber)
        			.connect(this._remoteAddress)
                    .addListener(new ConnectListener(responseSubscriber))));
	        }
	    } catch (final Throwable e) {
	    	responseSubscriber.onError(e);
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
				// final SSLEngine engine =
				// AndroidSslContextFactory.getClientContext().createSSLEngine();
				// engine.setUseClientMode(true);
				//
				// pipeline.addLast(new SslHandler(
				// FixNeverReachFINISHEDStateSSLEngine.fixAndroidBug( engine ),
				// false));
			}
		
			pipeline.addLast(new HttpClientCodec());
		
			if (!Features.isEnabled(this._featuresAsInt, Feature.DisableCompress)) {
				pipeline.addLast(new HttpContentDecompressor());
			}
		
			pipeline.addLast(new SimpleChannelInboundHandler<HttpObject>() {
				@Override
				public void channelActive(final ChannelHandlerContext ctx)
						throws Exception {
					if (LOG.isDebugEnabled()) {
						LOG.debug("channelActive: ch({})", ctx.channel());
					}
		
					ctx.fireChannelActive();
		
					// if ( !this._sslEnabled ) {
					// this._receiver.acceptEvent(NettyEvents.CHANNEL_ACTIVE, ctx);
					// }
				}
		
				@Override
				public void channelInactive(final ChannelHandlerContext ctx)
						throws Exception {
					if (LOG.isDebugEnabled()) {
						LOG.debug("channelInactive: ch({})", ctx.channel());
					}
					ctx.fireChannelInactive();
					// TODO invoke onCompleted or onError dep Connection: close or
					// not
				}
		
				@Override
				public void userEventTriggered(final ChannelHandlerContext ctx,
						final Object evt) throws Exception {
					if (LOG.isDebugEnabled()) {
						LOG.debug("userEventTriggered: ch({}) with event:{}",
								ctx.channel(), evt);
					}
		
					ctx.fireUserEventTriggered(evt);
		
					// if ( this._sslEnabled && (evt instanceof
					// SslHandshakeCompletionEvent)) {
					// if ( ((SslHandshakeCompletionEvent)evt).isSuccess() ) {
					// this._receiver.acceptEvent(NettyEvents.CHANNEL_ACTIVE, ctx);
					// }
					// }
					// else {
					// this._receiver.acceptEvent(NettyEvents.CHANNEL_USEREVENTTRIGGERED,
					// ctx, evt);
					// }
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
						responseSubscriber.onCompleted();
						// TODO consider Connection: close case
					}
				}
			});
			
			return channel;
		} catch (Throwable e) {
			if (null!=channel) {
				channel.close();
			}
			throw e;
		}
	}
	
	private final class ConnectListener implements
			GenericFutureListener<ChannelFuture> {
		private ConnectListener(final Subscriber<? super HttpObject> responseSubscriber) {
			this._responseSubscriber = responseSubscriber;
		}

		@Override
		public void operationComplete(final ChannelFuture future)
				throws Exception {
			final Channel channel = future.channel();
			if (future.isSuccess()) {
				this._responseSubscriber.add(
					_request.subscribe(
						new RequestSubscriber(
							_featuresAsInt, 
							channel, 
							this._responseSubscriber)));
				this._responseSubscriber.add(new Subscription() {
					@Override
					public void unsubscribe() {
						channel.close();
					}
					@Override
					public boolean isUnsubscribed() {
						return channel.isActive();
					}});
			}
			else {
				try {
					this._responseSubscriber.onError(future.cause());
				} finally {
					channel.close();
				}
			}
		}
		
		private final Subscriber<? super HttpObject> _responseSubscriber;
	}
	
	private final int	_featuresAsInt;
	private final SocketAddress _remoteAddress;
	private final Observable<HttpObject> _request;
	private final Callable<Channel> _newChannel;
	private final SslContext _sslCtx;
}