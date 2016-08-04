package org.jocean.http.rosa.impl;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLException;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;

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
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.AnnotationWrapper;
import org.jocean.idiom.rx.RxActions;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
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
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.CharsetUtil;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Action2;
import rx.functions.Func0;
import rx.functions.Func1;

public class DefaultSignalClientTestCase {

    @SuppressWarnings("unused")
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
    
    private static final String TEST_ADDR = "test";
    
    public static byte[] OK_RESP = "{\"code\":\"0\"}".getBytes(CharsetUtil.UTF_8);
    
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
    
    private static Observable<HttpObject> buildResponse(final Object responseBean) {
        final byte[] responseBytes = JSON.toJSONBytes(responseBean);
        final FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, OK, 
                Unpooled.wrappedBuffer(responseBytes));
        response.headers().set(CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, response.content().readableBytes());
        return  Observable.<HttpObject>just(response);
    }
    
    final Func1<URI, SocketAddress> TO_TEST_ADDR = new Func1<URI, SocketAddress>() {
        @Override
        public SocketAddress call(final URI uri) {
            return new LocalAddress(TEST_ADDR);
        }};
        
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
        
    @Test
    public void testSignalClientOnlySignal() throws Exception {
        final TestResponse respToSendback = new TestResponse("0", "OK");
        
        final Action2<Func0<FullHttpRequest>, HttpTrade> onRequestCompleted = 
            new Action2<Func0<FullHttpRequest>, HttpTrade>() {
            @Override
            public void call(final Func0<FullHttpRequest> genFullHttpRequest, final HttpTrade trade) {
                trade.outboundResponse(buildResponse(respToSendback));
            }};
        final Subscription server = createTestServerWith(TEST_ADDR, 
                onRequestCompleted,
                Feature.ENABLE_LOGGING,
                Feature.ENABLE_COMPRESSOR );
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        
        final DefaultHttpClient httpclient = new DefaultHttpClient(creator, pool);
        
        final DefaultSignalClient signalClient = new DefaultSignalClient(httpclient);
        
        signalClient.registerRequestType(TestRequest.class, TestResponse.class, 
                null, 
                TO_TEST_ADDR,
                Feature.ENABLE_LOGGING);
        final TestResponse respReceived = 
            ((SignalClient)signalClient).<TestResponse>defineInteraction(new TestRequest())
            .toBlocking().single();
        assertEquals(respToSendback, respReceived);
        
        pool.awaitRecycleChannels();
        
        server.unsubscribe();
    }
    
    @Test
    public void testSignalClientWithAttachment() throws Exception {
        
        final TestResponse respToSendback = new TestResponse("0", "OK");
        final List<FileUpload> uploads = new ArrayList<>();
        
        final Action2<Func0<FullHttpRequest>, HttpTrade> onRequestCompleted = new Action2<Func0<FullHttpRequest>, HttpTrade>() {
            @Override
            public void call(final Func0<FullHttpRequest> genFullHttpRequest, final HttpTrade trade) {
                final FullHttpRequest req = genFullHttpRequest.call();
                try {
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
                        }
                    }
                    trade.outboundResponse(buildResponse(respToSendback));
                } finally {
                    req.release();
                }
            }};
            
        //  launch test server for attachment send
        final Subscription server = createTestServerWith(TEST_ADDR, 
                onRequestCompleted,
                Feature.ENABLE_LOGGING,
                Feature.ENABLE_COMPRESSOR );
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        
        final DefaultHttpClient httpclient = new DefaultHttpClient(creator, pool);
        final DefaultSignalClient signalClient = new DefaultSignalClient(httpclient, 
                new AttachmentBuilder4InMemory());
        
        signalClient.registerRequestType(TestRequest.class, TestResponse.class, 
                null, 
                TO_TEST_ADDR,
                Feature.ENABLE_LOGGING,
                Outbound.ENABLE_MULTIPART);
        
        final AttachmentInMemory[] attachsToSend = new AttachmentInMemory[]{
                new AttachmentInMemory("1", "text/plain", "11111111111111".getBytes(Charsets.UTF_8)),
                new AttachmentInMemory("2", "text/plain", "22222222222222222".getBytes(Charsets.UTF_8)),
                new AttachmentInMemory("3", "text/plain", "333333333333333".getBytes(Charsets.UTF_8)),
        };
        
        final TestResponse respReceived = ((SignalClient)signalClient).<TestResponse>defineInteraction(
                    new TestRequest(), 
                    attachsToSend)
            .toBlocking().single();
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
        
        server.unsubscribe();
    }
}
