/**
 * 
 */
package org.jocean.http.client.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GenericFutureListener;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.Builder;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
public class DefaultClientBuilder implements Builder {

	private static final Logger LOG =
			LoggerFactory.getLogger(DefaultClientBuilder.class);
	
	private static final class DefaultHttpClient 
		implements HttpClient, OnSubscribe<HttpObject> {
		DefaultHttpClient(final Channel channel) {
			this._channel = channel;
			final ChannelPipeline pipeline = channel.pipeline();
			pipeline.addLast(new SimpleChannelInboundHandler<HttpObject>() {
				@Override
				public void channelActive(final ChannelHandlerContext ctx) throws Exception {
			        if ( LOG.isDebugEnabled() ) {
			            LOG.debug("channelActive: ch({})", ctx.channel());
			        }
			        
			        ctx.fireChannelActive();
					
//					if ( !this._sslEnabled ) {
//						this._receiver.acceptEvent(NettyEvents.CHANNEL_ACTIVE, ctx);
//					}
				}
				
				@Override
				public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
			        if ( LOG.isDebugEnabled() ) {
			            LOG.debug("channelInactive: ch({})", ctx.channel());
			        }
			        
			        ctx.fireChannelInactive();
				}
				
				@Override
				public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt)
						throws Exception {
					if ( LOG.isDebugEnabled() ) {
						LOG.debug("userEventTriggered: ch({}) with event:{}", ctx.channel(), evt);
					}
					
		            ctx.fireUserEventTriggered(evt);
					
//					if ( this._sslEnabled && (evt instanceof SslHandshakeCompletionEvent)) {
//						if ( ((SslHandshakeCompletionEvent)evt).isSuccess() ) {
//		    				this._receiver.acceptEvent(NettyEvents.CHANNEL_ACTIVE, ctx);
//						}
//					}
//					else {
//						this._receiver.acceptEvent(NettyEvents.CHANNEL_USEREVENTTRIGGERED, ctx, evt);
//					}
				}
				
			    @Override
			    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause)
			            throws Exception {
			    	_channel.close();
					doOnError(cause);
			    }

			    @Override
				protected void channelRead0(
						final ChannelHandlerContext ctx,
						final HttpObject msg) throws Exception {
					_subscriber.onNext(msg);
					if (msg instanceof LastHttpContent) {
						doOnComplete();
						//	TODO consider Connection: close case
					}
				}});
		}
		
		@Override
		public void close() throws IOException {
			this._isClosed = true;
	        ReferenceCountUtil.safeRelease(this._reuqest);
	        this._reuqest = null;
	        
	        for (HttpContent content : this._contents) {
	            ReferenceCountUtil.safeRelease(content);
	        }
	        this._contents.clear();
			this._channel.close();
		}

		@Override
		public HttpClient appendContent(
				final HttpContent content) {
			this._contents.add(ReferenceCountUtil.retain(content));
			if (this._isStart) {
				sendHttpObject(content);
			}
			return this;
		}

		private void sendHttpObject(final HttpObject obj) {
			this._channel.writeAndFlush(ReferenceCountUtil.retain(obj))
			.addListener(new GenericFutureListener<ChannelFuture> () {
				@Override
				public void operationComplete(final ChannelFuture future)
						throws Exception {
					if (!future.isSuccess()) {
						doOnError(future.cause());
					}
				}});
		}

		@Override
		public Observable<HttpObject> sendRequest(
				final HttpRequest request) {
			this._reuqest = ReferenceCountUtil.retain(request);
			return Observable.create(this);
		}
		
		@Override
		public void call(final Subscriber<? super HttpObject> subscriber) {
			if (this._isStart) {
				// already started, onError TODO
				subscriber.onError(new RuntimeException("Already Started"));
			}
			else {
	            if (!subscriber.isUnsubscribed()) {
	            	_isStart = true;
	                this._subscriber = subscriber;
	                sendHttpObject(this._reuqest);
	                for ( HttpContent content : this._contents) {
	                	sendHttpObject(content);
	                }
	                subscriber.add(new Subscription() {
						@Override
						public void unsubscribe() {
							try {
								close();
							} catch (IOException e) {
								LOG.warn("exception when invoke ch:({})'s close, detail:{}", 
										_channel, ExceptionUtils.exception2detail(e));
							}
						}
						@Override
						public boolean isUnsubscribed() {
							return _isClosed;
						}});
	            }
			}
		}
		
		private void doOnComplete() {
			if (null!=this._subscriber) {
				final Subscriber<? super HttpObject> subscriber = this._subscriber;
				this._subscriber = null;
				subscriber.onCompleted();
			}
		}
		
		private void doOnError(final Throwable cause) {
			if (null!=this._subscriber) {
				final Subscriber<? super HttpObject> subscriber = this._subscriber;
				this._subscriber = null;
				subscriber.onError(cause);
			}
		}
		
		private final Channel 	_channel;
		private HttpRequest 	_reuqest;
		private final List<HttpContent>	_contents = new ArrayList<>();
		private boolean			_isStart = false;
		private boolean 		_isClosed = false;
		private Subscriber<? super HttpObject> _subscriber;
	}
	
	private final class OnSubscribeHttpClient implements
			OnSubscribe<HttpClient> {

		OnSubscribeHttpClient(final URI uri) {
			this._uri = uri;
		}
		
		@Override
		public void call(final Subscriber<? super HttpClient> subscriber) {
	        try {
	            if (!subscriber.isUnsubscribed()) {
	            	final Channel channel = newChannel();
	            	final ChannelFuture connectFuture = channel.connect(
	            			new InetSocketAddress(
	                        this._uri.getHost(), this._uri.getPort()));
	                connectFuture.addListener(new GenericFutureListener<ChannelFuture> (){
						@Override
						public void operationComplete(final ChannelFuture future)
								throws Exception {
							if (future.isSuccess()) {
			                	subscriber.onNext(new DefaultHttpClient(future.channel()));
				                subscriber.onCompleted();
							}
							else {
								try {
									subscriber.onError(future.cause());
								} finally {
									channel.close();
								}
								
							}
						}});
	                subscriber.add(Subscriptions.from(connectFuture));
	            }
	        } catch (final Throwable e) {
	        	subscriber.onError(e);
	        }
		}
		
		private final URI _uri;
	}

	/* (non-Javadoc)
	 * @see org.jocean.http.client.HttpClient.Builder#build(java.net.URI)
	 */
	@Override
	public Observable<HttpClient> build(final URI uri) {
		return Observable.create(new OnSubscribeHttpClient(uri));
	}
	
	public DefaultClientBuilder() {
		this(1);
	}
	
	public DefaultClientBuilder(final int processThreadNumber) {
        // Configure the client.
    	this._bootstrap = new Bootstrap()
    		.group(new NioEventLoopGroup(processThreadNumber))
    		.channel(NioSocketChannel.class)
    		.handler(new ChannelInitializer<Channel>() {
				@Override
				protected void initChannel(final Channel channel) throws Exception {
					//	TODO init channel enable ssl
			        // Create a default pipeline implementation.
			        final ChannelPipeline pipeline = channel.pipeline();

		        	pipeline.addLast(new LoggingHandler());
			        
			        // Enable HTTPS if necessary.
//			        final String scheme = uri.getScheme();
//			        final boolean sslEnabled = "https".equalsIgnoreCase(scheme);
//			        if ( sslEnabled ) {
//			            final SSLEngine engine =
//			                AndroidSslContextFactory.getClientContext().createSSLEngine();
//			            engine.setUseClientMode(true);
//
//			            p.addLast("ssl", new SslHandler(
//			            		FixNeverReachFINISHEDStateSSLEngine.fixAndroidBug( engine ), false));
//			        }

			        pipeline.addLast(new HttpClientCodec());

			        // Remove the following line if you don't want automatic content decompression.
			        pipeline.addLast(new HttpContentDecompressor());
				}});
		
	}

	public void destroy() {
        // Shut down executor threads to exit.
        this._bootstrap.group().shutdownGracefully();
	}
	
	protected Channel newChannel() {
		final Channel ch = this._bootstrap.register().channel();
		//ch.config().setAllocator(new AlwaysUnpooledHeapByteBufAllocator() );
		if ( LOG.isDebugEnabled() ) {
			LOG.debug("create new channel: {}", ch);
		}
		return	ch;
	}
	
    private final Bootstrap _bootstrap;
}
