package org.jocean.http.client.impl;

import static org.jocean.http.Feature.ENABLE_LOGGING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.jocean.http.Feature;
import org.jocean.http.Feature.ENABLE_SSL;
import org.jocean.http.TestHttpUtil;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.junit.Test;

import io.netty.channel.local.LocalAddress;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
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

public class UnpoolHttpClientTestCase {

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

    private Action1<HttpTrade> responseBy(
            final String contentType, 
            final byte[] bodyAsBytes) {
        return new Action1<HttpTrade>() {
            @Override
            public void call(final HttpTrade trade) {
                trade.outbound(TestHttpUtil.buildBytesResponse(contentType, bodyAsBytes));
            }};
    }
    
    /* // TODO using initiator
    //  Happy Path
    @Test
    public void testHttpHappyPathKeepAliveNOTReuseConnection() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", CONTENT),
                ENABLE_LOGGING);

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
                        new LocalAddress(testAddr), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")),
                        ENABLE_LOGGING)
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, CONTENT));
            }
            assertEquals(2, creator.getChannels().size());
            creator.getChannels().get(1).assertClosed(1);
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    
    @Test
    public void testHttpsHappyPathKeepAliveNOTReuseConnection() throws Exception {
        final String testAddr = UUID.randomUUID().toString();
        final Subscription server = TestHttpUtil.createTestServerWith(testAddr, 
                responseBy("text/plain", CONTENT),
                enableSSL4ServerWithSelfSigned(),
                Feature.ENABLE_LOGGING_OVER_SSL);

        final TestChannelCreator creator = new TestChannelCreator();
        
        final DefaultHttpClient client = new DefaultHttpClient(
                creator,
                Nettys.unpoolChannels(),
                Feature.ENABLE_LOGGING_OVER_SSL,
                enableSSL4Client()
                );
        
        try {
            // first 
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress(testAddr), 
                        Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, CONTENT));
            }
            assertEquals(2, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.unsubscribe();
        }
    }
    */
}
