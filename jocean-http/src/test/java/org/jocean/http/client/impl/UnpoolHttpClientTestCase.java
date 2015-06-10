package org.jocean.http.client.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import java.util.Arrays;
import java.util.Iterator;

import javax.net.ssl.SSLException;

import static org.jocean.http.Feature.ENABLE_LOGGING;
import org.jocean.http.Feature.ENABLE_SSL;
import org.jocean.http.server.HttpTestServer;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.junit.Test;

import rx.Observable;

public class UnpoolHttpClientTestCase {

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

    //  Happy Path
    @Test
    public void testHttpHappyPathKeepAliveNOTReuseConnection() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");

        final TestChannelCreator creator = new TestChannelCreator();
    
        final DefaultHttpClient client = new DefaultHttpClient(
                creator,
                Nettys.unpoolChannels(),
                ENABLE_LOGGING);
        try {
            // first 
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")),
                        ENABLE_LOGGING)
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainMap())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
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
            creator.getChannels().get(1).assertClosed();
        } finally {
            client.close();
            server.stop();
        }
    }
    
    @Test
    public void testHttpsHappyPathKeepAliveNOTReuseConnection() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(true, "test");

        final TestChannelCreator creator = new TestChannelCreator();
        
        final DefaultHttpClient client = new DefaultHttpClient(
                creator,
                Nettys.unpoolChannels(),
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx)
                );
        
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
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
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
            creator.getChannels().get(0).assertClosed();
        } finally {
            client.close();
            server.stop();
        }
    }
}
