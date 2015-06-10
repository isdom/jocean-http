package org.jocean.http.client.impl;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;

import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import javax.net.ssl.SSLException;

import static org.jocean.http.Feature.ENABLE_LOGGING;
import org.jocean.http.Feature.ENABLE_SSL;
import org.jocean.http.server.HttpTestServer;
import org.jocean.http.server.HttpTestServerHandler;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.rx.OnNextSensor;
import org.jocean.idiom.rx.TestSubscription;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscription;
import rx.observers.TestSubscriber;

public class DefaultHttpClientTestCase {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpClientTestCase.class);

    final static SslContext sslCtx;
    static {
        sslCtx = initSslCtx();
    }

    private static SslContext initSslCtx() {
        try {
            return SslContext.newClientContext(InsecureTrustManagerFactory.INSTANCE);
        } catch (SSLException e) {
            return null;
        }
    }
    
    private HttpTestServer createTestServerWithDefaultHandler(
            final boolean enableSSL, 
            final String acceptId) 
            throws Exception {
        return new HttpTestServer(
                enableSSL, 
                new LocalAddress(acceptId), 
                new LocalEventLoopGroup(1), 
                new LocalEventLoopGroup(),
                LocalServerChannel.class,
                HttpTestServer.DEFAULT_NEW_HANDLER);
    }

    private HttpTestServer createTestServerWith(
            final boolean enableSSL, 
            final String acceptId,
            final Callable<ChannelInboundHandler> newHandler) 
            throws Exception {
        return new HttpTestServer(
                enableSSL, 
                new LocalAddress(acceptId), 
                new LocalEventLoopGroup(1), 
                new LocalEventLoopGroup(),
                LocalServerChannel.class,
                newHandler);
    }
    
    //  Happy Path
    @Test
    public void testHttpHappyPathOnce() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");
        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(), ENABLE_LOGGING);
        try {
        
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                .compose(RxNettys.objects2httpobjs())
                .map(RxNettys.<HttpObject>retainMap())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    public void testHttpHappyPathOnceAndCheckRefCount() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");

        final ByteBuf content = Unpooled.buffer(0);
        content.writeBytes("test content".getBytes("UTF-8"));
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", content);
        
        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(), 
                ENABLE_LOGGING);
        try {
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.just(request))
                .compose(RxNettys.objects2httpobjs())
                .map(RxNettys.<HttpObject>retainMap())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            ReferenceCountUtil.release(request);
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
        } finally {
            client.close();
            server.stop();
        }
        
        assertEquals(0, request.refCnt());
    }
    
    @Test
    public void testHttpsHappyPathOnce() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(true, "test");

        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(), 
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        try {
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                .compose(RxNettys.objects2httpobjs())
                .map(RxNettys.<HttpObject>retainMap())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
        } finally {
            client.close();
            server.stop();
        }
    }
    
    @Test
    public void testHttpHappyPathKeepAliveReuseConnection() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");

        final TestChannelCreator creator = new TestChannelCreator();
    
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        try {
            // first 
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainMap())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                pool.awaitRecycleChannels();
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose();
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainMap())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose();
        } finally {
            client.close();
            server.stop();
        }
    }
    
    @Test
    public void testHttpsHappyPathKeepAliveReuseConnection() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(true, "test");

        final TestChannelCreator creator = new TestChannelCreator();
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        
        try {
            // first 
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainMap())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                pool.awaitRecycleChannels();
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose();
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainMap())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose();
        } finally {
            client.close();
            server.stop();
        }
    }
    
    @Test
    public void testHttpOnErrorBeforeSend1stAnd2ndHappyPathKeepAliveReuseConnection() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");

        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting);
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        
        try {
            // first 
            {
                final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
                try {
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.<HttpObject>error(new RuntimeException("test error")))
                    .compose(RxNettys.objects2httpobjs())
                    .subscribe(testSubscriber);
                    // await for unsubscribed
                    new TestSubscription() {{
                        testSubscriber.add(this);
                        pauseConnecting.countDown();
                    }}.awaitUnsubscribed();
                    
                    pool.awaitRecycleChannels();
                } finally {
                    assertEquals(1, testSubscriber.getOnErrorEvents().size());
                    assertEquals(RuntimeException.class, 
                            testSubscriber.getOnErrorEvents().get(0).getClass());
                    assertEquals(0, testSubscriber.getOnCompletedEvents().size());
                    assertEquals(0, testSubscriber.getOnNextEvents().size());
                }
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose();
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainMap())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose();
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    public void testHttpSendingError1stAnd2ndHappyPathNotReuseConnection() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");

        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting)
            .setWriteException(new RuntimeException("write error"));
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        
        try {
            // first 
            {
                final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
                try {
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                    .compose(RxNettys.objects2httpobjs())
                    .subscribe(testSubscriber);
                    // await for unsubscribed
                    new TestSubscription() {{
                        testSubscriber.add(this);
                        pauseConnecting.countDown();
                    }}.awaitUnsubscribed();
                    pool.awaitRecycleChannels();
                } finally {
                    assertEquals(1, testSubscriber.getOnErrorEvents().size());
                    assertEquals(RuntimeException.class, 
                            testSubscriber.getOnErrorEvents().get(0).getClass());
                    assertEquals(0, testSubscriber.getOnCompletedEvents().size());
                    assertEquals(0, testSubscriber.getOnNextEvents().size());
                }
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
            //  reset write exception
            creator.setWriteException(null)
                .setPauseConnecting(null);
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainMap())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(2, creator.getChannels().size());
            creator.getChannels().get(1).assertNotClose();
        } finally {
            client.close();
            server.stop();
        }
    }
    
    //  all kinds of exception
    //  Not connected
    @Test
    public void testHttpForNotConnected() throws Exception {
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = 
                new TestChannelCreator().setPauseConnecting(pauseConnecting);
        final DefaultHttpClient client = new DefaultHttpClient(creator, ENABLE_LOGGING);
        //    NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            client.defineInteraction(new LocalAddress("test"), 
                Observable.<HttpObject>just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
                .doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .subscribe(testSubscriber);
            // await for unsubscribed
            new TestSubscription() {{
                testSubscriber.add(this);
                LOG.debug("try to start connect channel.");
                pauseConnecting.countDown();
            }}.awaitUnsubscribed();
            
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            nextSensor.assertNotCalled();
        }
    }

    @Test
    public void testHttpsNotConnected() throws Exception {
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting);
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        //  NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            client.defineInteraction(new LocalAddress("test"), 
                Observable.<HttpObject>just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
                .doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .subscribe(testSubscriber);
            // await for unsubscribed
            new TestSubscription() {{
                testSubscriber.add(this);
                LOG.debug("try to start connect channel.");
                pauseConnecting.countDown();
            }}.awaitUnsubscribed();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            nextSensor.assertNotCalled();
        }
    }

    @Test
    public void testHttpsNotShakehand() throws Exception {
        // http server
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting);
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                new ENABLE_SSL(sslCtx));
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
                .doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .subscribe(testSubscriber);
            new TestSubscription() {{
                testSubscriber.add(this);
                pauseConnecting.countDown();
            }}.awaitUnsubscribed();
            testSubscriber.awaitTerminalEvent();
            pool.awaitRecycleChannels();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
        } finally {
            client.close();
            server.stop();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(NotSslRecordException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            nextSensor.assertNotCalled();
        }
    }
    
    @Test
    public void testHttpEmitExceptionWhenConnecting() throws Exception {
        final String errorMsg = "connecting failure";
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting)
            .setConnectException(new RuntimeException(errorMsg));
        
        final DefaultHttpClient client = new DefaultHttpClient(creator);
        //    NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
                .doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .subscribe(testSubscriber);
            new TestSubscription() {{
                testSubscriber.add(this);
                pauseConnecting.countDown();
            }}.awaitUnsubscribed();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            assertFalse(creator.getChannels().get(0).isActive());
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(errorMsg, 
                    testSubscriber.getOnErrorEvents().get(0).getMessage());
            //  channel not connected, so no message send
            nextSensor.assertNotCalled();
        }
    }
    
    @Test
    public void testHttpsEmitExceptionWhenConnecting() throws Exception {
        final String errorMsg = "connecting failure";
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting)
            .setConnectException(new RuntimeException(errorMsg));
        
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                new ENABLE_SSL(sslCtx));
        //    NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
                .doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .subscribe(testSubscriber);
            new TestSubscription() {{
                testSubscriber.add(this);
                pauseConnecting.countDown();
            }}.awaitUnsubscribed();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            //  TODO, this case failed!!
            //  remark by isdom 2015.04.25
