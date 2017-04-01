package org.jocean.http;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.HttpInitiator0;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Func1;

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
        
        final SslContext sslCtx = SslContextBuilder.forClient().build();
        final Feature sslfeature = new Feature.ENABLE_SSL(sslCtx);
        
        try (final HttpClient client = new DefaultHttpClient()) {
            {
                final String host = "www.sina.com.cn";

                final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
                HttpUtil.setKeepAlive(request, true);
                request.headers().set(HttpHeaderNames.HOST, host);

                LOG.debug("send request:{}", request);
              
                LOG.info("recv:{}", sendRequestAndRecv(client, request, host, sslfeature));
            }
            {
                final String host = "www.alipay.com";

                final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
                HttpUtil.setKeepAlive(request, true);
                request.headers().set(HttpHeaderNames.HOST, host);

                LOG.debug("send request:{}", request);
              
                LOG.info("recv:{}", sendRequestAndRecv(client, request, host, sslfeature));
            }
            {
                final String host = "github.com";

                final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.GET, "/isdom");
                HttpUtil.setKeepAlive(request, true);
                request.headers().set(HttpHeaderNames.HOST, host);

                LOG.debug("send request:{}", request);
              
                LOG.info("recv:{}", sendRequestAndRecv(client, request, host, sslfeature));
            }
        }
    }

    private static String sendRequestAndRecv(final HttpClient client,
            final DefaultFullHttpRequest request, final String host,
            final Feature sslfeature) {
        return client.initiator0().remoteAddress(new InetSocketAddress(host, 80 /*443*/))
        .feature(/*sslfeature,*/ Feature.ENABLE_LOGGING_OVER_SSL).build()
        .flatMap(new Func1<HttpInitiator0, Observable<String>>() {
            @Override
            public Observable<String> call(final HttpInitiator0 initiator) {
                final HttpMessageHolder holder = new HttpMessageHolder();
                initiator.doOnTerminate(holder.release());
                return initiator.defineInteraction(Observable.just(request))
                .compose(holder.assembleAndHold())
                .last().map(new Func1<HttpObject, String>() {
                    @Override
                    public String call(HttpObject t) {
                        final FullHttpResponse resp = holder.httpMessageBuilder(RxNettys.BUILD_FULL_RESPONSE).call();
                        try {
                            return new String(Nettys.dumpByteBufAsBytes(resp.content()), Charsets.UTF_8);
                        } catch (IOException e) {
                            e.printStackTrace();
                            return null;
                        } finally {
                            resp.release();
                        }
                    }}).doAfterTerminate(new Action0() {
                        @Override
                        public void call() {
                            initiator.close();
                        }});
            }})
        .toBlocking().single();
    }

}
