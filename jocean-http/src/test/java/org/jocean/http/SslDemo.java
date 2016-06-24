package org.jocean.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import rx.Observable;
import rx.functions.Action0;

public class SslDemo {

    private static final Logger LOG =
            LoggerFactory.getLogger(SslDemo.class);
    
    /*
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
    */
    
    public static void main(String[] args) throws Exception {
        
        final CountDownLatch latch = new CountDownLatch(2);
        final SslContext sslCtx = SslContextBuilder.forClient().build();
        final Feature sslfeature = new Feature.ENABLE_SSL(sslCtx);
        
        try (final HttpClient client = new DefaultHttpClient()) {
            {
                final String host = "www.alipay.com";

                final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
                HttpHeaders.setKeepAlive(request, true);
                HttpHeaders.setHost(request, host);

                LOG.debug("send request:{}", request);
              
                final HttpMessageHolder holder = new HttpMessageHolder(-1);
                
                client.defineInteraction(
                    new InetSocketAddress(host, 443), 
                    Observable.just(request),
                    Feature.ENABLE_LOGGING_PREV_SSL,
                    sslfeature
                    )
                .compose(holder.assembleAndHold())
                .subscribe(RxSubscribers.nopOnNext(), RxSubscribers.nopOnError(), new Action0() {
                    @Override
                    public void call() {
                        final FullHttpResponse resp = holder.bindHttpObjects(RxNettys.BUILD_FULL_RESPONSE).call();
                        holder.release().call();
                        
                        try {
                            LOG.info("recv:{}", new String(Nettys.dumpByteBufAsBytes(resp.content()), Charsets.UTF_8));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        resp.release();
                        latch.countDown();
                    }});
                
            }
            {
                final String host = "github.com";

                final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.GET, "/isdom");
                HttpHeaders.setKeepAlive(request, true);
                HttpHeaders.setHost(request, host);

                LOG.debug("send request:{}", request);
              
                final HttpMessageHolder holder = new HttpMessageHolder(-1);
                
                client.defineInteraction(
                    new InetSocketAddress(host, 443), 
                    Observable.just(request),
                    Feature.ENABLE_LOGGING_PREV_SSL,
                    sslfeature
                    )
                .compose(holder.assembleAndHold())
                .subscribe(RxSubscribers.nopOnNext(), RxSubscribers.nopOnError(), new Action0() {
                    @Override
                    public void call() {
                        final FullHttpResponse resp = holder.bindHttpObjects(RxNettys.BUILD_FULL_RESPONSE).call();
                        holder.release().call();
                        
                        try {
                            LOG.info("recv:{}", new String(Nettys.dumpByteBufAsBytes(resp.content()), Charsets.UTF_8));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        resp.release();
                        latch.countDown();
                    }});
            }
            
            latch.await();
        }
    }

}
