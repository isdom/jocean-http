package org.jocean.http.client.impl;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import io.netty.bootstrap.ChannelFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.client.HttpClient.Feature;
import org.jocean.http.server.HttpTestServer;
import org.jocean.http.server.HttpTestServerHandler;
import org.jocean.idiom.ExceptionUtils;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Func1;
import rx.observers.TestSubscriber;

public class DefaultHttpClientTestCase {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpClientTestCase.class);

    private byte[] responseAsBytes(final Iterator<HttpObject> itr)
            throws IOException {
        final CompositeByteBuf composite = Unpooled.compositeBuffer();
        try {
            while (itr.hasNext()) {
                final HttpObject obj = itr.next();
                if (obj instanceof HttpContent) {
                    composite.addComponent(((HttpContent)obj).content());
                }
                System.out.println(obj);
            }
            composite.setIndex(0, composite.capacity());
            
            @SuppressWarnings("resource")
            final InputStream is = new ByteBufInputStream(composite);
            final byte[] bytes = new byte[is.available()];
            is.read(bytes);
            return bytes;
        } finally {
            ReferenceCountUtil.release(composite);
        }
    }

    @Test
    public void testHttpHappyPath() throws Exception {
        final HttpTestServer server = new HttpTestServer(
                false, 
                new LocalAddress("test"), 
                new LocalEventLoopGroup(1), 
                new LocalEventLoopGroup(),
                LocalServerChannel.class,
                HttpTestServer.DEFAULT_NEW_HANDLER);

        final DefaultHttpClient client = new DefaultHttpClient(
                new LocalEventLoopGroup(1), LocalChannel.class);
        try {
        
            final Iterator<HttpObject> itr = 
                client.sendRequest(
                    new LocalAddress("test"), 
                    Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                .map(new Func1<HttpObject, HttpObject>() {
                    @Override
                    public HttpObject call(final HttpObject obj) {
                        //    retain obj for blocking
                        return ReferenceCountUtil.retain(obj);
                    }})
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = responseAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, HttpTestServerHandler.CONTENT));
        } finally {
            client.close();
            server.stop();
            assertEquals(0, client.getActiveChannelCount());
        }
    }

    @Test
    public void testHttpHappyPathAndCheckRefCount() throws Exception {
        final HttpTestServer server = new HttpTestServer(
                false, 
                new LocalAddress("test"), 
                new LocalEventLoopGroup(1), 
                new LocalEventLoopGroup(),
                LocalServerChannel.class,
                HttpTestServer.DEFAULT_NEW_HANDLER);

        final ByteBuf content = Unpooled.buffer(0);
        content.writeBytes("test content".getBytes("UTF-8"));
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", content);
        
        final DefaultHttpClient client = new DefaultHttpClient(
                new LocalEventLoopGroup(1), LocalChannel.class);
        try {
            
            final Iterator<HttpObject> itr = 
                client.sendRequest(
                    new LocalAddress("test"), 
                    Observable.just(request))
                .map(new Func1<HttpObject, HttpObject>() {
                    @Override
                    public HttpObject call(final HttpObject obj) {
                        //  retain obj for blocking
                        return ReferenceCountUtil.retain(obj);
                    }})
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = responseAsBytes(itr);
            
            ReferenceCountUtil.release(request);
            
            assertTrue(Arrays.equals(bytes, HttpTestServerHandler.CONTENT));
        } finally {
            client.close();
            server.stop();
            assertEquals(0, client.getActiveChannelCount());
        }
        
        assertEquals(0, request.refCnt());
    }
    
    @Test
    public void testHttpsHappyPath() throws Exception {
        final HttpTestServer server = new HttpTestServer(
                true, 
                new LocalAddress("test"), 
                new LocalEventLoopGroup(1), 
                new LocalEventLoopGroup(),
                LocalServerChannel.class,
                HttpTestServer.DEFAULT_NEW_HANDLER);

        final DefaultHttpClient client = new DefaultHttpClient(
                new LocalEventLoopGroup(1), LocalChannel.class);
        try {
        
            final Iterator<HttpObject> itr = 
                client.sendRequest(
                    new LocalAddress("test"), 
                    Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")),
                    Feature.EnableSSL)
                .map(new Func1<HttpObject, HttpObject>() {
                    @Override
                    public HttpObject call(final HttpObject obj) {
                        //    retain obj for blocking
                        return ReferenceCountUtil.retain(obj);
                    }})
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = responseAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, HttpTestServerHandler.CONTENT));
        } finally {
            client.close();
            server.stop();
            assertEquals(0, client.getActiveChannelCount());
        }
    }
    
    @Test
    public void testHttpNotConnected() throws Exception {
        final CountDownLatch clientChannelClosed = new CountDownLatch(1);
        // mark channel closed
        final class TestLocalChannel extends LocalChannel {
            @Override
            public ChannelFuture close() {
                clientChannelClosed.countDown();
                return super.close();
            }
        }
        final DefaultHttpClient client = new DefaultHttpClient(
                new LocalEventLoopGroup(1), new ChannelFactory<TestLocalChannel>() {
                    @Override
                    public TestLocalChannel newChannel() {
                        return new TestLocalChannel();
                    }});
        //    NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            client.sendRequest(
                new LocalAddress("test"), 
                Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
            .subscribe(testSubscriber);
            testSubscriber.awaitTerminalEvent();
            clientChannelClosed.await();
        } finally {
            client.close();
            assertEquals(0, client.getActiveChannelCount());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
        }
    }

    @Test
    public void testHttpsNotConnected() throws Exception {
        final CountDownLatch clientChannelClosed = new CountDownLatch(1);
        // mark channel closed
        final class TestLocalChannel extends LocalChannel {
            @Override
            public ChannelFuture close() {
                clientChannelClosed.countDown();
                return super.close();
            }
        }
        final DefaultHttpClient client = new DefaultHttpClient(
                new LocalEventLoopGroup(1), new ChannelFactory<TestLocalChannel>() {
                    @Override
                    public TestLocalChannel newChannel() {
                        return new TestLocalChannel();
                    }});
        //  NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            client.sendRequest(
                new LocalAddress("test"), 
                Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")),
                Feature.EnableSSL)
            .subscribe(testSubscriber);
            testSubscriber.awaitTerminalEvent();
            clientChannelClosed.await();
        } finally {
            client.close();
            assertEquals(0, client.getActiveChannelCount());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
        }
    }

    @Test
    public void testHttpsNotShakehand() throws Exception {
        // http server
        final HttpTestServer server = new HttpTestServer(
                false, 
                new LocalAddress("test"), 
                new LocalEventLoopGroup(1), 
                new LocalEventLoopGroup(),
                LocalServerChannel.class,
                HttpTestServer.DEFAULT_NEW_HANDLER);
        
        final CountDownLatch clientChannelClosed = new CountDownLatch(1);
        
        // mark channel closed
        final class TestLocalChannel extends LocalChannel {
            @Override
            public ChannelFuture close() {
                clientChannelClosed.countDown();
                return super.close();
            }
        }
        
        final DefaultHttpClient client = new DefaultHttpClient(
                new LocalEventLoopGroup(1), new ChannelFactory<TestLocalChannel>() {
                    @Override
                    public TestLocalChannel newChannel() {
                        return new TestLocalChannel();
                    }});
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            client.sendRequest(
                new LocalAddress("test"), 
                Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")),
                Feature.EnableSSL)
            .subscribe(testSubscriber);
            clientChannelClosed.await();
        } finally {
            client.close();
            server.stop();
            assertEquals(0, client.getActiveChannelCount());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(javax.net.ssl.SSLException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
        }
    }
    
    abstract class TestHandler extends SimpleChannelInboundHandler<HttpObject> {

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }    
    
    @Test
    public void testHttpDisconnectAfterConnected() throws Exception {
        final HttpTestServer server = new HttpTestServer(
                false, 
                new LocalAddress("test"), 
                new LocalEventLoopGroup(1), 
                new LocalEventLoopGroup(),
                LocalServerChannel.class,
                new Callable<ChannelInboundHandler> () {
                    @Override
                    public ChannelInboundHandler call() throws Exception {
                        return new TestHandler() {
                            @Override
                            protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                                    throws Exception {
                                if (msg instanceof HttpRequest) {
                                    ctx.close();
                                }
                            }
                        };
                    }});
        
        final CountDownLatch clientChannelClosed = new CountDownLatch(1);
        
        // mark channel closed
        final class TestLocalChannel extends LocalChannel {
            @Override
            public ChannelFuture close() {
                clientChannelClosed.countDown();
                return super.close();
            }
        }
        
        final DefaultHttpClient client = new DefaultHttpClient(
                new LocalEventLoopGroup(1), new ChannelFactory<TestLocalChannel>() {
                    @Override
                    public TestLocalChannel newChannel() {
                        return new TestLocalChannel();
                    }});
        final AtomicBoolean isOnErrorCalled = new AtomicBoolean(false);
        final AtomicBoolean isOnCompletedCalled = new AtomicBoolean(false);
        final AtomicBoolean isOnNextCalled = new AtomicBoolean(false);
        final AtomicReference<Throwable> lastError = new AtomicReference<Throwable>();
        try {
            client.sendRequest(
                new LocalAddress("test"), 
                Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
            .subscribe(new Subscriber<HttpObject>() {
                @Override
                public void onCompleted() {
                    isOnCompletedCalled.set(true);
                    LOG.debug("testHttpsNotShakehand: onCompleted");
                }
                @Override
                public void onError(Throwable e) {
                    isOnErrorCalled.set(true);
                    lastError.set(e);
                    LOG.debug("testHttpsNotShakehand: onError, detail:{}",
                            ExceptionUtils.exception2detail(e));
                }
                @Override
                public void onNext(HttpObject t) {
                    isOnNextCalled.set(true);
                    LOG.debug("testHttpsNotShakehand: onNext");
                }});
            clientChannelClosed.await();
        } finally {
            client.close();
            server.stop();
            assertEquals(0, client.getActiveChannelCount());
            assertEquals(RuntimeException.class, lastError.get().getClass());
            assertTrue(isOnErrorCalled.get());
            assertFalse(isOnCompletedCalled.get());
            assertFalse(isOnNextCalled.get());
        }
    }
    
    @Test
    public void testHttpClientCanceledAfterConnected() throws Exception {
        final CountDownLatch serverRecvd = new CountDownLatch(1);
        final CountDownLatch clientChannelClosed = new CountDownLatch(1);
        
        // mark channel closed
        final class TestLocalChannel extends LocalChannel {
            @Override
            public ChannelFuture close() {
                clientChannelClosed.countDown();
                return super.close();
            }
        }
        
        final HttpTestServer server = new HttpTestServer(
                false, 
                new LocalAddress("test"), 
                new LocalEventLoopGroup(1), 
                new LocalEventLoopGroup(),
                LocalServerChannel.class,
                new Callable<ChannelInboundHandler> () {
                    @Override
                    public ChannelInboundHandler call() throws Exception {
                        return new TestHandler() {
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
        
        final DefaultHttpClient client = new DefaultHttpClient(
                new LocalEventLoopGroup(1), new ChannelFactory<TestLocalChannel>() {
                    @Override
                    public TestLocalChannel newChannel() {
                        return new TestLocalChannel();
                    }});
        final AtomicBoolean isOnErrorCalled = new AtomicBoolean(false);
        final AtomicBoolean isOnCompletedCalled = new AtomicBoolean(false);
        final AtomicBoolean isOnNextCalled = new AtomicBoolean(false);
        try {
            final Subscription subscription = 
                client.sendRequest(
                    new LocalAddress("test"), 
                    Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                .subscribe(new Subscriber<HttpObject>() {
                    @Override
                    public void onCompleted() {
                        isOnCompletedCalled.set(true);
                        LOG.debug("testHttpClientCanceledAfterConnected: onCompleted");
                    }
                    @Override
                    public void onError(Throwable e) {
                        isOnErrorCalled.set(true);
                        LOG.debug("testHttpClientCanceledAfterConnected: onError, detail:{}",
                                ExceptionUtils.exception2detail(e));
                    }
                    @Override
                    public void onNext(HttpObject t) {
                        isOnNextCalled.set(true);
                        LOG.debug("testHttpClientCanceledAfterConnected: onNext");
                    }});
            
            serverRecvd.await();
            
            assertEquals(1, client.getActiveChannelCount());
            //  server !NOT! send back
            subscription.unsubscribe();
            
            // test if close method has been called.
            assertEquals(0, clientChannelClosed.getCount());
        } finally {
            // 注意: 一个 try-with-resources 语句可以像普通的 try 语句那样有 catch 和 finally 块。
            //  在try-with-resources 语句中, 任意的 catch 或者 finally 块都是在声明的资源被关闭以后才运行。
            client.close();
            server.stop();
            assertEquals(0, client.getActiveChannelCount());
            assertFalse(isOnErrorCalled.get());
            assertFalse(isOnCompletedCalled.get());
            assertFalse(isOnNextCalled.get());
        }
    }

    @Test
    public void testEmitErrorAfterConnected() throws Exception {
        final CountDownLatch clientChannelClosed = new CountDownLatch(1);
        
        // mark channel closed
        final class TestLocalChannel extends LocalChannel {
            @Override
            public ChannelFuture close() {
                clientChannelClosed.countDown();
                return super.close();
            }
        }
        
        final HttpTestServer server = new HttpTestServer(
                false, 
                new LocalAddress("test"), 
                new LocalEventLoopGroup(1), 
                new LocalEventLoopGroup(),
                LocalServerChannel.class,
                new Callable<ChannelInboundHandler> () {
                    @Override
                    public ChannelInboundHandler call() throws Exception {
                        return new TestHandler() {
                            @Override
                            protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                                    throws Exception {
                            }
                        };
                    }});
        
        final DefaultHttpClient client = new DefaultHttpClient(
                new LocalEventLoopGroup(1), new ChannelFactory<TestLocalChannel>() {
                    @Override
                    public TestLocalChannel newChannel() {
                        return new TestLocalChannel();
                    }});
        final AtomicBoolean isOnErrorCalled = new AtomicBoolean(false);
        final AtomicBoolean isOnCompletedCalled = new AtomicBoolean(false);
        final AtomicBoolean isOnNextCalled = new AtomicBoolean(false);
        try {
            @SuppressWarnings("unused")
            final Subscription subscription = 
                client.sendRequest(
                    new LocalAddress("test"), 
                    Observable.error(new RuntimeException("test error")))
                .subscribe(new Subscriber<HttpObject>() {
                    @Override
                    public void onCompleted() {
                        isOnCompletedCalled.set(true);
                        LOG.debug("testEmitErrorAfterConnected: onCompleted");
                    }
                    @Override
                    public void onError(Throwable e) {
                        isOnErrorCalled.set(true);
                        LOG.debug("testEmitErrorAfterConnected: onError, detail:{}",
                                ExceptionUtils.exception2detail(e));
                    }
                    @Override
                    public void onNext(HttpObject t) {
                        isOnNextCalled.set(true);
                        LOG.debug("testEmitErrorAfterConnected: onNext");
                    }});
            
            clientChannelClosed.await();
        } finally {
            // 注意: 一个 try-with-resources 语句可以像普通的 try 语句那样有 catch 和 finally 块。
            //  在try-with-resources 语句中, 任意的 catch 或者 finally 块都是在声明的资源被关闭以后才运行。
            client.close();
            server.stop();
            assertEquals(0, client.getActiveChannelCount());
            assertTrue(isOnErrorCalled.get());
            assertFalse(isOnCompletedCalled.get());
            assertFalse(isOnNextCalled.get());
        }
    }

    @Test
    public void testHttpClientWriteAndFlushExceptionAfterConnected() throws Exception {
        final CountDownLatch clientChannelClosed = new CountDownLatch(1);
        
        // mark channel closed
        final class TestLocalChannel extends LocalChannel {
            @Override
            protected void doWrite(ChannelOutboundBuffer in) throws Exception {
                throw new RuntimeException("doWrite Error for test");
            }
            @Override
            public ChannelFuture close() {
                clientChannelClosed.countDown();
                return super.close();
            }
        }
        
        final HttpTestServer server = new HttpTestServer(
                false, 
                new LocalAddress("test"), 
                new LocalEventLoopGroup(1), 
                new LocalEventLoopGroup(),
                LocalServerChannel.class,
                new Callable<ChannelInboundHandler> () {
                    @Override
                    public ChannelInboundHandler call() throws Exception {
                        return new TestHandler() {
                            @Override
                            protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                                    throws Exception {
                            }
                        };
                    }});
        
        final DefaultHttpClient client = new DefaultHttpClient(
                new LocalEventLoopGroup(1), new ChannelFactory<TestLocalChannel>() {
                    @Override
                    public TestLocalChannel newChannel() {
                        return new TestLocalChannel();
                    }});
        final AtomicBoolean isOnErrorCalled = new AtomicBoolean(false);
        final AtomicBoolean isOnCompletedCalled = new AtomicBoolean(false);
        final AtomicBoolean isOnNextCalled = new AtomicBoolean(false);
        try {
            @SuppressWarnings("unused")
            final Subscription subscription = 
                client.sendRequest(
                    new LocalAddress("test"), 
                    Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                .subscribe(new Subscriber<HttpObject>() {
                    @Override
                    public void onCompleted() {
                        isOnCompletedCalled.set(true);
                        LOG.debug("testHttpClientWriteAndFlushExceptionAfterConnected: onCompleted");
                    }
                    @Override
                    public void onError(Throwable e) {
                        isOnErrorCalled.set(true);
                        LOG.debug("testHttpClientWriteAndFlushExceptionAfterConnected: onError, detail:{}",
                                ExceptionUtils.exception2detail(e));
                    }
                    @Override
                    public void onNext(HttpObject t) {
                        isOnNextCalled.set(true);
                        LOG.debug("testHttpClientWriteAndFlushExceptionAfterConnected: onNext");
                    }});
            
            clientChannelClosed.await();
        } finally {
            client.close();
            server.stop();
            assertEquals(0, client.getActiveChannelCount());
            assertTrue(isOnErrorCalled.get());
            assertFalse(isOnCompletedCalled.get());
            assertFalse(isOnNextCalled.get());
        }
    }

    @Test
    public void testHttp10ConnectionCloseHappyPath() throws Exception {
        final HttpTestServer server = new HttpTestServer(
            false, 
            new LocalAddress("test"), 
            new LocalEventLoopGroup(1), 
            new LocalEventLoopGroup(),
            LocalServerChannel.class,
            new Callable<ChannelInboundHandler> () {
                @Override
                public ChannelInboundHandler call() throws Exception {
                    return new TestHandler() {
                        @Override
                        protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                                throws Exception {
                            if (msg instanceof HttpRequest) {
                                //  for HTTP 1.0 Connection: Close response behavior
                                final FullHttpResponse response = new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_0, OK, 
                                        Unpooled.wrappedBuffer(HttpTestServerHandler.CONTENT));
                                response.headers().set(CONTENT_TYPE, "text/plain");
                                //  missing Content-Length
//                                response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                                response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
                                ctx.write(response).addListener(ChannelFutureListener.CLOSE);
                            }
                        }
                    };
                }});

        final DefaultHttpClient client = new DefaultHttpClient(
                new LocalEventLoopGroup(1), LocalChannel.class);
        try {
            final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
            request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
            
            final Iterator<HttpObject> itr = 
                client.sendRequest(
                    new LocalAddress("test"), 
                    Observable.just(request),
                    Feature.EnableLOG,
                    Feature.DisableCompress)
                .map(new Func1<HttpObject, HttpObject>() {
                    @Override
                    public HttpObject call(final HttpObject obj) {
                        //  retain obj for blocking
                        return ReferenceCountUtil.retain(obj);
                    }})
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = responseAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, HttpTestServerHandler.CONTENT));
        } finally {
            client.close();
            server.stop();
            assertEquals(0, client.getActiveChannelCount());
        }
    }

    @Test
    public void testHttp10ConnectionCloseBadCaseMissingPartContent() throws Exception {
        final CountDownLatch clientChannelClosed = new CountDownLatch(1);
        
        // mark channel closed
        final class TestLocalChannel extends LocalChannel {
            @Override
            public ChannelFuture close() {
                clientChannelClosed.countDown();
                return super.close();
            }
        }
        
        final HttpTestServer server = new HttpTestServer(
            false, 
            new LocalAddress("test"), 
            new LocalEventLoopGroup(1), 
            new LocalEventLoopGroup(),
            LocalServerChannel.class,
            new Callable<ChannelInboundHandler> () {
                @Override
                public ChannelInboundHandler call() throws Exception {
                    return new TestHandler() {
                        @Override
                        protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                                throws Exception {
                            if (msg instanceof HttpRequest) {
                                //  for HTTP 1.0 Connection: Close response behavior
                                final FullHttpResponse response = new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_0, OK, 
                                        Unpooled.wrappedBuffer(HttpTestServerHandler.CONTENT));
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

        final DefaultHttpClient client = new DefaultHttpClient(
                new LocalEventLoopGroup(1), new ChannelFactory<TestLocalChannel>() {
                    @Override
                    public TestLocalChannel newChannel() {
                        return new TestLocalChannel();
                    }});
        final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
        
        final AtomicBoolean isOnErrorCalled = new AtomicBoolean(false);
        final AtomicBoolean isOnCompletedCalled = new AtomicBoolean(false);
        final AtomicBoolean isOnNextCalled = new AtomicBoolean(false);
        try {
            client.sendRequest(
                new LocalAddress("test"), 
                Observable.just(request),
                Feature.DisableCompress)
            .subscribe(new Subscriber<HttpObject>() {
                @Override
                public void onCompleted() {
                    isOnCompletedCalled.set(true);
                    LOG.debug("testHttp10ConnectionCloseBadCaseMissingPartContent: onCompleted");
                }
                @Override
                public void onError(Throwable e) {
                    isOnErrorCalled.set(true);
                    LOG.debug("testHttp10ConnectionCloseBadCaseMissingPartContent: onError, detail:{}",
                            ExceptionUtils.exception2detail(e));
                }
                @Override
                public void onNext(HttpObject t) {
                    isOnNextCalled.set(true);
                    LOG.debug("testHttp10ConnectionCloseBadCaseMissingPartContent: onNext ({})", t);
                }});
            
            clientChannelClosed.await();
        } finally {
            client.close();
            server.stop();
            assertEquals(0, client.getActiveChannelCount());
            assertTrue(isOnNextCalled.get());
            assertTrue(isOnErrorCalled.get());
            assertFalse(isOnCompletedCalled.get());
        }
    }
}
