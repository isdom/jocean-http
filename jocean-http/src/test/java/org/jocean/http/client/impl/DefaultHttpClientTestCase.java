package org.jocean.http.client.impl;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jocean.http.Feature.ENABLE_LOGGING;
import static org.jocean.http.Feature.ENABLE_LOGGING_OVER_SSL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import javax.net.ssl.SSLException;

import org.jocean.http.Feature;
import org.jocean.http.Feature.ENABLE_SSL;
import org.jocean.http.TestHttpUtil;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.server.HttpTestServer;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.HttpUtil;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.rx.OnNextSensor;
import org.jocean.idiom.rx.RxFunctions;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.observers.TestSubscriber;
import rx.schedulers.Schedulers;

public class DefaultHttpClientTestCase {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpClientTestCase.class);

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
    
    private HttpTestServer createTestServerWithDefaultHandler(
            final boolean enableSSL, 
            final String acceptId) 
            throws Exception {
        return new HttpTestServer(
                enableSSL, 
                new LocalAddress(acceptId), 
                new DefaultEventLoopGroup(1), 
                new DefaultEventLoopGroup(),
                LocalServerChannel.class,
                HttpTestServer.DEFAULT_NEW_HANDLER);
    }

    private DefaultFullHttpRequest fullHttpRequest() {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    }

    private Action1<HttpTrade> responseBy(
            final String contentType, 
            final byte[] bodyAsBytes) {
        return new Action1<HttpTrade>() {
            @Override
            public void call(final HttpTrade trade) {
                trade.outboundResponse(TestHttpUtil.buildBytesResponse(contentType, bodyAsBytes));
            }};
    }
    
    //  TODO, add more multi-call for same interaction define
    //       and check if each call generate different channel instance
    //  Happy Path
    @Test
    public void testHttpHappyPathOnce() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", HttpTestServer.CONTENT),
                ENABLE_LOGGING);
        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(), ENABLE_LOGGING);
        try {
        
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress(testAddr), 
                    Observable.just(fullHttpRequest()))
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
        } finally {
            client.close();
            server.unsubscribe();
        }
    }

    @Test(timeout=1000)
    public void testHttpHappyPathObserveOnOtherthread() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", HttpTestServer.CONTENT),
                ENABLE_LOGGING);
        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(), ENABLE_LOGGING);
        try {
            final HttpMessageHolder holder = new HttpMessageHolder(0);
        
            client.defineInteraction(
                new LocalAddress(testAddr), 
                Observable.just(fullHttpRequest()))
            .observeOn(Schedulers.io())
            .compose(holder.assembleAndHold())
            .toBlocking().last();
            
            final FullHttpResponse resp = holder.httpMessageBuilder(RxNettys.BUILD_FULL_RESPONSE).call();
            try {
                final byte[] bytes = Nettys.dumpByteBufAsBytes(resp.content());
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            } finally {
                ReferenceCountUtil.release(resp);
            }
            
            holder.release().call();
            
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test
    public void testHttpHappyPathOnceAndCheckRefCount() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", HttpTestServer.CONTENT),
                ENABLE_LOGGING);

        final ByteBuf content = Unpooled.buffer(0);
        content.writeBytes("test content".getBytes("UTF-8"));
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", content);
        
        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(), 
                ENABLE_LOGGING);
        try {
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress(testAddr), 
                    Observable.just(request))
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            //  assert true referenced count release to zero
            assertTrue(ReferenceCountUtil.release(request));
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
        } finally {
            client.close();
            server.unsubscribe();
        }
        
        assertEquals(0, request.refCnt());
    }
    
    @Test
    public void testHttpsHappyPathOnce() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", HttpTestServer.CONTENT),
                enableSSL4ServerWithSelfSigned(),
                ENABLE_LOGGING_OVER_SSL);

        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(), 
                ENABLE_LOGGING_OVER_SSL,
                enableSSL4Client());
        try {
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress(testAddr), 
                    Observable.just(fullHttpRequest()))
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
        } finally {
            client.close();
            server.unsubscribe();
        }
    }

    @Test
    public void testHttpHappyPathKeepAliveReuseConnection() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", HttpTestServer.CONTENT),
                ENABLE_LOGGING);

        final TestChannelCreator creator = new TestChannelCreator();
    
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        try {
            // first 
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(fullHttpRequest()))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                //  await for 1 second
                pool.awaitRecycleChannels(1);
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(fullHttpRequest()))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            //  try wait for close
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test
    public void testHttpHappyPathKeepAliveReuseConnectionTwice() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", HttpTestServer.CONTENT),
                ENABLE_LOGGING);

        final TestChannelCreator creator = new TestChannelCreator();
    
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        try {
            // first 
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(fullHttpRequest()))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                //  await for 1 second
                pool.awaitRecycleChannelsAndReset(1, 1);
            }
            assertEquals(1, creator.getChannels().size());
            //  try wait for close
            creator.getChannels().get(0).assertNotClose(1);
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(fullHttpRequest()))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                //  await for 1 second
                pool.awaitRecycleChannelsAndReset(1, 1);
            }
            assertEquals(1, creator.getChannels().size());
            //  try wait for close
            creator.getChannels().get(0).assertNotClose(1);
            // third
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(fullHttpRequest()))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                //  await for 1 second
                pool.awaitRecycleChannelsAndReset(1, 1);
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test
    public void testHttpsHappyPathKeepAliveReuseConnection() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", HttpTestServer.CONTENT),
                enableSSL4ServerWithSelfSigned(),
                ENABLE_LOGGING_OVER_SSL);

        final TestChannelCreator creator = new TestChannelCreator();
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                enableSSL4Client());
        
        try {
            // first 
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(fullHttpRequest()))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                //  await for 1 second
                pool.awaitRecycleChannels(1);
            }
            assertEquals(1, creator.getChannels().size());
            //  try wait for close
            creator.getChannels().get(0).assertNotClose(1);
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(fullHttpRequest()))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            //  try wait for close
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test(timeout=10000)
    public void testHttpOnErrorBeforeSend1stAnd2ndHappyPathKeepAliveReuseConnection() throws Exception {
        //  TODO? create http server using flowing code, but exception with connectionException ? why? fix it
//        final String testAddr = UUID.randomUUID().toString();
//        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
//                responseBy("text/plain", HttpTestServer.CONTENT));
        
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");

        final TestChannelCreator creator = new TestChannelCreator();
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        
        try {
            // first 
            {
                final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
                final CountDownLatch unsubscribed = new CountDownLatch(1);
                try {
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.<HttpObject>error(new RuntimeException("test error")))
                    .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
                    .subscribe(testSubscriber);
                    unsubscribed.await();
                    
                    //  await for 1 second
                    pool.awaitRecycleChannels(1);
                } finally {
                    assertEquals(1, testSubscriber.getOnErrorEvents().size());
                    assertEquals(RuntimeException.class, 
                            testSubscriber.getOnErrorEvents().get(0).getClass());
                    assertEquals(0, testSubscriber.getCompletions());
                    assertEquals(0, testSubscriber.getOnNextEvents().size());
                }
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(fullHttpRequest()))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
//            server.unsubscribe();
            server.stop();
        }
    }

    @Test(timeout=10000)
    public void testHttpSendingError1stAnd2ndHappyPathNotReuseConnection() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", HttpTestServer.CONTENT),
                ENABLE_LOGGING);

        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setWriteException(new RuntimeException("write error"));
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        
        try {
            // first 
            {
                final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
                final CountDownLatch unsubscribed = new CountDownLatch(1);
                try {
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(fullHttpRequest()))
                    .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
                    .subscribe(testSubscriber);
                    unsubscribed.await();
                    
                    //  await for 1 second
                    pool.awaitRecycleChannels(1);
                } finally {
                    assertEquals(1, testSubscriber.getOnErrorEvents().size());
                    assertEquals(RuntimeException.class, 
                            testSubscriber.getOnErrorEvents().get(0).getClass());
                    assertEquals(0, testSubscriber.getCompletions());
                    assertEquals(0, testSubscriber.getOnNextEvents().size());
                }
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
            creator.reset();
            //  reset write exception
            creator.setWriteException(null);
            
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(fullHttpRequest()))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(2, creator.getChannels().size());
            creator.getChannels().get(1).assertNotClose(1);
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
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
            assertEquals(0, counter.uploadBytes());
            assertEquals(0, counter.downloadBytes());
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
                responseBy("text/plain", HttpTestServer.CONTENT),
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
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            assertTrue(0 < counter.uploadBytes());
            assertTrue(0 < counter.downloadBytes());
            LOG.debug("meter.uploadBytes: {}", counter.uploadBytes());
            LOG.debug("meter.downloadBytes: {}", counter.downloadBytes());
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
                responseBy("text/plain", HttpTestServer.CONTENT),
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
                        trade.abort();
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
                        trade.abort();
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

    @Test
    public void testHttpRequestEmitErrorAfterConnected() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", HttpTestServer.CONTENT),
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
                responseBy("text/plain", HttpTestServer.CONTENT),
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
                responseBy("text/plain", HttpTestServer.CONTENT),
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
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
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
                responseBy("text/plain", HttpTestServer.CONTENT),
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
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
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
                responseBy("text/plain", HttpTestServer.CONTENT),
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
                responseBy("text/plain", HttpTestServer.CONTENT),
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
                responseBy("text/plain", HttpTestServer.CONTENT),
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
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
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
                responseBy("text/plain", HttpTestServer.CONTENT),
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
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
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
                                Unpooled.wrappedBuffer(HttpTestServer.CONTENT));
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                        //  missing Content-Length
//                        response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        trade.outboundResponse(Observable.just(response));
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
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
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
                                Unpooled.wrappedBuffer(HttpTestServer.CONTENT));
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                        //  BAD Content-Length, actual length + 1
                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 
                                response.content().readableBytes() + 1);
                        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        trade.outboundResponse(Observable.just(response));
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
                                Unpooled.wrappedBuffer(HttpTestServer.CONTENT));
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                        //  missing Content-Length
//                        response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        trade.outboundResponse(Observable.just(response));
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
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
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
                                Unpooled.wrappedBuffer(HttpTestServer.CONTENT));
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                        //  BAD Content-Length, actual length + 1
                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 
                                response.content().readableBytes() + 1);
                        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        trade.outboundResponse(Observable.just(response));
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
