package org.jocean.http.client.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import javax.net.ssl.SSLException;

import org.jocean.http.Feature;
import org.jocean.http.Feature.ENABLE_SSL;
import org.jocean.http.TestHttpUtil;
import org.jocean.http.TransportException;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.client.HttpClient.InitiatorBuilder;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.TerminateAware;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PoolArenaMetric;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.local.LocalAddress;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action0;
import rx.observables.ConnectableObservable;
import rx.observers.TestSubscriber;

public class DefaultHttpClientTestCase {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpClientTestCase.class);

    public static final byte[] CONTENT = { 'H', 'e', 'l', 'l', 'o', ' ', 'W', 'o', 'r', 'l', 'd' };
    
    private static SslContext initSslCtx4Client() {
        try {
            return SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
        } catch (SSLException e) {
            return null;
        }
    }
    
    private static Feature enableSSL4Client() {
        return new ENABLE_SSL(initSslCtx4Client());
    }
    
    private static Feature enableSSL4ServerWithSelfSigned()
            throws CertificateException, SSLException {
        final SelfSignedCertificate ssc = new SelfSignedCertificate();
        final SslContext sslCtx4Server = 
                SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        return new ENABLE_SSL(sslCtx4Server);
    }
    
    private DefaultFullHttpRequest fullHttpRequest() {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    }

    private static void configDefaultAllocator() {
        System.getProperties().setProperty("io.netty.allocator.tinyCacheSize", "0");
        System.getProperties().setProperty("io.netty.allocator.smallCacheSize", "0");
        System.getProperties().setProperty("io.netty.allocator.normalCacheSize", "0");
        System.getProperties().setProperty("io.netty.allocator.type", "pooled");
        System.getProperties().setProperty("io.netty.noPreferDirect", "true");
    }
    
    private static PooledByteBufAllocator defaultAllocator() {
        return PooledByteBufAllocator.DEFAULT;
    }

    private static int allActiveAllocationsCount(final Iterator<PoolArenaMetric> iter) {
        int total = 0;
        while (iter.hasNext()) {
            total += iter.next().numActiveAllocations();
        }
        return total;
    }
    
    private static int allHeapActiveAllocationsCount(final PooledByteBufAllocator allocator) {
        return allActiveAllocationsCount(allocator.metric().heapArenas().iterator());
    }
    
    private static int allDirectActiveAllocationsCount(final PooledByteBufAllocator allocator) {
        return allActiveAllocationsCount(allocator.metric().directArenas().iterator());
    }
    
    private static int allActiveAllocationsCount(final PooledByteBufAllocator allocator) {
        return allHeapActiveAllocationsCount(allocator) 
                + allDirectActiveAllocationsCount(allocator);
    }

    private static byte[] dumpResponseContentAsBytes(final HttpMessageHolder holder) throws IOException {
        final FullHttpResponse resp = 
            holder.fullOf(RxNettys.BUILD_FULL_RESPONSE)
            .call();
        try {
            return Nettys.dumpByteBufAsBytes(resp.content());
        } finally {
            if (null!=resp) {
                resp.release();
            }
        }
    }
    
    static public interface Interaction {
        public void interact(
                final HttpInitiator initiator,
                final Observable<HttpObject> response, 
                final HttpMessageHolder holder) throws Exception;
    }
    
    private static HttpInitiator startInteraction(
            final InitiatorBuilder builder,
            final Observable<? extends Object> request, 
            final Interaction interaction ) throws Exception {
        try ( final HttpInitiator initiator = builder.build().toBlocking().single()) {
            final HttpMessageHolder holder = new HttpMessageHolder();
            holder.setMaxBlockSize(-1);
            initiator.doOnTerminate(holder.closer());
            
            interaction.interact(initiator,
                initiator.defineInteraction(request)
                    .compose(holder.<HttpObject>assembleAndHold()), 
                holder);
            return initiator;
        }
    }

    @Test(timeout=5000)
    public void testInitiatorMultiCalldefineInteraction() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                Feature.ENABLE_LOGGING);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                Feature.ENABLE_LOGGING);
        try ( final HttpInitiator initiator = client.initiator().remoteAddress(new LocalAddress(addr))
                .build().toBlocking().single()) {
            
            @SuppressWarnings({ "unchecked", "unused" })
            final Observable<HttpObject> resp1 = 
                    (Observable<HttpObject>)initiator.defineInteraction(Observable.just(fullHttpRequest()));
            
            @SuppressWarnings({ "unchecked", "unused" })
            final Observable<HttpObject> resp2 = 
                    (Observable<HttpObject>)initiator.defineInteraction(Observable.just(fullHttpRequest()));
            assertEquals(0, allActiveAllocationsCount(allocator));
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorMultiCalldefineInteractionAndSubscribe() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                Feature.ENABLE_LOGGING);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                Feature.ENABLE_LOGGING);
        try ( final HttpInitiator initiator = client.initiator().remoteAddress(new LocalAddress(addr))
                .build().toBlocking().single()) {
            
            @SuppressWarnings("unchecked")
            final Observable<HttpObject> resp1 = 
                    (Observable<HttpObject>)initiator.defineInteraction(Observable.just(fullHttpRequest()));
            
            @SuppressWarnings("unchecked")
            final Observable<HttpObject> resp2 = 
                    (Observable<HttpObject>)initiator.defineInteraction(Observable.just(fullHttpRequest()));
            resp1.subscribe();

            final TestSubscriber<HttpObject> subscriber = new TestSubscriber<>();
            resp2.subscribe(subscriber);
            
            subscriber.awaitTerminalEvent();
            subscriber.assertError(RuntimeException.class);
            
//            assertEquals(0, allActiveAllocationsCount(allocator));
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionSuccessAsHttp() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                Feature.ENABLE_LOGGING);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                Feature.ENABLE_LOGGING);
        try {
            startInteraction(client.initiator().remoteAddress(new LocalAddress(addr)), 
                Observable.just(fullHttpRequest()),
                new Interaction() {
                    @Override
                    public void interact(
                            final HttpInitiator initiator,
                            final Observable<HttpObject> response, 
                            final HttpMessageHolder holder) throws Exception {
                        final Observable<HttpObject> cached = response.cache();
                        
                        cached.subscribe();
                        // server side recv req
                        final HttpTrade trade = trades.take();
                        
                        // recv all request
                        trade.inbound().message().toCompletable().await();
                        assertEquals(0, allActiveAllocationsCount(allocator));
                        
                        final ByteBuf svrRespContent = allocator.buffer(CONTENT.length).writeBytes(CONTENT);
                        assertEquals(1, allActiveAllocationsCount(allocator));
                        
                        // send back resp
                        trade.outbound().message(TestHttpUtil.buildByteBufResponse("text/plain", svrRespContent));
                        
                        // wait for recv all resp at client side
                        cached.toCompletable().await();
                        
                        svrRespContent.release();
                        
                        // holder create clientside resp's content
                        assertEquals(1, allActiveAllocationsCount(allocator));
                        
                        assertTrue(Arrays.equals(dumpResponseContentAsBytes(holder), CONTENT));
                    }
                });
        } finally {
            assertEquals(0, allActiveAllocationsCount(allocator));
            client.close();
            server.unsubscribe();
        }
    }

    @Test(timeout=5000)
    public void testInitiatorInteractionSuccessAsHttps() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                enableSSL4ServerWithSelfSigned(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                enableSSL4Client(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        try {
            startInteraction(client.initiator().remoteAddress(new LocalAddress(addr)), 
                Observable.just(fullHttpRequest()),
                new Interaction() {
                    @Override
                    public void interact(
                            final HttpInitiator initiator,
                            final Observable<HttpObject> response, 
                            final HttpMessageHolder holder) throws Exception {
                        final Observable<HttpObject> cached = response.cache();
                        // initiator 开始发送 请求
                        cached.subscribe();
                        
                        LOG.debug("before get tarde");
                        // server side recv req
                        final HttpTrade trade = trades.take();
                        LOG.debug("after get tarde");
                        
                        // recv all request
                        trade.inbound().message().toCompletable().await();
                        
                        final ByteBuf svrRespContent = allocator.buffer(CONTENT.length).writeBytes(CONTENT);
                        
                        // send back resp
                        trade.outbound().message(TestHttpUtil.buildByteBufResponse("text/plain", svrRespContent));
                        
                        // wait for recv all resp at client side
                        cached.toCompletable().await();
                        
                        svrRespContent.release();
                        
                        assertTrue(Arrays.equals(dumpResponseContentAsBytes(holder), CONTENT));
                    }
                });
        } finally {
            assertEquals(0, allActiveAllocationsCount(allocator));
            client.close();
            server.unsubscribe();
        }
    }
    
    private static Interaction standardInteraction(
            final PooledByteBufAllocator allocator,
            final BlockingQueue<HttpTrade> trades) {
        return new Interaction() {
            @Override
            public void interact(
                    final HttpInitiator initiator,
                    final Observable<HttpObject> response, 
                    final HttpMessageHolder holder) throws Exception {
                final Observable<HttpObject> cached = response.cache();
                
                cached.subscribe();
                // server side recv req
                final HttpTrade trade = trades.take();
                
                // recv all request
                trade.inbound().message().toCompletable().await();
                
                final ByteBuf svrRespContent = allocator.buffer(CONTENT.length).writeBytes(CONTENT);
                
                // send back resp
                trade.outbound().message(TestHttpUtil.buildByteBufResponse("text/plain", svrRespContent));
                
                // wait for recv all resp at client side
                cached.toCompletable().await();
                
                svrRespContent.release();
                
                assertTrue(Arrays.equals(dumpResponseContentAsBytes(holder), CONTENT));
            }
        };
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionSuccessAsHttpReuseChannel() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                Feature.ENABLE_LOGGING);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                Feature.ENABLE_LOGGING);
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        try {
            final Channel ch1 = (Channel)startInteraction(
                    client.initiator().remoteAddress(new LocalAddress(addr)), 
                    Observable.just(fullHttpRequest()),
                    standardInteraction(allocator, trades)).transport();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
            
            final Channel ch2 = (Channel)startInteraction(
                    client.initiator().remoteAddress(new LocalAddress(addr)), 
                    Observable.just(fullHttpRequest()),
                    standardInteraction(allocator, trades)).transport();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
            
            assertSame(ch1, ch2);
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionSuccessAsHttpsReuseChannel() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                enableSSL4ServerWithSelfSigned(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                enableSSL4Client(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        try {
            final Channel ch1 = (Channel)startInteraction(
                    client.initiator().remoteAddress(new LocalAddress(addr)), 
                    Observable.just(fullHttpRequest()),
                    standardInteraction(allocator, trades)).transport();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
            
            final Channel ch2 = (Channel)startInteraction(
                    client.initiator().remoteAddress(new LocalAddress(addr)), 
                    Observable.just(fullHttpRequest()),
                    standardInteraction(allocator, trades)).transport();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
            
            assertSame(ch1, ch2);
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionNo1NotSendNo2SuccessReuseChannelAsHttp() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                Feature.ENABLE_LOGGING);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                Feature.ENABLE_LOGGING);
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        try {
            final Channel ch1 = (Channel)startInteraction(
                client.initiator().remoteAddress(new LocalAddress(addr)), 
                Observable.<HttpObject>error(new RuntimeException("test error")),
                new Interaction() {
                    @Override
                    public void interact(
                            final HttpInitiator initiator,
                            final Observable<HttpObject> response, 
                            final HttpMessageHolder holder) throws Exception {
                        final TestSubscriber<HttpObject> subscriber = new TestSubscriber<>();
                        response.subscribe(subscriber);
                        
                        subscriber.awaitTerminalEvent();
                        subscriber.assertError(RuntimeException.class);
                        subscriber.assertNoValues();
                    }
                }).transport();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
            
            final Channel ch2 = (Channel)startInteraction(
                    client.initiator().remoteAddress(new LocalAddress(addr)), 
                    Observable.just(fullHttpRequest()),
                    standardInteraction(allocator, trades)).transport();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
            
            assertSame(ch1, ch2);
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionNo1NotSendNo2SuccessReuseChannelAsHttps() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                enableSSL4ServerWithSelfSigned(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                enableSSL4Client(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        try {
            final Channel ch1 = (Channel)startInteraction(
                client.initiator().remoteAddress(new LocalAddress(addr)), 
                Observable.<HttpObject>error(new RuntimeException("test error")),
                new Interaction() {
                    @Override
                    public void interact(
                            final HttpInitiator initiator,
                            final Observable<HttpObject> response, 
                            final HttpMessageHolder holder) throws Exception {
                        final TestSubscriber<HttpObject> subscriber = new TestSubscriber<>();
                        response.subscribe(subscriber);
                        
                        subscriber.awaitTerminalEvent();
                        subscriber.assertError(RuntimeException.class);
                        subscriber.assertNoValues();
                    }
                }).transport();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
            
            final Channel ch2 = (Channel)startInteraction(
                    client.initiator().remoteAddress(new LocalAddress(addr)), 
                    Observable.just(fullHttpRequest()),
                    standardInteraction(allocator, trades)).transport();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
            
            assertSame(ch1, ch2);
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionNotConnectedAsHttp() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final String addr = UUID.randomUUID().toString();
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                Feature.ENABLE_LOGGING);
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        try {
            final TestSubscriber<HttpInitiator> subscriber = new TestSubscriber<>();
            
            client.initiator().remoteAddress(new LocalAddress(addr)).build().subscribe(subscriber);
                        
            subscriber.awaitTerminalEvent();
            subscriber.assertError(ConnectException.class);
            subscriber.assertNoValues();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
        } finally {
            client.close();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionNotConnectedAsHttps() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final String addr = UUID.randomUUID().toString();
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                enableSSL4Client(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        try {
            final TestSubscriber<HttpInitiator> subscriber = new TestSubscriber<>();
            
            client.initiator().remoteAddress(new LocalAddress(addr)).build().subscribe(subscriber);
                        
            subscriber.awaitTerminalEvent();
            subscriber.assertError(ConnectException.class);
            subscriber.assertNoValues();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
        } finally {
            client.close();
        }
    }
    
    @Test(timeout=15000)
    public void testInitiatorInteractionNotShakehandAsHttps() throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final String addr = UUID.randomUUID().toString();
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                Feature.ENABLE_LOGGING);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                enableSSL4Client(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        try {
            final TestSubscriber<HttpInitiator> subscriber = new TestSubscriber<>();
            
            client.initiator().remoteAddress(new LocalAddress(addr)).build().subscribe(subscriber);
                        
            subscriber.awaitTerminalEvent();
            subscriber.assertError(SSLException.class);
            subscriber.assertNoValues();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=5000)
    public void testGetInitiatorUnsubscribedAlreadyAsHttp()
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final String addr = UUID.randomUUID().toString();
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                Feature.ENABLE_LOGGING);
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        try {
            final TestSubscriber<HttpInitiator> subscriber = new TestSubscriber<>();
            
            subscriber.unsubscribe();
            final Subscription subscription = client.initiator().remoteAddress(new LocalAddress(addr))
                    .build().subscribe(subscriber);

            assertTrue(subscription.isUnsubscribed());
            
            subscriber.assertNoTerminalEvent();
            subscriber.assertNoValues();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
        } finally {
            client.close();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionUnsubscribedAlreadyAsHttp() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final String addr = UUID.randomUUID().toString();
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                Feature.ENABLE_LOGGING);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                Feature.ENABLE_LOGGING);
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        try {
            startInteraction(
                client.initiator().remoteAddress(new LocalAddress(addr)), 
                Observable.just(fullHttpRequest()),
                new Interaction() {
                    @Override
                    public void interact(
                            final HttpInitiator initiator,
                            final Observable<HttpObject> response, 
                            final HttpMessageHolder holder) throws Exception {
                        final TestSubscriber<HttpObject> subscriber = new TestSubscriber<>();
                        
                        subscriber.unsubscribe();
                        final Subscription subscription = response.subscribe(subscriber);
                        
                        assertTrue(subscription.isUnsubscribed());
                        
                        subscriber.assertNoTerminalEvent();
                        subscriber.assertNoValues();
                    }
                });
            
            assertEquals(0, allActiveAllocationsCount(allocator));
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionDisconnectFromServerAfterConnectedAsHttp() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                Feature.ENABLE_LOGGING);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                Feature.ENABLE_LOGGING);
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        try {
            startInteraction(
                client.initiator().remoteAddress(new LocalAddress(addr)), 
                Observable.just(fullHttpRequest()),
                new Interaction() {
                    @Override
                    public void interact(
                            final HttpInitiator initiator,
                            final Observable<HttpObject> response, 
                            final HttpMessageHolder holder) throws Exception {
                        final TestSubscriber<HttpObject> subscriber = new TestSubscriber<>();
                        response.subscribe(subscriber);
                        
                        // server side recv req
                        final HttpTrade trade = trades.take();
                        
                        // recv request from client side
                        trade.inbound().message().toCompletable().await();
                        
                        // disconnect
                        trade.close();
                        
                        subscriber.awaitTerminalEvent();
                        subscriber.assertError(TransportException.class);
                        subscriber.assertNotCompleted();
                        subscriber.assertNoValues();
                    }
                });
            
            assertEquals(0, allActiveAllocationsCount(allocator));
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionDisconnectFromServerAfterConnectedAsHttps() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                enableSSL4ServerWithSelfSigned(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                enableSSL4Client(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        try {
            startInteraction(
                client.initiator().remoteAddress(new LocalAddress(addr)), 
                Observable.just(fullHttpRequest()),
                new Interaction() {
                    @Override
                    public void interact(
                            final HttpInitiator initiator,
                            final Observable<HttpObject> response, 
                            final HttpMessageHolder holder) throws Exception {
                        final TestSubscriber<HttpObject> subscriber = new TestSubscriber<>();
                        response.subscribe(subscriber);
                        
                        // server side recv req
                        final HttpTrade trade = trades.take();
                        
                        // recv request from client side
                        trade.inbound().message().toCompletable().await();
                        
                        // disconnect
                        trade.close();
                        
                        subscriber.awaitTerminalEvent();
                        subscriber.assertError(TransportException.class);
                        subscriber.assertNotCompleted();
                        subscriber.assertNoValues();
                    }
                });
            
            assertEquals(0, allActiveAllocationsCount(allocator));
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionClientCanceledAsHttp() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                Feature.ENABLE_LOGGING);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                Feature.ENABLE_LOGGING);
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        try {
            startInteraction(
                client.initiator().remoteAddress(new LocalAddress(addr)), 
                Observable.just(fullHttpRequest()),
                new Interaction() {
                    @Override
                    public void interact(
                            final HttpInitiator initiator,
                            final Observable<HttpObject> response, 
                            final HttpMessageHolder holder) throws Exception {
                        final TestSubscriber<HttpObject> subscriber = new TestSubscriber<>();
                        final Subscription subscription = response.subscribe(subscriber);
                        
                        // server side recv req
                        final HttpTrade trade = trades.take();
                        
                        // recv request from client side
                        trade.inbound().message().toCompletable().await();
                        
                        // server not send response, and client cancel this interaction
                        subscription.unsubscribe();
                        
                        assertFalse(initiator.isActive());
                        subscriber.assertNoTerminalEvent();
                        subscriber.assertNoValues();
                    }
                });
            
            assertEquals(0, allActiveAllocationsCount(allocator));
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionClientCanceledAsHttps() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                enableSSL4ServerWithSelfSigned(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                enableSSL4Client(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        try {
            startInteraction(
                client.initiator().remoteAddress(new LocalAddress(addr)), 
                Observable.just(fullHttpRequest()),
                new Interaction() {
                    @Override
                    public void interact(
                            final HttpInitiator initiator,
                            final Observable<HttpObject> response, 
                            final HttpMessageHolder holder) throws Exception {
                        final TestSubscriber<HttpObject> subscriber = new TestSubscriber<>();
                        final Subscription subscription = response.subscribe(subscriber);
                        
                        // server side recv req
                        final HttpTrade trade = trades.take();
                        
                        // recv request from client side
                        trade.inbound().message().toCompletable().await();
                        
                        // server not send response, and client cancel this interaction
                        subscription.unsubscribe();
                        
                        TerminateAware.Util.awaitTerminated(trade);
                        TerminateAware.Util.awaitTerminated(initiator);
                        
                        assertFalse(initiator.isActive());
                        subscriber.assertNoTerminalEvent();
                        subscriber.assertNoValues();
                    }
                });
            
            assertEquals(0, allActiveAllocationsCount(allocator));
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionSendPartRequestThenFailedAsHttp() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                Feature.ENABLE_LOGGING);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                Feature.ENABLE_LOGGING);
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        try {
            final HttpRequest req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
            req.headers().set(HttpHeaderNames.CONTENT_LENGTH, 100);
            final ConnectableObservable<HttpObject> errorOfEnd = Observable.<HttpObject>error(
                    new RuntimeException("test error")).publish();
            final Channel ch1 = (Channel)startInteraction(
                client.initiator().remoteAddress(new LocalAddress(addr)), 
                Observable.concat(Observable.<HttpObject>just(req), errorOfEnd),
                new Interaction() {
                    @Override
                    public void interact(
                            final HttpInitiator initiator,
                            final Observable<HttpObject> response, 
                            final HttpMessageHolder holder) throws Exception {
                        initiator.setFlushPerWrite(true);
                        final TestSubscriber<HttpObject> subscriber = new TestSubscriber<>();
                        response.subscribe(subscriber);
                        
                        // server side recv req
                        final HttpTrade trade = trades.take();
                        assertTrue(trade.isActive());
                        
                        // fire error
                        errorOfEnd.connect();
                        
                        subscriber.awaitTerminalEvent();
                        subscriber.assertError(RuntimeException.class);
                        subscriber.assertNoValues();
                        
                        TerminateAware.Util.awaitTerminated(trade);
                        assertTrue(!trade.isActive());
                    }
                }).transport();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
            
            final Channel ch2 = (Channel)startInteraction(
                    client.initiator().remoteAddress(new LocalAddress(addr)), 
                    Observable.just(fullHttpRequest()),
                    standardInteraction(allocator, trades)).transport();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
            
            assertNotSame(ch1, ch2);
        } finally {
            client.close();
            server.unsubscribe();
        }
    }

    @Test(timeout=5000)
    public void testInitiatorInteractionSendPartRequestThenFailedAsHttps() 
        throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                enableSSL4ServerWithSelfSigned(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                enableSSL4Client(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        
        assertEquals(0, allActiveAllocationsCount(allocator));
        
        try {
            final HttpRequest req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
            req.headers().set(HttpHeaderNames.CONTENT_LENGTH, 100);
            final ConnectableObservable<HttpObject> errorOfEnd = Observable.<HttpObject>error(
                    new RuntimeException("test error")).publish();
            final Channel ch1 = (Channel)startInteraction(
                client.initiator().remoteAddress(new LocalAddress(addr)), 
                Observable.concat(Observable.<HttpObject>just(req), errorOfEnd),
                new Interaction() {
                    @Override
                    public void interact(
                            final HttpInitiator initiator,
                            final Observable<HttpObject> response, 
                            final HttpMessageHolder holder) throws Exception {
                        initiator.setFlushPerWrite(true);
                        final TestSubscriber<HttpObject> subscriber = new TestSubscriber<>();
                        response.subscribe(subscriber);
                        
                        // server side recv req
                        final HttpTrade trade = trades.take();
                        assertTrue(trade.isActive());
                        
                        // fire error
                        errorOfEnd.connect();
                        
                        subscriber.awaitTerminalEvent();
                        subscriber.assertError(RuntimeException.class);
                        subscriber.assertNoValues();
                        
                        TerminateAware.Util.awaitTerminated(trade);
                        assertTrue(!trade.isActive());
                    }
                }).transport();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
            
            final Channel ch2 = (Channel)startInteraction(
                    client.initiator().remoteAddress(new LocalAddress(addr)), 
                    Observable.just(fullHttpRequest()),
                    standardInteraction(allocator, trades)).transport();
            
            assertEquals(0, allActiveAllocationsCount(allocator));
            
            assertNotSame(ch1, ch2);
        } finally {
            client.close();
            server.unsubscribe();
        }
    }

    @Test(timeout=5000)
    public void testInitiatorInteractionSuccessAsHttp10ConnectionClose() throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                Feature.ENABLE_LOGGING);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                Feature.ENABLE_LOGGING);
        try {
            final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            startInteraction(client.initiator().remoteAddress(new LocalAddress(addr)), 
                Observable.just(request),
                new Interaction() {
                    @Override
                    public void interact(
                            final HttpInitiator initiator,
                            final Observable<HttpObject> response, 
                            final HttpMessageHolder holder) throws Exception {
                        final Observable<HttpObject> cached = response.cache();
                        
                        cached.subscribe();
                        
                        // server side recv req
                        final HttpTrade trade = trades.take();
                        
                        // recv all request
                        trade.inbound().message().toCompletable().await();
                        assertEquals(0, allActiveAllocationsCount(allocator));
                        
                        final ByteBuf svrRespContent = allocator.buffer(CONTENT.length).writeBytes(CONTENT);
                        assertEquals(1, allActiveAllocationsCount(allocator));
                        
                        //  for HTTP 1.0 Connection: Close response behavior
                        final FullHttpResponse fullrespfromsvr = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_0, 
                                HttpResponseStatus.OK, 
                                svrRespContent);
                        fullrespfromsvr.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                        //  missing Content-Length
//                        response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                        fullrespfromsvr.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        trade.outbound().message(Observable.just(fullrespfromsvr));
                        
                        // wait for recv all resp at client side
                        cached.toCompletable().await();
                        
                        svrRespContent.release();
                        
                        // holder create clientside resp's content
                        assertEquals(1, allActiveAllocationsCount(allocator));
                        
                        assertTrue(Arrays.equals(dumpResponseContentAsBytes(holder), CONTENT));
                    }
                });
        } finally {
            assertEquals(0, allActiveAllocationsCount(allocator));
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionSuccessAsHttps10ConnectionClose() throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                enableSSL4ServerWithSelfSigned(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                enableSSL4Client(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        try {
            final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            startInteraction(client.initiator().remoteAddress(new LocalAddress(addr)), 
                Observable.just(request),
                new Interaction() {
                    @Override
                    public void interact(
                            final HttpInitiator initiator,
                            final Observable<HttpObject> response, 
                            final HttpMessageHolder holder) throws Exception {
                        final Observable<HttpObject> cached = response.cache();
                        
                        cached.subscribe();
                        
                        // server side recv req
                        final HttpTrade trade = trades.take();
                        
                        // recv all request
                        trade.inbound().message().toCompletable().await();
                        
                        final ByteBuf svrRespContent = allocator.buffer(CONTENT.length).writeBytes(CONTENT);
                        
                        //  for HTTP 1.0 Connection: Close response behavior
                        final FullHttpResponse fullrespfromsvr = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_0, 
                                HttpResponseStatus.OK, 
                                svrRespContent);
                        fullrespfromsvr.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                        //  missing Content-Length
//                        response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                        fullrespfromsvr.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        trade.outbound().message(Observable.just(fullrespfromsvr));
                        
                        // wait for recv all resp at client side
                        cached.toCompletable().await();
                        
                        svrRespContent.release();
                        
                        assertTrue(Arrays.equals(dumpResponseContentAsBytes(holder), CONTENT));
                    }
                });
        } finally {
            assertEquals(0, allActiveAllocationsCount(allocator));
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionFailedAsHttp10ConnectionCloseMissingPartContent() throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                Feature.ENABLE_LOGGING);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                Feature.ENABLE_LOGGING);
        try {
            final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            startInteraction(client.initiator().remoteAddress(new LocalAddress(addr)), 
                Observable.just(request),
                new Interaction() {
                    @Override
                    public void interact(
                            final HttpInitiator initiator,
                            final Observable<HttpObject> response, 
                            final HttpMessageHolder holder) throws Exception {
                        
                        final CountDownLatch cdl4initiator = new CountDownLatch(1);
                        initiator.doOnTerminate(new Action0() {
                            @Override
                            public void call() {
                                cdl4initiator.countDown();
                            }});
                        
                        assertEquals(0, allActiveAllocationsCount(allocator));
                        
                        final TestSubscriber<HttpObject> subscriber = new TestSubscriber<>();
                        response.subscribe(subscriber);
                        
                        // server side recv req
                        final HttpTrade trade = trades.take();
                        
                        // recv all request
                        trade.inbound().message().toCompletable().await();
                        
                        final ByteBuf svrRespContent = allocator.buffer(CONTENT.length).writeBytes(CONTENT);
                        
                        //  for HTTP 1.0 Connection: Close response behavior
                        final FullHttpResponse fullrespfromsvr = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_0, 
                                HttpResponseStatus.OK, 
                                svrRespContent);
                        
                        fullrespfromsvr.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                        //  missing Content-Length
                        fullrespfromsvr.headers().set(HttpHeaderNames.CONTENT_LENGTH, 
                                fullrespfromsvr.content().readableBytes() + 1);
                        fullrespfromsvr.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        trade.outbound().message(Observable.just(fullrespfromsvr));
                        TerminateAware.Util.awaitTerminated(trade);
                        
                        subscriber.awaitTerminalEvent();
                        assertTrue(svrRespContent.release());
                        
                        subscriber.assertError(TransportException.class);
                        assertTrue(subscriber.getValueCount() > 0);
                        
                        cdl4initiator.await();
                        assertEquals(0, allActiveAllocationsCount(allocator));
                    }
                });
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=5000)
    public void testInitiatorInteractionFailedAsHttps10ConnectionCloseMissingPartContent() throws Exception {
        //  配置 池化分配器 为 取消缓存，使用 Heap
        configDefaultAllocator();

        final PooledByteBufAllocator allocator = defaultAllocator();
        
        final BlockingQueue<HttpTrade> trades = new ArrayBlockingQueue<>(1);
        final String addr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(addr, 
                trades,
                enableSSL4ServerWithSelfSigned(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        final DefaultHttpClient client = 
                new DefaultHttpClient(new TestChannelCreator(), 
                enableSSL4Client(),
                Feature.ENABLE_LOGGING_OVER_SSL);
        try {
            final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            startInteraction(client.initiator().remoteAddress(new LocalAddress(addr)), 
                Observable.just(request),
                new Interaction() {
                    @Override
                    public void interact(
                            final HttpInitiator initiator,
                            final Observable<HttpObject> response, 
                            final HttpMessageHolder holder) throws Exception {
                        
                        final CountDownLatch cdl4initiator = new CountDownLatch(1);
                        initiator.doOnTerminate(new Action0() {
                            @Override
                            public void call() {
                                cdl4initiator.countDown();
                            }});
                        
                        final TestSubscriber<HttpObject> subscriber = new TestSubscriber<>();
                        response.subscribe(subscriber);
                        
                        // server side recv req
                        final HttpTrade trade = trades.take();
                        
                        // recv all request
                        trade.inbound().message().toCompletable().await();
                        
                        final ByteBuf svrRespContent = allocator.buffer(CONTENT.length).writeBytes(CONTENT);
                        
                        //  for HTTP 1.0 Connection: Close response behavior
                        final FullHttpResponse fullrespfromsvr = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_0, 
                                HttpResponseStatus.OK, 
                                svrRespContent);
                        
                        fullrespfromsvr.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                        //  missing Content-Length
                        fullrespfromsvr.headers().set(HttpHeaderNames.CONTENT_LENGTH, 
                                fullrespfromsvr.content().readableBytes() + 1);
                        fullrespfromsvr.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        trade.outbound().message(Observable.just(fullrespfromsvr));
                        TerminateAware.Util.awaitTerminated(trade);
                        
                        subscriber.awaitTerminalEvent();
                        assertTrue(svrRespContent.release());
                        
                        subscriber.assertError(TransportException.class);
                        assertTrue(subscriber.getValueCount() > 0);
                        
                        cdl4initiator.await();
                        assertEquals(0, allActiveAllocationsCount(allocator));
                    }
                });
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    //  TODO, add more multi-call for same interaction define
    //       and check if each call generate different channel instance

    /* // TODO using initiator
    
    @Test
    public void testTrafficCounterWhenHttpAndNotConnected() throws Exception {
        
        final CountDownLatch unsubscribed = new CountDownLatch(1);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator, ENABLE_LOGGING);
        //    NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final HttpUtil.TrafficCounterFeature counter = HttpUtil.buildTrafficCounterFeature();
            
            client.defineInteraction(new LocalAddress("test"), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor),
                counter
                )
                .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
                .subscribe(testSubscriber);
            
            unsubscribed.await();
            
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
            assertEquals(0, counter.outboundBytes());
            assertEquals(0, counter.inboundBytes());
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getCompletions());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            nextSensor.assertNotCalled();
        }
    }
    
    @Test
    public void testTrafficCounterWhenHttpHappyPathOnce() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", CONTENT),
                ENABLE_LOGGING);

        final HttpUtil.TrafficCounterFeature counter = HttpUtil.buildTrafficCounterFeature();
        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(), 
                ENABLE_LOGGING,
                counter);
        try {
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress(testAddr), 
                    Observable.just(fullHttpRequest()))
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, CONTENT));
            assertTrue(0 < counter.outboundBytes());
            assertTrue(0 < counter.inboundBytes());
            LOG.debug("meter.uploadBytes: {}", counter.outboundBytes());
            LOG.debug("meter.downloadBytes: {}", counter.inboundBytes());
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    */
    
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
                                    Unpooled.wrappedBuffer(CONTENT));
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
