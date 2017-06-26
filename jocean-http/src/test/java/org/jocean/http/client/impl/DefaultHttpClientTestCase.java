package org.jocean.http.client.impl;

import static org.junit.Assert.assertEquals;
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

import javax.net.ssl.SSLException;

import org.jocean.http.Feature;
import org.jocean.http.Feature.ENABLE_SSL;
import org.jocean.http.TestHttpUtil;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.client.HttpClient.InitiatorBuilder;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PoolArenaMetric;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.local.LocalAddress;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
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

    private Action1<HttpTrade> responseBy(
            final String contentType, 
            final byte[] content) {
        return new Action1<HttpTrade>() {
            @Override
            public void call(final HttpTrade trade) {
                trade.outbound().message(TestHttpUtil.buildBytesResponse(contentType, content));
            }};
    }
    
    private Action1<HttpTrade> responseBy(
            final String contentType, 
            final ByteBuf content) {
        return new Action1<HttpTrade>() {
            @Override
            public void call(final HttpTrade trade) {
                trade.outbound().message(
                    TestHttpUtil.buildByteBufResponse(contentType, content));
            }};
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
        public void interact(final Observable<HttpObject> response, final HttpMessageHolder holder) throws Exception;
    }
    
    private static HttpInitiator startInteraction(
            final InitiatorBuilder builder,
            final Observable<? extends Object> request, 
            final Interaction interaction ) throws Exception {
        try ( final HttpInitiator initiator = builder.build().toBlocking().single()) {
            final HttpMessageHolder holder = new HttpMessageHolder();
            holder.setMaxBlockSize(-1);
            initiator.doOnTerminate(holder.closer());
            
            interaction.interact(
                initiator.defineInteraction(request)
                    .compose(holder.<HttpObject>assembleAndHold()), 
                holder);
            return initiator;
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
                    public void interact(final Observable<HttpObject> response, 
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
                    public void interact(final Observable<HttpObject> response, 
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
            public void interact(final Observable<HttpObject> response, 
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
                    public void interact(final Observable<HttpObject> response, 
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
                    public void interact(final Observable<HttpObject> response, 
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
    
    //  TODO, add more multi-call for same interaction define
    //       and check if each call generate different channel instance

    /* // TODO using initiator
    
    //  all kinds of exception
    //  Not connected
    @Test
    public void testHttpForNotConnected() throws Exception {
        
        final CountDownLatch unsubscribed = new CountDownLatch(1);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator, ENABLE_LOGGING);
        //    NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            client.defineInteraction(new LocalAddress("test"), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            
            unsubscribed.await();
            
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getCompletions());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            nextSensor.assertNotCalled();
        }
    }

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
    
    @Test
    public void testHttpForUnsubscribedBeforeSubscribe() throws Exception {
        
        final CountDownLatch unsubscribed = new CountDownLatch(1);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator, ENABLE_LOGGING);
        //    NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            testSubscriber.unsubscribe();
            
            client.defineInteraction(new LocalAddress(UUID.randomUUID().toString()), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            
            unsubscribed.await();
            
            assertEquals(0, creator.getChannels().size());
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getCompletions());
            assertEquals(0, testSubscriber.getOnErrorEvents().size());
            nextSensor.assertNotCalled();
        }
    }

    @Test
    public void testHttpsNotConnected() throws Exception {
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING,
                enableSSL4Client());
        //  NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(new LocalAddress(UUID.randomUUID().toString()), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            // await for unsubscribed
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getCompletions());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            nextSensor.assertNotCalled();
        }
    }

    @Test
    public void testHttpsNotShakehand() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", CONTENT),
                ENABLE_LOGGING);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                enableSSL4Client());
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress(testAddr), 
                Observable.just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            
            //  await for 1 second
            pool.awaitRecycleChannels(1);
            
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.unsubscribe();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getCompletions());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(NotSslRecordException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            nextSensor.assertNotCalled();
        }
    }
    
    @Test
    public void testHttpEmitExceptionWhenConnecting() throws Exception {
        final String errorMsg = "connecting failure";
        
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setConnectException(new RuntimeException(errorMsg));
        
        final DefaultHttpClient client = new DefaultHttpClient(creator);
        //    NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress(UUID.randomUUID().toString()), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            assertFalse(creator.getChannels().get(0).isActive());
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getCompletions());
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
        
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setConnectException(new RuntimeException(errorMsg));
        
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                enableSSL4Client());
        //    NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getCompletions());
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
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                new Action1<HttpTrade>() {
                    @Override
                    public void call(final HttpTrade trade) {
                        trade.close();
                    }},
                ENABLE_LOGGING);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress(testAddr),
                Observable.just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            
            //  await for 1 second
            pool.awaitRecycleChannels(1);
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
//            server.stop();
            server.unsubscribe();
            testSubscriber.assertTerminalEvent();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
//            assertEquals(RuntimeException.class, 
//                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getCompletions());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  channel connected, so message has been send
            nextSensor.assertCalled();
        }
    }
    
    @Test
    public void testHttpsDisconnectFromServerAfterConnected() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                new Action1<HttpTrade>() {
                    @Override
                    public void call(final HttpTrade trade) {
                        trade.close();
                    }},
                enableSSL4ServerWithSelfSigned(),
                ENABLE_LOGGING_OVER_SSL);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING_OVER_SSL,
                enableSSL4Client());
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress(testAddr), 
                Observable.just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            
            //  await for 1 second
            pool.awaitRecycleChannels(1);
            
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.unsubscribe();
            testSubscriber.assertTerminalEvent();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
//            assertEquals(RuntimeException.class, 
//                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getCompletions());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  channel connected, so message has been send
            nextSensor.assertCalled();
        }
    }
    
    @Test
    public void testHttpClientCanceledAfterConnected() throws Exception {
        final CountDownLatch serverRecvd = new CountDownLatch(1);
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                new Action1<HttpTrade>() {
                    @Override
                    public void call(final HttpTrade trade) {
                        LOG.debug("recv request {}, and do nothing.", trade);
                        serverRecvd.countDown();
                        //  never send response
                    }},
                ENABLE_LOGGING);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator);
        
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final Subscription subscription = 
                client.defineInteraction(
                    new LocalAddress(testAddr), 
                    Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
                .subscribe(testSubscriber);
            
            serverRecvd.await();
            
            //  server !NOT! send back
            subscription.unsubscribe();
            
            // test if close method has been called.
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.unsubscribe();
            
            testSubscriber.assertNoErrors();
            assertEquals(0, testSubscriber.getCompletions());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  channel connected, so message has been send
            nextSensor.assertCalled();
        }
    }

    @Test
    public void testHttpsClientCanceledAfterConnected() throws Exception {
        final CountDownLatch serverRecvd = new CountDownLatch(1);
        
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                new Action1<HttpTrade>() {
                    @Override
                    public void call(final HttpTrade trade) {
                        LOG.debug("recv request {}, and do nothing.", trade);
                        serverRecvd.countDown();
                        //  never send response
                    }},
                enableSSL4ServerWithSelfSigned(),
                ENABLE_LOGGING_OVER_SSL);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING_OVER_SSL,
                enableSSL4Client());
        
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final Subscription subscription = 
                client.defineInteraction(
                    new LocalAddress(testAddr), 
                    Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
                .subscribe(testSubscriber);
            
            serverRecvd.await();
            
//            assertEquals(1, client.getActiveChannelCount());
            //  server !NOT! send back
            subscription.unsubscribe();
            
            //  await for 1 second
            pool.awaitRecycleChannels(1);
            
            // test if close method has been called.
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            // 注意: 一个 try-with-resources 语句可以像普通的 try 语句那样有 catch 和 finally 块。 
            //  在try-with-resources 语句中, 任意的 catch 或者 finally 块都是在声明的资源被关闭以后才运行。 
			client.close();
			server.unsubscribe();
//            assertEquals(0, client.getActiveChannelCount());
            testSubscriber.assertNoErrors();
            assertEquals(0, testSubscriber.getCompletions());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  channel connected, so message has been send
            nextSensor.assertCalled();
        }
    }

    @Test(timeout=10000)
    public void testHttpRequestEmitErrorAfterConnected() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", CONTENT),
                ENABLE_LOGGING);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING);
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress(testAddr), 
                Observable.<HttpObject>error(new RuntimeException("test error")))
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
            server.unsubscribe();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getCompletions());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
        }
    }

    @Test(timeout=10000)
    public void testHttpsRequestEmitErrorAfterConnected() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", CONTENT),
                enableSSL4ServerWithSelfSigned(),
                ENABLE_LOGGING_OVER_SSL);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING_OVER_SSL,
                enableSSL4Client());
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress(testAddr), 
                Observable.<HttpObject>error(new RuntimeException("test error")))
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            
            //  await for 1 second
            pool.awaitRecycleChannels(1);
            
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
            server.unsubscribe();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getCompletions());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
        }
    }
    
    @Test(timeout=10000)
    public void testHttpRequestEmitErrorAfterConnectedAndReuse2nd() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", CONTENT),
                ENABLE_LOGGING);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            //  first
            {
                final CountDownLatch unsubscribed = new CountDownLatch(1);
                client.defineInteraction(
                    new LocalAddress(testAddr), 
                    Observable.<HttpObject>error(new RuntimeException("test error")))
                .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
                .subscribe(testSubscriber);
                unsubscribed.await();
                testSubscriber.awaitTerminalEvent();
                
                //  await for 1 second
                pool.awaitRecycleChannels(1);
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getCompletions());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(fullHttpRequest()))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
            server.unsubscribe();
        }
    }

    @Test(timeout=10000)
    public void testHttpsRequestEmitErrorAfterConnectedAndReuse2nd() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", CONTENT),
                enableSSL4ServerWithSelfSigned(),
                ENABLE_LOGGING_OVER_SSL);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING_OVER_SSL,
                enableSSL4Client());
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            //  first
            {
                final CountDownLatch unsubscribed = new CountDownLatch(1);
                client.defineInteraction(
                    new LocalAddress(testAddr), 
                    Observable.<HttpObject>error(new RuntimeException("test error")))
                .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
                .subscribe(testSubscriber);
                unsubscribed.await();
                testSubscriber.awaitTerminalEvent();
                
                //  await for 1 second
                pool.awaitRecycleChannels(1);
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getCompletions());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(fullHttpRequest()))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=10000)
    public void testHttpClientWriteAndFlushExceptionAfterConnected() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", CONTENT),
                ENABLE_LOGGING);
        
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setWriteException(new RuntimeException("doWrite Error for test"));
        
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING);
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress(testAddr), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.unsubscribe();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getCompletions());
            //  no response received
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  message has been write to send queue
            nextSensor.assertCalled();
        }
    }

    @Test
    public void testHttpsClientWriteAndFlushExceptionAfterConnected() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", CONTENT),
                enableSSL4ServerWithSelfSigned(),
                ENABLE_LOGGING_OVER_SSL);
        
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setWriteException(new RuntimeException("doWrite Error for test"));
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING_OVER_SSL,
                enableSSL4Client());
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress(testAddr), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            
            //  await for 1 second
            pool.awaitRecycleChannels(1);
            
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.unsubscribe();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(SSLException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getCompletions());
            //  no response received
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  message has been write to send queue
            nextSensor.assertNotCalled();
        }
    }

    @Test(timeout=10000)
    public void testHttpClientWriteAndFlushExceptionAfterConnectedAndNewConnection2nd() 
            throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", CONTENT),
                ENABLE_LOGGING);
        
        final TestChannelCreator creator = new TestChannelCreator();
        creator.setWriteException(new RuntimeException("doWrite Error for test"));
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
            try {
            {
                final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
                final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
                // first
                final CountDownLatch unsubscribed = new CountDownLatch(1);
                client.defineInteraction(
                    new LocalAddress(testAddr), 
                    Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
                .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
                .subscribe(testSubscriber);
                unsubscribed.await();
                testSubscriber.awaitTerminalEvent();
                
                //  await for 1 second
                pool.awaitRecycleChannels(1);
                
                assertEquals(1, creator.getChannels().size());
                creator.getChannels().get(0).assertClosed(1);
                assertEquals(1, testSubscriber.getOnErrorEvents().size());
                assertEquals(RuntimeException.class, 
                        testSubscriber.getOnErrorEvents().get(0).getClass());
                assertEquals(0, testSubscriber.getCompletions());
                //  no response received
                assertEquals(0, testSubscriber.getOnNextEvents().size());
                //  message has been write to send queue
                nextSensor.assertCalled();
            }
            // reset creator
            creator.reset();
            creator.setWriteException(null);
            
            {
                // second
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(fullHttpRequest()))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, CONTENT));
                assertEquals(2, creator.getChannels().size());
                creator.getChannels().get(1).assertNotClose(1);
            }
        } finally {
            client.close();
            server.unsubscribe();
        }
    }

    @Test
    public void testHttpsClientWriteAndFlushExceptionAfterConnectedAndNewConnection2nd() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", CONTENT),
                enableSSL4ServerWithSelfSigned(),
                ENABLE_LOGGING_OVER_SSL);
        
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setWriteException(new RuntimeException("doWrite Error for test"));
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING_OVER_SSL,
                enableSSL4Client());
        try {
            {
                final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
                final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
                final CountDownLatch unsubscribed = new CountDownLatch(1);
                client.defineInteraction(
                    new LocalAddress(testAddr), 
                    Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
                .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
                .subscribe(testSubscriber);
                unsubscribed.await();
                testSubscriber.awaitTerminalEvent();
                
                //  await for 1 second
                pool.awaitRecycleChannels(1);
                
                assertEquals(1, creator.getChannels().size());
                creator.getChannels().get(0).assertClosed(1);
                assertEquals(1, testSubscriber.getOnErrorEvents().size());
                assertEquals(SSLException.class, 
                        testSubscriber.getOnErrorEvents().get(0).getClass());
                assertEquals(0, testSubscriber.getCompletions());
                //  no response received
                assertEquals(0, testSubscriber.getOnNextEvents().size());
                //  message has been write to send queue
                nextSensor.assertNotCalled();
            }
            // reset creator
            creator.setWriteException(null);
            {
                // second
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(fullHttpRequest()))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, CONTENT));
                assertEquals(2, creator.getChannels().size());
                creator.getChannels().get(1).assertNotClose(1);
            }
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test
    public void testHttp10ConnectionCloseHappyPath() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                new Action1<HttpTrade>() {
                    @Override
                    public void call(final HttpTrade trade) {
                        //  for HTTP 1.0 Connection: Close response behavior
                        final FullHttpResponse response = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_0, OK, 
                                Unpooled.wrappedBuffer(CONTENT));
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                        //  missing Content-Length
//                        response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        trade.outbound().message(Observable.just(response));
                    }
            
            },
            ENABLE_LOGGING);
    
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING);
        try {
            final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress(testAddr), 
                    Observable.just(request))
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, CONTENT));
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).awaitClosed();
        } finally {
            client.close();
            server.unsubscribe();
        }
    }

    @Test
    public void testHttp10ConnectionCloseBadCaseMissingPartContent() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                new Action1<HttpTrade>() {
                    @Override
                    public void call(final HttpTrade trade) {
                        //  for HTTP 1.0 Connection: Close response behavior
                        final FullHttpResponse response = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_0, OK, 
                                Unpooled.wrappedBuffer(CONTENT));
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                        //  BAD Content-Length, actual length + 1
                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 
                                response.content().readableBytes() + 1);
                        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        trade.outbound().message(Observable.just(response));
                    }
            
            },
            ENABLE_LOGGING);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING);
        final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress(testAddr), 
                Observable.<HttpObject>just(request))
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.unsubscribe();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
//            assertEquals(RuntimeException.class, 
//                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getCompletions());
            assertTrue(testSubscriber.getOnNextEvents().size()>=1);
        }
    }
    
    @Test
    public void testHttps10ConnectionCloseHappyPath() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                new Action1<HttpTrade>() {
                    @Override
                    public void call(final HttpTrade trade) {
                        //  for HTTP 1.0 Connection: Close response behavior
                        final FullHttpResponse response = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_0, OK, 
                                Unpooled.wrappedBuffer(CONTENT));
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                        //  missing Content-Length
//                        response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        trade.outbound().message(Observable.just(response));
                    }
            
            },
            enableSSL4ServerWithSelfSigned(),
            ENABLE_LOGGING_OVER_SSL);

        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING_OVER_SSL,
                enableSSL4Client());
        try {
            final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress(testAddr), 
                    Observable.just(request))
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, CONTENT));
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).awaitClosed();
        } finally {
            client.close();
            server.unsubscribe();
        }
    }

    @Test
    public void testHttps10ConnectionCloseBadCaseMissingPartContent() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                new Action1<HttpTrade>() {
                    @Override
                    public void call(final HttpTrade trade) {
                        //  for HTTP 1.0 Connection: Close response behavior
                        final FullHttpResponse response = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_0, OK, 
                                Unpooled.wrappedBuffer(CONTENT));
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                        //  BAD Content-Length, actual length + 1
                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 
                                response.content().readableBytes() + 1);
                        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        trade.outbound().message(Observable.just(response));
                    }},
                enableSSL4ServerWithSelfSigned(),
                ENABLE_LOGGING_OVER_SSL);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING_OVER_SSL,
                enableSSL4Client());
        final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress(testAddr), 
                Observable.<HttpObject>just(request))
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            
            //  await for 1 second
            pool.awaitRecycleChannels(1);
            
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.unsubscribe();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
//            assertEquals(RuntimeException.class, 
//                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getCompletions());
            assertTrue(testSubscriber.getOnNextEvents().size()>=1);
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
