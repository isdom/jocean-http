package org.jocean.http.rosa;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jocean.http.Feature.ENABLE_LOGGING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.Callable;

import javax.net.ssl.SSLException;

import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.client.impl.TestChannelCreator;
import org.jocean.http.server.HttpTestServer;
import org.jocean.http.server.HttpTestServerHandler;
import org.jocean.http.util.RxNettys;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;

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
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import rx.Observable;

public class DefaultSignalClientTestCase {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultSignalClientTestCase.class);

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
    
    public static byte[] CONTENT;
    static {
        try {
            CONTENT = Resources.asByteSource(
                    Resources.getResource(DefaultSignalClientTestCase.class, "fetchMetadataResp.json")).read();
        } catch (IOException e) {
            e.printStackTrace();
        }
        
    }
    
    @Test
    public void testSignalClient1() throws Exception {
        final HttpTestServer server = createTestServerWith(false, "test",
                new Callable<ChannelInboundHandler> () {
            @Override
            public ChannelInboundHandler call() throws Exception {
                return new HttpTestServerHandler() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                            throws Exception {
                        if (msg instanceof HttpRequest) {
                            final FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, OK, 
                                    Unpooled.wrappedBuffer(CONTENT));
                            response.headers().set(CONTENT_TYPE, "application/json");
                            //  missing Content-Length
                            response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, response.content().readableBytes());
                            ctx.writeAndFlush(response);
                        }
                    }
                };
            }});

        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING);
        try {
            final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
//            request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
            
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.just(request))
                .compose(RxNettys.objects2httpobjs())
                .map(RxNettys.<HttpObject>retainMap())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            System.out.println(new String(bytes, Charsets.UTF_8));
            assertTrue(Arrays.equals(bytes, CONTENT));
            assertEquals(1, creator.getChannels().size());
        } finally {
            client.close();
            server.stop();
        }
    }
}
