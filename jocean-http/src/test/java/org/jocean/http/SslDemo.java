package org.jocean.http;

import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Iterator;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.Outbound;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.util.RxNettys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;

public class SslDemo {

    private static final Logger LOG =
            LoggerFactory.getLogger(SslDemo.class);
    
    private static byte[] responseAsBytes(final Iterator<HttpObject> itr)
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
    
    public static void main(String[] args) throws Exception {
        
        final SslContext sslCtx = SslContext.newClientContext(InsecureTrustManagerFactory.INSTANCE);
//        https://github.com/isdom
//        final String host = "www.alipay.com";
        final String host = "www.csdn.net";
        final String cs = "UTF-8";
            
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        HttpHeaders.setKeepAlive(request, true);
        HttpHeaders.setHost(request, host);
        
        LOG.debug("send request:{}", request);
        try (final HttpClient client = new DefaultHttpClient()) {
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new InetSocketAddress(host, 443), 
//                        new InetSocketAddress("58.215.107.207", 443), 
                        Observable.just(request),
                        Feature.ENABLE_LOGGING,
                        new Feature.ENABLE_SSL(sslCtx)
                        )
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainMap())
                    .toBlocking().toIterable().iterator();
                
                LOG.info("recv:{}", new String(responseAsBytes(itr), cs));
            }
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new InetSocketAddress(host, 443), 
//                        new InetSocketAddress("58.215.107.207", 443), 
                        Observable.just(request),
                        Feature.ENABLE_LOGGING,
                        new Feature.ENABLE_SSL(sslCtx)
                        )
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainMap())
                    .toBlocking().toIterable().iterator();
                
                LOG.info("recv 2nd:{}", new String(responseAsBytes(itr), cs));
            }
        }
    }

}
