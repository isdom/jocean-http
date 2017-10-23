package org.jocean.http.server.impl;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.client.impl.TestChannelCreator;
import org.jocean.http.client.impl.TestChannelPool;
import org.jocean.http.server.HttpServerBuilder;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.ExceptionUtils;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;

public class DefaultHttpServerBuilderTestCase {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpServerBuilderTestCase.class);
    public static final byte[] CONTENT = { 'H', 'e', 'l', 'l', 'o', ' ', 'W', 'o', 'r', 'l', 'd' };
    
    private Action1<HttpTrade> echoReactor(final AtomicReference<Object> transportRef) {
        return new Action1<HttpTrade>() {
            @Override
            public void call(final HttpTrade trade) {
                if (null!=transportRef) {
                    transportRef.set(trade.transport());
                }
//                final HttpMessageHolder holder = new HttpMessageHolder();
                trade.inbound().compose(RxNettys.message2fullreq(trade))
                .subscribe(new Action1<DisposableWrapper<FullHttpRequest>>() {
                    @Override
                    public void call(final DisposableWrapper<FullHttpRequest> dwreq) {
                        try (final InputStream is = new ByteBufInputStream(dwreq.unwrap().content())) {
                            final byte[] bytes = new byte[is.available()];
                            is.read(bytes);
                            final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, 
                                    Unpooled.wrappedBuffer(bytes));
                            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 
                                    response.content().readableBytes());
                            trade.outbound(Observable.<HttpObject>just(response));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }});
                
                /*
                .inbound().compose(holder.assembleAndHold()).subscribe(
                    RxSubscribers.ignoreNext(),
                    RxSubscribers.ignoreError(),
                    new Action0() {
                        @Override
                        public void call() {
                            final FullHttpRequest req = holder.fullOf(RxNettys.BUILD_FULL_REQUEST).call();
                            if (null!=req) {
                                try {
                                    try (final InputStream is = new ByteBufInputStream(req.content())) {
                                        final byte[] bytes = new byte[is.available()];
                                        is.read(bytes);
                                        final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, 
                                                Unpooled.wrappedBuffer(bytes));
                                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 
                                                response.content().readableBytes());
                                        trade.outbound(Observable.<HttpObject>just(response));
                                    }
                                } catch (IOException e) {
                                    e.printStackTrace();
                                } finally {
                                    req.release();
                                }
                            }
                        }});*/
            }};
    }

    private DefaultFullHttpRequest buildFullRequest(final byte[] bytes) {
        final ByteBuf content = Unpooled.buffer(0);
        content.writeBytes(bytes);
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", content);
        HttpUtil.setContentLength(request, content.readableBytes());
        return request;
    }
    
    /* // TODO using initiator
    @Test
    public void testHttpHappyPathOnce() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final HttpServerBuilder server = new DefaultHttpServerBuilder(
                new AbstractBootstrapCreator(
                new DefaultEventLoopGroup(1), new DefaultEventLoopGroup()) {
            @Override
            protected void initializeBootstrap(final ServerBootstrap bootstrap) {
                bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
                bootstrap.channel(LocalServerChannel.class);
            }});
        
        final Subscription testServer = 
                server.defineServer(new LocalAddress(testAddr),
                Feature.ENABLE_LOGGING,
                Feature.ENABLE_COMPRESSOR)
            .subscribe(echoReactor(null));
        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator());
        try {
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress(testAddr), 
                    Observable.just(buildFullRequest(CONTENT)),
                    Feature.ENABLE_LOGGING)
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, CONTENT));
        } finally {
            client.close();
            testServer.unsubscribe();
            server.close();
        }
    }

    @Test
    public void testHttpHappyPathTwice() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final HttpServerBuilder server = new DefaultHttpServerBuilder(
                new AbstractBootstrapCreator(
                new DefaultEventLoopGroup(1), new DefaultEventLoopGroup()) {
            @Override
            protected void initializeBootstrap(final ServerBootstrap bootstrap) {
                bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
                bootstrap.channel(LocalServerChannel.class);
            }});
        final AtomicReference<Object> transportRef = new AtomicReference<Object>();
        
        final Subscription testServer = 
                server.defineServer(new LocalAddress(testAddr),
                Feature.ENABLE_LOGGING,
                Feature.ENABLE_COMPRESSOR)
            .subscribe(echoReactor(transportRef));
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(), pool);
        try {
        
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress(testAddr), 
                    Observable.just(buildFullRequest(CONTENT)),
                    Feature.ENABLE_LOGGING)
                .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, CONTENT));
            
            final Object channel1 = transportRef.get();
            
            unsubscribed.await();
            //  await for 1 second
            pool.awaitRecycleChannels(1);
            
            //  TODO
            //  TOBE fix, client maybe not reused, so server channel not reused, 
            //  so ensure client channel will be reused
            final Iterator<HttpObject> itr2 = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(buildFullRequest(CONTENT)),
                        Feature.ENABLE_LOGGING)
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes2 = RxNettys.httpObjectsAsBytes(itr2);
                
                assertTrue(Arrays.equals(bytes2, CONTENT));
                
                final Object channel2 = transportRef.get();
                assertTrue(channel1 == channel2);
                
        } finally {
            client.close();
            testServer.unsubscribe();
            server.close();
        }
    }
    */

    @Test
    public void testTradeReadControl() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final HttpServerBuilder server = new DefaultHttpServerBuilder(
                new AbstractBootstrapCreator(
                new DefaultEventLoopGroup(1), new DefaultEventLoopGroup()) {
            @Override
            protected void initializeBootstrap(final ServerBootstrap bootstrap) {
                bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
                bootstrap.channel(LocalServerChannel.class);
            }});

        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(2);
        
        final Subscription testServer = 
                server.defineServer(new LocalAddress(testAddr),
                Feature.ENABLE_LOGGING,
                Feature.ENABLE_COMPRESSOR)
            .subscribe(new Action1<HttpTrade>() {
                @Override
                public void call(final HttpTrade trade) {
                    LOG.debug("on trade {}", trade);
                    try {
                        trades.put(trade);
                        LOG.debug("after offer trade {}", trade);
                    } catch (InterruptedException e) {
                        LOG.warn("exception when put trade, detail: {}", ExceptionUtils.exception2detail(e));
                    }
                }});
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(), pool);
        try ( final HttpInitiator initiator = 
                client.initiator().remoteAddress(new LocalAddress(testAddr)).build()
                .toBlocking().single()) {
            final FullHttpRequest reqToSend1 = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
            initiator.defineInteraction(Observable.just(reqToSend1)).subscribe();
            final HttpTrade trade1 = trades.take();
            // trade receive all inbound msg
//            trade1.obsrequest().toCompletable().await();
            
            final FullHttpRequest reqReceived1 = trade1.inbound().compose(RxNettys.message2fullreq(trade1))
                    .toBlocking().single().unwrap();
            assertEquals(reqToSend1, reqReceived1);
            
            final Channel channel = (Channel)initiator.transport();
            final FullHttpRequest reqToSend2 = 
                    new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, 
                    HttpMethod.GET, "/2nd");
            
            channel.writeAndFlush(reqToSend2).sync();
            
            assertTrue(null == trades.poll(1L, TimeUnit.SECONDS));
            
            // after send other interaction req, then first trade send response
            final FullHttpResponse responseToSend1 = new DefaultFullHttpResponse(HTTP_1_1, OK, 
                    Unpooled.wrappedBuffer(CONTENT));
            
            trade1.outbound(Observable.<HttpObject>just(responseToSend1));
            
//            initiator.inbound().message().toCompletable().await();
//            final FullHttpResponse resp = initiator.inbound().messageHolder().
//                    httpMessageBuilder(RxNettys.BUILD_FULL_RESPONSE).call();
//            assertEquals(responseToSend1, resp);
            final HttpTrade trade2 = trades.take();
            
            //  receive all inbound msg
//            trade2.obsrequest().toCompletable().await();
            
            final FullHttpRequest reqReceived2 = trade2.inbound().compose(RxNettys.message2fullreq(trade2))
                    .toBlocking().single().unwrap();
            assertEquals(reqToSend2, reqReceived2);
        } finally {
            client.close();
            testServer.unsubscribe();
            server.close();
        }
    }
}
