package org.jocean.http.rosa.impl;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
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
import org.jocean.http.server.HttpTestServer;
import org.jocean.http.server.HttpTestServerHandler;
import org.jocean.http.server.impl.AbstractBootstrapCreator;
import org.jocean.http.server.impl.DefaultHttpServer;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.AnnotationWrapper;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.rx.RxActions;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
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
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
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

    private static final String TEST_ADDR = "test";

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

    private static HttpServer createTestServerWith(
            final String acceptId,
            final Action2<Func0<FullHttpRequest>, HttpTrade> onCompleted,
            final Feature... features) {
        final Action1<HttpTrade> onIncomingTrade = new Action1<HttpTrade>() {
            @Override
            public void call(final HttpTrade trade) {
                final HttpMessageHolder holder = new HttpMessageHolder(0);
                trade.inboundRequest()
                    .compose(holder.assembleAndHold())
                    .doOnCompleted(RxActions.bindParameter(onCompleted,
                            holder.bindHttpObjects(RxNettys.BUILD_FULL_REQUEST), trade))
                    .doAfterTerminate(holder.release())
                    .doOnUnsubscribe(holder.release())
                    .subscribe();
            }};
            
            final HttpServer server = new DefaultHttpServer(
                    new AbstractBootstrapCreator(
                    new LocalEventLoopGroup(1), new LocalEventLoopGroup()) {
                @Override
                protected void initializeBootstrap(final ServerBootstrap bootstrap) {
                    bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
                    bootstrap.channel(LocalServerChannel.class);
                }});
            @SuppressWarnings("unused")
            final Subscription testServer = 
                server.defineServer(new LocalAddress(acceptId), features)
                .subscribe(onIncomingTrade);
        return server;
    }
    
    private HttpTestServer createTestServerWith(
            final boolean enableSSL, 
            final String acceptId,
            final Func0<ChannelInboundHandler> newHandler) 
            throws Exception {
        return new HttpTestServer(
                enableSSL, 
                new LocalAddress(acceptId), 
                new LocalEventLoopGroup(1), 
                new LocalEventLoopGroup(),
                LocalServerChannel.class,
                newHandler);
    }
    
    public static byte[] CONTENT;
    public static byte[] OK_RESP;
    static {
        try {
            CONTENT = Resources.asByteSource(
                    Resources.getResource(DefaultSignalClientTestCase.class, "fetchMetadataResp.json")).read();
            OK_RESP = Resources.asByteSource(
                    Resources.getResource(DefaultSignalClientTestCase.class, "okResponse.json")).read();
        } catch (IOException e) {
            LOG.warn("exception when Resources.asByteSource fetchMetadataResp.json/okResponse.json, detail:{}",
                    ExceptionUtils.exception2detail(e));
        }
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
    public void testSignalClient1() throws Exception {
        final HttpTestServer server = createTestServerWith(false, TEST_ADDR,
                new Func0<ChannelInboundHandler> () {
            @Override
            public ChannelInboundHandler call() {
                return new HttpTestServerHandler() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                            throws Exception {
                        if (msg instanceof HttpRequest) {
                            final FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, OK, 
                                    Unpooled.wrappedBuffer(CONTENT));
                            response.headers().set(CONTENT_TYPE, "application/json");
                            response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, response.content().readableBytes());
                            ctx.writeAndFlush(response);
                        }
                    }
                };
            }});

        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        
        final DefaultHttpClient httpclient = new DefaultHttpClient(creator, pool);
//            ,ENABLE_LOGGING);
        
        final DefaultSignalClient signalClient = new DefaultSignalClient(httpclient);
        
        signalClient.registerRequestType(FetchMetadataRequest.class, FetchMetadataResponse.class, 
                null, 
                TO_TEST_ADDR,
                Feature.EMPTY_FEATURES);
        final FetchMetadataResponse resp = 
            ((SignalClient)signalClient).<FetchMetadataResponse>defineInteraction(new FetchMetadataRequest())
            .toBlocking().single();
        System.out.println(resp);
        assertNotNull(resp);
        
        pool.awaitRecycleChannels();
        
//        Thread.sleep(1000000);
        server.stop();
    }
    
    @Test
    public void testSignalClientWithAttachment() throws Exception {
        
        final List<FileUpload> uploads = new ArrayList<>();
        
        final HttpDataFactory HTTP_DATA_FACTORY =
                new DefaultHttpDataFactory(false);
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
                    final FullHttpResponse response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1, OK, 
                            Unpooled.wrappedBuffer(OK_RESP));
                    response.headers().set(CONTENT_TYPE, "application/json");
                    response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, response.content().readableBytes());
                    trade.outboundResponse(Observable.just(response));
                } finally {
                    req.release();
                }
            }};
            
        //  launch test server for attachment send
        final HttpServer server = createTestServerWith(TEST_ADDR, 
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
        
        final TestResponse resp = 
            ((SignalClient)signalClient).<TestResponse>defineInteraction(
                    new TestRequest(), 
                    attachsToSend
                    )
            .toBlocking().single();
        assertNotNull(resp);
        
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
        
        server.close();
    }
}