//            creator.getChannels().get(0).assertClosed();
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(errorMsg, 
                    testSubscriber.getOnErrorEvents().get(0).getMessage());
            //  channel not connected, so no message send
            nextSensor.assertNotCalled();
        }
    }
    
    //  connected but meet error
    @Test
    public void testHttpDisconnectFromServerAfterConnected() throws Exception {
        final HttpTestServer server = createTestServerWith(false, "test",
                new Callable<ChannelInboundHandler> () {
                    @Override
                    public ChannelInboundHandler call() throws Exception {
                        return new HttpTestServerHandler() {
                            @Override
                            protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                                    throws Exception {
                                if (msg instanceof HttpRequest) {
                                    ctx.close();
                                }
                            }
                        };
                    }});
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting);
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
                .doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .subscribe(testSubscriber);
            new TestSubscription() {{
                testSubscriber.add(this);
                pauseConnecting.countDown();
            }}.awaitUnsubscribed();
            testSubscriber.awaitTerminalEvent();
            pool.awaitRecycleChannels();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
        } finally {
            client.close();
            server.stop();
            testSubscriber.assertTerminalEvent();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  channel connected, so message has been send
            nextSensor.assertCalled();
        }
    }
    
    @Test
    public void testHttpsDisconnectFromServerAfterConnected() throws Exception {
        final HttpTestServer server = createTestServerWith(true, "test",
                new Callable<ChannelInboundHandler> () {
                    @Override
                    public ChannelInboundHandler call() throws Exception {
                        return new HttpTestServerHandler() {
                            @Override
                            protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                                    throws Exception {
                                if (msg instanceof HttpRequest) {
                                    ctx.close();
                                }
                            }
                        };
                    }});
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting);
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
                .doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .subscribe(testSubscriber);
            new TestSubscription() {{
                testSubscriber.add(this);
                pauseConnecting.countDown();
            }}.awaitUnsubscribed();
            testSubscriber.awaitTerminalEvent();
            pool.awaitRecycleChannels();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
        } finally {
            client.close();
            server.stop();
            testSubscriber.assertTerminalEvent();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  channel connected, so message has been send
            nextSensor.assertCalled();
        }
    }
    
    @Test
    public void testHttpClientCanceledAfterConnected() throws Exception {
        final CountDownLatch serverRecvd = new CountDownLatch(1);
        final HttpTestServer server = createTestServerWith(false, "test",
                new Callable<ChannelInboundHandler> () {
                    @Override
                    public ChannelInboundHandler call() throws Exception {
                        return new HttpTestServerHandler() {
                            @Override
                            protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                                    throws Exception {
                                if (msg instanceof HttpRequest) {
                                    LOG.debug("recv request {}, and do nothing.", msg);
                                    serverRecvd.countDown();
                                    //  never send response
                                }
                            }
                        };
                    }});
        
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator);
        
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final Subscription subscription = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.<HttpObject>just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
                    .doOnNext(nextSensor))
                .compose(RxNettys.objects2httpobjs())
                .subscribe(testSubscriber);
            
            serverRecvd.await();
            
            //  server !NOT! send back
            subscription.unsubscribe();
            
            // test if close method has been called.
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
        } finally {
            client.close();
            server.stop();
            testSubscriber.assertNoErrors();
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  channel connected, so message has been send
            nextSensor.assertCalled();
        }
    }

    @Test
    public void testHttpsClientCanceledAfterConnected() throws Exception {
        final CountDownLatch serverRecvd = new CountDownLatch(1);
        final HttpTestServer server = createTestServerWith(true, "test",
                new Callable<ChannelInboundHandler> () {
                    @Override
                    public ChannelInboundHandler call() throws Exception {
                        return new HttpTestServerHandler() {
                            @Override
                            protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                                    throws Exception {
                                if (msg instanceof HttpRequest) {
                                    LOG.debug("recv request {}, and do nothing.", msg);
                                    serverRecvd.countDown();
                                    //  never send response
                                }
                            }
                        };
                    }});
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final Subscription subscription = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.<HttpObject>just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
                    .doOnNext(nextSensor))
                .compose(RxNettys.objects2httpobjs())
                .subscribe(testSubscriber);
            
            serverRecvd.await();
            
