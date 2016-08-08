package org.jocean.http.rosa.impl;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLException;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;

import org.jocean.http.Feature;
import org.jocean.http.client.Outbound;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.client.impl.TestChannelCreator;
import org.jocean.http.client.impl.TestChannelPool;
import org.jocean.http.rosa.SignalClient;
import org.jocean.http.server.HttpServer;
import org.jocean.http.server.HttpServer.HttpTrade;
import org.jocean.http.server.impl.AbstractBootstrapCreator;
import org.jocean.http.server.impl.DefaultHttpServer;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.AnnotationWrapper;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.rx.RxActions;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.annotation.JSONField;
import com.google.common.base.Charsets;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Action2;
import rx.functions.Func0;
import rx.functions.Func1;

public class DefaultSignalClientTestCase {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultSignalClientTestCase.class);

    final static SslContext sslCtx;
    static {
        sslCtx = initSslCtx();
    }

    private static SslContext initSslCtx() {
        try {
            return SslContextBuilder.forClient().build();
        } catch (SSLException e) {
            return null;
        }
    }
    
    private final static HttpServer TEST_SERVER_BUILDER = new DefaultHttpServer(
            new AbstractBootstrapCreator(
            new LocalEventLoopGroup(1), new LocalEventLoopGroup()) {
        @Override
        protected void initializeBootstrap(final ServerBootstrap bootstrap) {
            bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
            bootstrap.channel(LocalServerChannel.class);
        }});

    final HttpDataFactory HTTP_DATA_FACTORY = new DefaultHttpDataFactory(false);
    
    private static Subscription createTestServerWith(
            final String acceptId,
            final Action2<Func0<FullHttpRequest>, HttpTrade> onRequestCompleted,
            final Feature... features) {
        return TEST_SERVER_BUILDER.defineServer(new LocalAddress(acceptId), features)
            .subscribe(new Action1<HttpTrade>() {
                @Override
                public void call(final HttpTrade trade) {
                    final HttpMessageHolder holder = new HttpMessageHolder(0);
                    trade.inboundRequest()
                        .compose(holder.assembleAndHold())
                        .doOnCompleted(RxActions.bindParameter(onRequestCompleted,
                                holder.bindHttpObjects(RxNettys.BUILD_FULL_REQUEST), 
                                trade))
                        .doAfterTerminate(holder.release())
                        .doOnUnsubscribe(holder.release())
                        .subscribe();
                }});
    }
    
    private static Subscription createTestServerWith(
            final String acceptId,
            final Action1<HttpTrade> onRequestCompleted,
            final Feature... features) {
        return TEST_SERVER_BUILDER.defineServer(new LocalAddress(acceptId), features)
            .subscribe(new Action1<HttpTrade>() {
                @Override
                public void call(final HttpTrade trade) {
                    trade.inboundRequest()
                        .doOnCompleted(RxActions.bindParameter(onRequestCompleted, trade))
                        .subscribe();
                }});
    }
    
    private static Observable<HttpObject> buildResponse(final Object responseBean) {
        final byte[] responseBytes = JSON.toJSONBytes(responseBean);
        final FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, OK, 
                Unpooled.wrappedBuffer(responseBytes));
        response.headers().set(CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, response.content().readableBytes());
        return  Observable.<HttpObject>just(response);
    }
    
    private static Func1<URI, SocketAddress> buildUri2Addr(final String addr) {
        return new Func1<URI, SocketAddress>() {
            @Override
            public SocketAddress call(final URI uri) {
                return new LocalAddress(addr);
            }};
    }
        
    /*
    @Test
    public void testSignalClientMethodOf() {
        
        @AnnotationWrapper(OPTIONS.class)
        class Req4Options {}
        
        assertEquals(HttpMethod.OPTIONS, DefaultSignalClient.methodOf(Req4Options.class));
        
        @AnnotationWrapper(POST.class)
        class Req4Post {}
        
        assertEquals(HttpMethod.POST, DefaultSignalClient.methodOf(Req4Post.class));
        
        @AnnotationWrapper(GET.class)
        class Req4GET {}
        
        assertEquals(HttpMethod.GET, DefaultSignalClient.methodOf(Req4GET.class));
        
        class ReqWithoutExplicitMethod {}
        
        assertEquals(HttpMethod.GET, DefaultSignalClient.methodOf(ReqWithoutExplicitMethod.class));
        
        @AnnotationWrapper(HEAD.class)
        class Req4Head {}
        
        assertEquals(HttpMethod.HEAD, DefaultSignalClient.methodOf(Req4Head.class));

        @AnnotationWrapper(PUT.class)
        class Req4Put {}
        
        assertEquals(HttpMethod.PUT, DefaultSignalClient.methodOf(Req4Put.class));
        
        @AnnotationWrapper(DELETE.class)
        class Req4Delete {}
        
        assertEquals(HttpMethod.DELETE, DefaultSignalClient.methodOf(Req4Delete.class));
    }
    */
        
    @Path("/test/simpleRequest")
    public static class TestRequest {
        
        public TestRequest() {}
        
        public TestRequest(final String id) {
            this._id = id;
        }
        
        /* (non-Javadoc)
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((_id == null) ? 0 : _id.hashCode());
            return result;
        }

        /* (non-Javadoc)
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            TestRequest other = (TestRequest) obj;
            if (_id == null) {
                if (other._id != null)
                    return false;
            } else if (!_id.equals(other._id))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "[id=" + _id + "]";
        }
        
        public String getId() {
            return this._id;
        }

        public void setId(final String id) {
            this._id = id;
        }

        @QueryParam("id")
        protected String _id;
    }
    
    @AnnotationWrapper(POST.class)
    @Path("/test/simpleRequest")
    public static class TestRequestByPost {
        
        public TestRequestByPost() {}
        
        public TestRequestByPost(final String id) {
            this._id = id;
        }
        
        /* (non-Javadoc)
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((_id == null) ? 0 : _id.hashCode());
            return result;
        }

        /* (non-Javadoc)
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            TestRequestByPost other = (TestRequestByPost) obj;
            if (_id == null) {
                if (other._id != null)
                    return false;
            } else if (!_id.equals(other._id))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "[id=" + _id + "]";
        }
        
        @JSONField(name="id")
        public String getId() {
            return this._id;
        }

        @JSONField(name="id")
        public void setId(final String id) {
            this._id = id;
        }

        protected String _id;
    }
    
    public static class TestResponse {
        
        public TestResponse() {}
        
        public TestResponse(final String code, final String msg) {
            this._code = code;
            this._message = msg;
        }
        
        /* (non-Javadoc)
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((_code == null) ? 0 : _code.hashCode());
            result = prime * result
                    + ((_message == null) ? 0 : _message.hashCode());
            return result;
        }

        /* (non-Javadoc)
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            TestResponse other = (TestResponse) obj;
            if (_code == null) {
                if (other._code != null)
                    return false;
            } else if (!_code.equals(other._code))
                return false;
            if (_message == null) {
                if (other._message != null)
                    return false;
            } else if (!_message.equals(other._message))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "[code=" + _code +  ",message=" + _message + "]";
        }
        
        @JSONField(name="code")
        public String getCode() {
            return this._code;
        }

        @JSONField(name="code")
        public void setCode(final String code) {
            this._code = code;
        }

        @JSONField(name="message")
        public String getMessage() {
            return this._message;
        }

        @JSONField(name="message")
        public void setMessage(final String msg) {
            this._message = msg;
        }

        protected String _code;
        
        protected String _message;
    }
    
    @Test
    public void testSignalClientOnlySignalForGet() throws Exception {
        final TestResponse respToSendback = new TestResponse("0", "OK");
        final AtomicReference<HttpMethod> reqMethodReceivedRef = new AtomicReference<>();
        final AtomicReference<String> reqpathReceivedRef = new AtomicReference<>();
        final AtomicReference<String> reqbeanReceivedRef = new AtomicReference<>();
        
        final Action2<Func0<FullHttpRequest>, HttpTrade> requestAndTradeAwareWhenCompleted = 
            new Action2<Func0<FullHttpRequest>, HttpTrade>() {
            @Override
            public void call(final Func0<FullHttpRequest> genFullHttpRequest, final HttpTrade trade) {
                final FullHttpRequest req = genFullHttpRequest.call();
                try {
                    reqMethodReceivedRef.set(req.getMethod());
                    final QueryStringDecoder decoder = new QueryStringDecoder(req.getUri());
                    reqpathReceivedRef.set(decoder.path());
                    reqbeanReceivedRef.set(decoder.parameters().get("id").get(0));
                } finally {
                    req.release();
                }
                trade.outboundResponse(buildResponse(respToSendback));
            }};
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = createTestServerWith(testAddr, 
                requestAndTradeAwareWhenCompleted,
                Feature.ENABLE_LOGGING,
                Feature.ENABLE_COMPRESSOR );
        
        try {
            final TestChannelCreator creator = new TestChannelCreator();
            final TestChannelPool pool = new TestChannelPool(1);
            
            final DefaultHttpClient httpclient = new DefaultHttpClient(creator, pool);
            
            final DefaultSignalClient signalClient = new DefaultSignalClient("http://test", httpclient);
            
            signalClient.registerRequestType(TestRequest.class, TestResponse.class, 
                    null, 
                    buildUri2Addr(testAddr),
                    Feature.ENABLE_LOGGING);
            
            final TestRequest reqToSend = new TestRequest("1");
            final TestResponse respReceived = 
                ((SignalClient)signalClient).<TestResponse>defineInteraction(reqToSend)
                .timeout(1, TimeUnit.SECONDS)
                .toBlocking().single();
            
            assertEquals(HttpMethod.GET, reqMethodReceivedRef.get());
            assertEquals("/test/simpleRequest", reqpathReceivedRef.get());
            assertEquals(reqToSend.getId(), reqbeanReceivedRef.get());
            assertEquals(respToSendback, respReceived);
            
            pool.awaitRecycleChannels();
        } finally {
            server.unsubscribe();
        }
    }
    
    @Test
    public void testSignalClientOnlySignalForPost() throws Exception {
        final TestResponse respToSendback = new TestResponse("0", "OK");
        final AtomicReference<HttpMethod> reqMethodReceivedRef = new AtomicReference<>();
        final AtomicReference<String> reqpathReceivedRef = new AtomicReference<>();
        final AtomicReference<TestRequestByPost> reqbeanReceivedRef = new AtomicReference<>();
        
        final Action2<Func0<FullHttpRequest>, HttpTrade> requestAndTradeAwareWhenCompleted = 
            new Action2<Func0<FullHttpRequest>, HttpTrade>() {
            @Override
            public void call(final Func0<FullHttpRequest> genFullHttpRequest, final HttpTrade trade) {
                final FullHttpRequest req = genFullHttpRequest.call();
                try {
                    reqMethodReceivedRef.set(req.getMethod());
                    reqpathReceivedRef.set(req.getUri());
                    reqbeanReceivedRef.set(
                            (TestRequestByPost) JSON.parseObject(Nettys.dumpByteBufAsBytes(req.content()), 
                                    TestRequestByPost.class));
                } catch (IOException e) {
                    LOG.warn("exception when Nettys.dumpByteBufAsBytes, detail: {}",
                            ExceptionUtils.exception2detail(e));
                } finally {
                    req.release();
                }
                trade.outboundResponse(buildResponse(respToSendback));
            }};
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = createTestServerWith(testAddr, 
                requestAndTradeAwareWhenCompleted,
                Feature.ENABLE_LOGGING,
                Feature.ENABLE_COMPRESSOR );
        
        try {
            final TestChannelCreator creator = new TestChannelCreator();
            final TestChannelPool pool = new TestChannelPool(1);
            
            final DefaultHttpClient httpclient = new DefaultHttpClient(creator, pool);
            
            final DefaultSignalClient signalClient = new DefaultSignalClient("http://test", httpclient);
            
            signalClient.registerRequestType(TestRequestByPost.class, TestResponse.class, 
                    null, 
                    buildUri2Addr(testAddr),
                    Feature.ENABLE_LOGGING);
            
            final TestRequestByPost reqToSend = new TestRequestByPost("1");
            final TestResponse respReceived = 
                ((SignalClient)signalClient).<TestResponse>defineInteraction(reqToSend)
                .timeout(1, TimeUnit.SECONDS)
                .toBlocking().single();
            
            assertEquals(HttpMethod.POST, reqMethodReceivedRef.get());
            assertEquals("/test/simpleRequest", reqpathReceivedRef.get());
            assertEquals(reqToSend, reqbeanReceivedRef.get());
            assertEquals(respToSendback, respReceived);
            
            pool.awaitRecycleChannels();
        } finally {
            server.unsubscribe();
        }
    }
    
    @AnnotationWrapper(POST.class)
    @Path("/test/simpleRequest")
    public static class TestRequestByPostWithQueryParam {
        
        public TestRequestByPostWithQueryParam() {}
        
        public TestRequestByPostWithQueryParam(final String id, final String p) {
            this._id = id;
            this._queryp = p;
        }
        
        /* (non-Javadoc)
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((_id == null) ? 0 : _id.hashCode());
            result = prime * result
                    + ((_queryp == null) ? 0 : _queryp.hashCode());
            return result;
        }

        /* (non-Javadoc)
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            TestRequestByPostWithQueryParam other = (TestRequestByPostWithQueryParam) obj;
            if (_id == null) {
                if (other._id != null)
                    return false;
            } else if (!_id.equals(other._id))
                return false;
            if (_queryp == null) {
                if (other._queryp != null)
                    return false;
            } else if (!_queryp.equals(other._queryp))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "[id=" + _id + "]";
        }
        
        @JSONField(name="id")
        public String getId() {
            return this._id;
        }

        @JSONField(name="id")
        public void setId(final String id) {
            this._id = id;
        }

        protected String _id;
        
        @AnnotationWrapper(POST.class)
        @QueryParam("p")
        String _queryp;
    }
    
    @Test
    public void testSignalClientOnlySignalForPostWithQueryParam() throws Exception {
        final TestResponse respToSendback = new TestResponse("0", "OK");
        final AtomicReference<HttpMethod> reqMethodReceivedRef = new AtomicReference<>();
        final AtomicReference<String> reqpathReceivedRef = new AtomicReference<>();
        final AtomicReference<TestRequestByPostWithQueryParam> reqbeanReceivedRef = new AtomicReference<>();
        
        final Action2<Func0<FullHttpRequest>, HttpTrade> requestAndTradeAwareWhenCompleted = 
            new Action2<Func0<FullHttpRequest>, HttpTrade>() {
            @Override
            public void call(final Func0<FullHttpRequest> genFullHttpRequest, final HttpTrade trade) {
                final FullHttpRequest req = genFullHttpRequest.call();
                try {
                    reqMethodReceivedRef.set(req.getMethod());
                    final QueryStringDecoder decoder = new QueryStringDecoder(req.getUri());
                    reqpathReceivedRef.set(decoder.path());
                    final TestRequestByPostWithQueryParam reqbean = (TestRequestByPostWithQueryParam) JSON.parseObject(Nettys.dumpByteBufAsBytes(req.content()), 
                            TestRequestByPostWithQueryParam.class);
                    reqbean._queryp = decoder.parameters().get("p").get(0);
                    reqbeanReceivedRef.set(reqbean);
                } catch (IOException e) {
                    LOG.warn("exception when Nettys.dumpByteBufAsBytes, detail: {}",
                            ExceptionUtils.exception2detail(e));
                } finally {
                    req.release();
                }
                trade.outboundResponse(buildResponse(respToSendback));
            }};
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = createTestServerWith(testAddr, 
                requestAndTradeAwareWhenCompleted,
                Feature.ENABLE_LOGGING,
                Feature.ENABLE_COMPRESSOR );
        
        try {
            final TestChannelCreator creator = new TestChannelCreator();
            final TestChannelPool pool = new TestChannelPool(1);
            
            final DefaultHttpClient httpclient = new DefaultHttpClient(creator, pool);
            
            final DefaultSignalClient signalClient = new DefaultSignalClient("http://test", httpclient);
            
            signalClient.registerRequestType(TestRequestByPostWithQueryParam.class, TestResponse.class, 
                    null, 
                    buildUri2Addr(testAddr),
                    Feature.ENABLE_LOGGING);
            
            final TestRequestByPostWithQueryParam reqToSend = new TestRequestByPostWithQueryParam("1", "test");
            final TestResponse respReceived = 
                ((SignalClient)signalClient).<TestResponse>defineInteraction(reqToSend)
                .timeout(1, TimeUnit.SECONDS)
                .toBlocking().single();
            
            assertEquals(HttpMethod.POST, reqMethodReceivedRef.get());
            assertEquals("/test/simpleRequest", reqpathReceivedRef.get());
            assertEquals(reqToSend, reqbeanReceivedRef.get());
            assertEquals(respToSendback, respReceived);
            
            pool.awaitRecycleChannels();
        } finally {
            server.unsubscribe();
        }
    }
    
    @AnnotationWrapper(POST.class)
    @Path("/test/simpleRequest")
    public static class TestRequestByPostWithHeaderParam {
        
        public TestRequestByPostWithHeaderParam() {}
        
        public TestRequestByPostWithHeaderParam(final String id, final String p) {
            this._id = id;
            this._headerp = p;
        }
        
        /* (non-Javadoc)
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((_id == null) ? 0 : _id.hashCode());
            result = prime * result
                    + ((_headerp == null) ? 0 : _headerp.hashCode());
            return result;
        }

        /* (non-Javadoc)
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            TestRequestByPostWithHeaderParam other = (TestRequestByPostWithHeaderParam) obj;
            if (_id == null) {
                if (other._id != null)
                    return false;
            } else if (!_id.equals(other._id))
                return false;
            if (_headerp == null) {
                if (other._headerp != null)
                    return false;
            } else if (!_headerp.equals(other._headerp))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "[id=" + _id + "]";
        }
        
        @JSONField(name="id")
        public String getId() {
            return this._id;
        }

        @JSONField(name="id")
        public void setId(final String id) {
            this._id = id;
        }

        protected String _id;
        
        @HeaderParam("X-P")
        String _headerp;
    }
    
    @Test
    public void testSignalClientOnlySignalForPostWithHeaderParam() throws Exception {
        final TestResponse respToSendback = new TestResponse("0", "OK");
        final AtomicReference<HttpMethod> reqMethodReceivedRef = new AtomicReference<>();
        final AtomicReference<String> reqpathReceivedRef = new AtomicReference<>();
        final AtomicReference<TestRequestByPostWithHeaderParam> reqbeanReceivedRef = new AtomicReference<>();
        
        final Action2<Func0<FullHttpRequest>, HttpTrade> requestAndTradeAwareWhenCompleted = 
            new Action2<Func0<FullHttpRequest>, HttpTrade>() {
            @Override
            public void call(final Func0<FullHttpRequest> genFullHttpRequest, final HttpTrade trade) {
                final FullHttpRequest req = genFullHttpRequest.call();
                try {
                    reqMethodReceivedRef.set(req.getMethod());
                    reqpathReceivedRef.set(req.getUri());
                    final TestRequestByPostWithHeaderParam reqbean = (TestRequestByPostWithHeaderParam) JSON.parseObject(Nettys.dumpByteBufAsBytes(req.content()), 
                            TestRequestByPostWithHeaderParam.class);
                    reqbean._headerp = req.headers().get("X-P");
                    reqbeanReceivedRef.set(reqbean);
                } catch (IOException e) {
                    LOG.warn("exception when Nettys.dumpByteBufAsBytes, detail: {}",
                            ExceptionUtils.exception2detail(e));
                } finally {
                    req.release();
                }
                trade.outboundResponse(buildResponse(respToSendback));
            }};
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = createTestServerWith(testAddr, 
                requestAndTradeAwareWhenCompleted,
                Feature.ENABLE_LOGGING,
                Feature.ENABLE_COMPRESSOR );
        
        try {
            final TestChannelCreator creator = new TestChannelCreator();
            final TestChannelPool pool = new TestChannelPool(1);
            
            final DefaultHttpClient httpclient = new DefaultHttpClient(creator, pool);
            
            final DefaultSignalClient signalClient = new DefaultSignalClient("http://test", httpclient);
            
            signalClient.registerRequestType(TestRequestByPostWithHeaderParam.class, TestResponse.class, 
                    null, 
                    buildUri2Addr(testAddr),
                    Feature.ENABLE_LOGGING);
            
            final TestRequestByPostWithHeaderParam reqToSend = new TestRequestByPostWithHeaderParam("1", "test");
            final TestResponse respReceived = 
                ((SignalClient)signalClient).<TestResponse>defineInteraction(reqToSend)
                .timeout(1, TimeUnit.SECONDS)
                .toBlocking().single();
            
            assertEquals(HttpMethod.POST, reqMethodReceivedRef.get());
            assertEquals("/test/simpleRequest", reqpathReceivedRef.get());
            assertEquals(reqToSend, reqbeanReceivedRef.get());
            assertEquals(respToSendback, respReceived);
            
            pool.awaitRecycleChannels();
        } finally {
            server.unsubscribe();
        }
    }
    
    @Test
    public void testSignalClientWithAttachmentSuccess() throws Exception {
        
        final TestResponse respToSendback = new TestResponse("0", "OK");
        final AtomicReference<HttpMethod> reqMethodReceivedRef = new AtomicReference<>();
        final AtomicReference<String> reqpathReceivedRef = new AtomicReference<>();
        final AtomicReference<TestRequestByPost> reqbeanReceivedRef = new AtomicReference<>();
        final List<FileUpload> uploads = new ArrayList<>();
        
        final Action2<Func0<FullHttpRequest>, HttpTrade> requestAndTradeAwareWhenCompleted = 
            new Action2<Func0<FullHttpRequest>, HttpTrade>() {
            @Override
            public void call(final Func0<FullHttpRequest> genFullHttpRequest, final HttpTrade trade) {
                final FullHttpRequest req = genFullHttpRequest.call();
                try {
                    reqMethodReceivedRef.set(req.getMethod());
                    reqpathReceivedRef.set(req.getUri());
                    HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(
                            HTTP_DATA_FACTORY, req);
                    //  first is signal
                    boolean isfirst = true;
                    while (decoder.hasNext()) {
                        final InterfaceHttpData data = decoder.next();
                        if (!isfirst) {
                            if (data instanceof FileUpload) {
                                uploads.add((FileUpload)data);
                            }
                        } else {
                            isfirst = false;
                            try {
                                reqbeanReceivedRef.set(
                                        (TestRequestByPost) JSON.parseObject(Nettys.dumpByteBufAsBytes(((FileUpload)data).content()), 
                                                TestRequestByPost.class));
                            } catch (Exception e) {
                                LOG.warn("exception when JSON.parseObject, detail: {}",
                                        ExceptionUtils.exception2detail(e));
                            }
                        }
                    }
                    trade.outboundResponse(buildResponse(respToSendback));
                } finally {
                    req.release();
                }
            }};
            
        //  launch test server for attachment send
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = createTestServerWith(testAddr, 
                requestAndTradeAwareWhenCompleted,
                Feature.ENABLE_LOGGING,
                Feature.ENABLE_COMPRESSOR );
        
        try {
            final TestChannelCreator creator = new TestChannelCreator();
            final TestChannelPool pool = new TestChannelPool(1);
            
            final DefaultHttpClient httpclient = new DefaultHttpClient(creator, pool);
            final DefaultSignalClient signalClient = new DefaultSignalClient("http://test", httpclient, 
                    new AttachmentBuilder4InMemory());
            
            signalClient.registerRequestType(TestRequestByPost.class, TestResponse.class, 
                    null, 
                    buildUri2Addr(testAddr),
                    Feature.ENABLE_LOGGING,
                    Outbound.ENABLE_MULTIPART);
            
            final AttachmentInMemory[] attachsToSend = new AttachmentInMemory[]{
                    new AttachmentInMemory("1", "text/plain", "11111111111111".getBytes(Charsets.UTF_8)),
                    new AttachmentInMemory("2", "text/plain", "22222222222222222".getBytes(Charsets.UTF_8)),
                    new AttachmentInMemory("3", "text/plain", "333333333333333".getBytes(Charsets.UTF_8)),
            };
            
            final TestRequestByPost reqToSend = new TestRequestByPost("1");
            final TestResponse respReceived = ((SignalClient)signalClient).<TestResponse>defineInteraction(
                    reqToSend, 
                    attachsToSend)
                .timeout(1, TimeUnit.SECONDS)
                .toBlocking().single();
            
            assertEquals(HttpMethod.POST, reqMethodReceivedRef.get());
            assertEquals("/test/simpleRequest", reqpathReceivedRef.get());
            assertEquals(reqToSend, reqbeanReceivedRef.get());
            assertEquals(respToSendback, respReceived);
            
            final FileUpload[] attachsReceived = uploads.toArray(new FileUpload[0]);
            
            assertEquals(attachsToSend.length, attachsReceived.length);
            for (int idx = 0; idx < attachsToSend.length; idx++) {
                final AttachmentInMemory inmemory = attachsToSend[idx];
                final FileUpload upload = attachsReceived[idx];
                assertEquals(inmemory.filename, upload.getName());
                assertEquals(inmemory.contentType, upload.getContentType());
                assertTrue( Arrays.equals(inmemory.content(), upload.get()));
            }
            
            pool.awaitRecycleChannels();
        } finally {
            server.unsubscribe();
        }
    }

    @Test(expected = TimeoutException.class)
    public void testSignalClientWithAttachmentMissingENABLE_MULTIPART() throws Throwable {
        //  launch test server for attachment send
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = createTestServerWith(testAddr, 
            new Action1<HttpTrade>() {
                // NOT care http request, using trade for sendback response
                @Override
                public void call(final HttpTrade trade) {
                    trade.outboundResponse(buildResponse(new TestResponse("0", "OK")));
                }},
            Feature.ENABLE_LOGGING,
            Feature.ENABLE_COMPRESSOR );
        try {
            final TestChannelCreator creator = new TestChannelCreator();
            final TestChannelPool pool = new TestChannelPool(1);
            
            final DefaultHttpClient httpclient = new DefaultHttpClient(creator, pool);
            final DefaultSignalClient signalClient = new DefaultSignalClient("http://test", httpclient, 
                    new AttachmentBuilder4InMemory());
            
            signalClient.registerRequestType(TestRequestByPost.class, TestResponse.class, 
                    null, 
                    buildUri2Addr(testAddr),
                    Feature.ENABLE_LOGGING);
            
            ((SignalClient)signalClient).<TestResponse>defineInteraction(
                new TestRequestByPost("1"), 
                new AttachmentInMemory("1", "text/plain", "11111111111111".getBytes(Charsets.UTF_8)),
                new AttachmentInMemory("2", "text/plain", "22222222222222222".getBytes(Charsets.UTF_8)),
                new AttachmentInMemory("3", "text/plain", "333333333333333".getBytes(Charsets.UTF_8)))
            .timeout(1, TimeUnit.SECONDS)
            .toBlocking().single();
        } catch (RuntimeException e) {
            throw e.getCause();
        } finally {
            server.unsubscribe();
        }
    }
    
    //  TODO: add Path annotation with placeholder's testcase
}
