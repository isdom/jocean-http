package org.jocean.http.rosa;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.URI;

import javax.net.ssl.SSLException;

import org.jocean.http.Feature;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.client.impl.TestChannelCreator;
import org.jocean.http.client.impl.TestChannelPool;
import org.jocean.http.rosa.impl.DefaultSignalClient;
import org.jocean.http.server.HttpTestServer;
import org.jocean.http.server.HttpTestServerHandler;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.Resources;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import rx.functions.Func0;
import rx.functions.Func1;

public class DefaultSignalClientTestCase {

    private static final String TEST_ADDR = "test";

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
    static {
        try {
            CONTENT = Resources.asByteSource(
                    Resources.getResource(DefaultSignalClientTestCase.class, "fetchMetadataResp.json")).read();
        } catch (IOException e) {
            e.printStackTrace();
        }
        
    }
    
    final Func1<URI, SocketAddress> TO_TEST_ADDR = new Func1<URI, SocketAddress>() {
        @Override
        public SocketAddress call(final URI uri) {
            return new LocalAddress(TEST_ADDR);
        }};
        
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
        fail("Not Test");
    }
}