//            assertEquals(1, client.getActiveChannelCount());
            //  server !NOT! send back
            subscription.unsubscribe();
            pool.awaitRecycleChannels();
            
            // test if close method has been called.
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
        } finally {
            // 注意: 一个 try-with-resources 语句可以像普通的 try 语句那样有 catch 和 finally 块。
            //  在try-with-resources 语句中, 任意的 catch 或者 finally 块都是在声明的资源被关闭以后才运行。
            client.close();
            server.stop();
//            assertEquals(0, client.getActiveChannelCount());
            testSubscriber.assertNoErrors();
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  channel connected, so message has been send
            nextSensor.assertCalled();
        }
    }
    
    @Test
    public void testHttpRequestEmitErrorAfterConnected() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting);
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING);
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>error(new RuntimeException("test error")))
            .compose(RxNettys.objects2httpobjs())
            .subscribe(testSubscriber);
            new TestSubscription() {{
                testSubscriber.add(this);
                pauseConnecting.countDown();
            }}.awaitUnsubscribed();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose();
        } finally {
            client.close();
            server.stop();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
        }
    }

    @Test
    public void testHttpsRequestEmitErrorAfterConnected() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(true, "test");
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting);
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>error(new RuntimeException("test error")))
            .compose(RxNettys.objects2httpobjs())
            .subscribe(testSubscriber);
            new TestSubscription() {{
                testSubscriber.add(this);
                pauseConnecting.countDown();
            }}.awaitUnsubscribed();
            testSubscriber.awaitTerminalEvent();
            pool.awaitRecycleChannels();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose();
        } finally {
            client.close();
            server.stop();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
        }
    }
    
    @Test
    public void testHttpRequestEmitErrorAfterConnectedAndReuse2nd() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting);
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            //  first
            {
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.<HttpObject>error(new RuntimeException("test error")))
                .compose(RxNettys.objects2httpobjs())
                .subscribe(testSubscriber);
                new TestSubscription() {{
                    testSubscriber.add(this);
                    pauseConnecting.countDown();
                }}.awaitUnsubscribed();
                testSubscriber.awaitTerminalEvent();
                pool.awaitRecycleChannels();
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainMap())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose();
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    public void testHttpsRequestEmitErrorAfterConnectedAndReuse2nd() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(true, "test");
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting);
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            //  first
            {
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.<HttpObject>error(new RuntimeException("test error")))
                .compose(RxNettys.objects2httpobjs())
                .subscribe(testSubscriber);
                new TestSubscription() {{
                    testSubscriber.add(this);
                    pauseConnecting.countDown();
                }}.awaitUnsubscribed();
                testSubscriber.awaitTerminalEvent();
                pool.awaitRecycleChannels();
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainMap())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose();
        } finally {
            client.close();
            server.stop();
        }
    }
    
    @Test
    public void testHttpClientWriteAndFlushExceptionAfterConnected() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting)
            .setWriteException(new RuntimeException("doWrite Error for test"));
        
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING);
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
                .doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .subscribe(testSubscriber);
            new TestSubscription() {{
                testSubscriber.add(this);
                pauseConnecting.countDown();
            }}.awaitUnsubscribed();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
        } finally {
            client.close();
            server.stop();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            //  no response received
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  message has been write to send queue
            nextSensor.assertCalled();
        }
    }

    @Test
    public void testHttpsClientWriteAndFlushExceptionAfterConnected() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(true, "test");
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting)
            .setWriteException(new RuntimeException("doWrite Error for test"));
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
                .doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .subscribe(testSubscriber);
            new TestSubscription() {{
                testSubscriber.add(this);
                pauseConnecting.countDown();
            }}.awaitUnsubscribed();
            testSubscriber.awaitTerminalEvent();
            pool.awaitRecycleChannels();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
        } finally {
            client.close();
            server.stop();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(SSLException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            //  no response received
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  message has been write to send queue
            nextSensor.assertNotCalled();
        }
    }
    
    @Test
    public void testHttpClientWriteAndFlushExceptionAfterConnectedAndNewConnection2nd() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting)
            .setWriteException(new RuntimeException("doWrite Error for test"));
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
            try {
            {
                final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
                final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
                // first
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.<HttpObject>just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
                    .doOnNext(nextSensor))
                .compose(RxNettys.objects2httpobjs())
                .subscribe(testSubscriber);
                new TestSubscription() {{
                    testSubscriber.add(this);
                    pauseConnecting.countDown();
                }}.awaitUnsubscribed();
                testSubscriber.awaitTerminalEvent();
                pool.awaitRecycleChannels();
                assertEquals(1, creator.getChannels().size());
                creator.getChannels().get(0).assertClosed();
                assertEquals(1, testSubscriber.getOnErrorEvents().size());
                assertEquals(RuntimeException.class, 
                        testSubscriber.getOnErrorEvents().get(0).getClass());
                assertEquals(0, testSubscriber.getOnCompletedEvents().size());
                //  no response received
                assertEquals(0, testSubscriber.getOnNextEvents().size());
                //  message has been write to send queue
                nextSensor.assertCalled();
            }
            // reset creator
            creator.setPauseConnecting(null).setWriteException(null);
            {
                // second
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainMap())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                assertEquals(2, creator.getChannels().size());
                creator.getChannels().get(1).assertNotClose();
            }
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    public void testHttpsClientWriteAndFlushExceptionAfterConnectedAndNewConnection2nd() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(true, "test");
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting)
            .setWriteException(new RuntimeException("doWrite Error for test"));
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        try {
            {
                final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
                final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.<HttpObject>just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
                    .doOnNext(nextSensor))
                .compose(RxNettys.objects2httpobjs())
                .subscribe(testSubscriber);
                new TestSubscription() {{
                    testSubscriber.add(this);
                    pauseConnecting.countDown();
                }}.awaitUnsubscribed();
                testSubscriber.awaitTerminalEvent();
                pool.awaitRecycleChannels();
                assertEquals(1, creator.getChannels().size());
                creator.getChannels().get(0).assertClosed();
                assertEquals(1, testSubscriber.getOnErrorEvents().size());
                assertEquals(SSLException.class, 
                        testSubscriber.getOnErrorEvents().get(0).getClass());
                assertEquals(0, testSubscriber.getOnCompletedEvents().size());
                //  no response received
                assertEquals(0, testSubscriber.getOnNextEvents().size());
                //  message has been write to send queue
                nextSensor.assertNotCalled();
            }
            // reset creator
            creator.setPauseConnecting(null).setWriteException(null);
            {
                // second
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainMap())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                assertEquals(2, creator.getChannels().size());
                creator.getChannels().get(1).assertNotClose();
            }
        } finally {
            client.close();
            server.stop();
        }
    }
    
    @Test
    public void testHttp10ConnectionCloseHappyPath() throws Exception {
        final HttpTestServer server = createTestServerWith(false, "test",
                new Callable<ChannelInboundHandler> () {
            @Override
            public ChannelInboundHandler call() throws Exception {
                return new HttpTestServerHandler() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                            throws Exception {
                        if (msg instanceof HttpRequest) {
                            //  for HTTP 1.0 Connection: Close response behavior
                            final FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_0, OK, 
                                    Unpooled.wrappedBuffer(HttpTestServer.CONTENT));
                            response.headers().set(CONTENT_TYPE, "text/plain");
                            //  missing Content-Length
//                            response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                            response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
                            ctx.write(response).addListener(ChannelFutureListener.CLOSE);
                        }
                    }
                };
            }});

        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING);
        try {
            final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
            request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
            
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.just(request))
                .compose(RxNettys.objects2httpobjs())
                .map(RxNettys.<HttpObject>retainMap())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).awaitClosed();
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    public void testHttp10ConnectionCloseBadCaseMissingPartContent() throws Exception {
        final HttpTestServer server = createTestServerWith(false, "test",
                new Callable<ChannelInboundHandler> () {
            @Override
            public ChannelInboundHandler call() throws Exception {
                return new HttpTestServerHandler() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                            throws Exception {
                        if (msg instanceof HttpRequest) {
                            //  for HTTP 1.0 Connection: Close response behavior
                            final FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_0, OK, 
                                    Unpooled.wrappedBuffer(HttpTestServer.CONTENT));
                            response.headers().set(CONTENT_TYPE, "text/plain");
                            //  BAD Content-Length, actual length + 1
                            response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, 
                                    response.content().readableBytes() + 1);
                            response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
                            ctx.write(response).addListener(ChannelFutureListener.CLOSE);
                        }
                    }
                };
            }});
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting);
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING);
        final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
        
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>just(request))
            .compose(RxNettys.objects2httpobjs())
            .subscribe(testSubscriber);
            new TestSubscription() {{
                testSubscriber.add(this);
                pauseConnecting.countDown();
            }}.awaitUnsubscribed();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
        } finally {
            client.close();
            server.stop();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertTrue(testSubscriber.getOnNextEvents().size()>=1);
        }
    }
    
    @Test
    public void testHttps10ConnectionCloseHappyPath() throws Exception {
        final HttpTestServer server = createTestServerWith(true, "test",
                new Callable<ChannelInboundHandler> () {
            @Override
            public ChannelInboundHandler call() throws Exception {
                return new HttpTestServerHandler() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                            throws Exception {
                        if (msg instanceof HttpRequest) {
                            //  for HTTP 1.0 Connection: Close response behavior
                            final FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_0, OK, 
                                    Unpooled.wrappedBuffer(HttpTestServer.CONTENT));
                            response.headers().set(CONTENT_TYPE, "text/plain");
                            //  missing Content-Length
//                            response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                            response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
                            ctx.write(response).addListener(ChannelFutureListener.CLOSE);
                        }
                    }
                };
            }});

        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        try {
            final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
            request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
            
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.just(request))
                .compose(RxNettys.objects2httpobjs())
                .map(RxNettys.<HttpObject>retainMap())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).awaitClosed();
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    public void testHttps10ConnectionCloseBadCaseMissingPartContent() throws Exception {
        final HttpTestServer server = createTestServerWith(true, "test",
                new Callable<ChannelInboundHandler> () {
            @Override
            public ChannelInboundHandler call() throws Exception {
                return new HttpTestServerHandler() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                            throws Exception {
                        if (msg instanceof HttpRequest) {
                            //  for HTTP 1.0 Connection: Close response behavior
                            final FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_0, OK, 
                                    Unpooled.wrappedBuffer(HttpTestServer.CONTENT));
                            response.headers().set(CONTENT_TYPE, "text/plain");
                            //  BAD Content-Length, actual length + 1
                            response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, 
                                    response.content().readableBytes() + 1);
                            response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
                            ctx.write(response).addListener(ChannelFutureListener.CLOSE);
                        }
                    }
                };
            }});
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting);
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
        
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>just(request))
            .compose(RxNettys.objects2httpobjs())
            .subscribe(testSubscriber);
            new TestSubscription() {{
                testSubscriber.add(this);
                pauseConnecting.countDown();
            }}.awaitUnsubscribed();
            testSubscriber.awaitTerminalEvent();
            pool.awaitRecycleChannels();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
        } finally {
            client.close();
            server.stop();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertTrue(testSubscriber.getOnNextEvents().size()>=1);
        }
    }
    
    /*
    @Test
    public void testHttp11KeeypAliveBadCaseMissingPartContent() throws Exception {
        final HttpTestServer server = createTestServerWith(false, "test",
                new Callable<ChannelInboundHandler> () {
            @Override
            public ChannelInboundHandler call() throws Exception {
                return new HttpTestServerHandler() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                            throws Exception {
                        if (msg instanceof HttpRequest) {
                            //  for HTTP 1.0 Connection: Close response behavior
                            final FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, OK, 
                                    Unpooled.wrappedBuffer(HttpTestServer.CONTENT));
                            response.headers().set(CONTENT_TYPE, "text/plain");
                            //  BAD Content-Length, actual length + 1
                            response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, 
                                    response.content().readableBytes() + 1);
                            response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
                            ctx.write(response);
                        }
                    }
                };
            }});
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting);
        final DefaultHttpClient client = new DefaultHttpClient(creator);
        final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            client.sendRequest(
                new LocalAddress("test"), 
                Observable.<HttpObject>just(request),
                Feature.DisableCompress)
            .subscribe(testSubscriber);
            new TestSubscription() {{
                testSubscriber.add(this);
                pauseConnecting.countDown();
                //  wait for ever // TO fix
            }}.awaitUnsubscribed();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
        } finally {
            client.close();
            server.stop();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertTrue(testSubscriber.getOnNextEvents().size()>=1);
        }
    }
    */
    
    // TODO, 增加 transfer request 时, 调用 response subscriber.unsubscribe 后，write future是否会被正确取消。
}
