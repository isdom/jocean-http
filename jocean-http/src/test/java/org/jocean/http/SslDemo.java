package org.jocean.http;

import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Iterator;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.Feature;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.functions.Func1;

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
        
//        https://github.com/isdom
            
        try (final HttpClient client = new DefaultHttpClient()) {
            final Iterator<HttpObject> itr = 
                client.sendRequest(new InetSocketAddress("www.alipay.com", 443), 
                    Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/")),
                    Feature.EnableLOG,
                    Feature.EnableSSL)
                .map(new Func1<HttpObject, HttpObject>() {
                    @Override
                    public HttpObject call(final HttpObject obj) {
                        //    retain obj for blocking
                        return ReferenceCountUtil.retain(obj);
                    }})
                .toBlocking().toIterable().iterator();
            
            LOG.info("recv:{}", new String(responseAsBytes(itr), "GBK"));
        }
    }

}
