package org.jocean.http;

import java.net.InetSocketAddress;
import java.util.List;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.util.Nettys;
import org.jocean.idiom.DisposableWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import rx.Observable;
import rx.functions.Func1;

public class SslDemo {

    private static final Logger LOG =
            LoggerFactory.getLogger(SslDemo.class);

    public static void main(final String[] args) throws Exception {

        final SslContext sslCtx = SslContextBuilder.forClient().build();
        final Feature sslfeature = new Feature.ENABLE_SSL(sslCtx);

        try (final HttpClient client = new DefaultHttpClient()) {
            {
                final String host =
                        "www.sina.com.cn";
//                        "www.greatdata.com.cn";

                final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
//                HttpUtil.setKeepAlive(request, true);
                request.headers().set(HttpHeaderNames.HOST, host);

                LOG.debug("send request:{}", request);

                final String content = sendRequestAndRecv(client,
                		host,
                		443,
//                		80,
                		request,
                        sslfeature
//                        Feature.ENABLE_LOGGING,
//                        Feature.ENABLE_LOGGING_OVER_SSL
//                        Feature.ENABLE_COMPRESSOR
                        );
//                LOG.info("recv:{}", content);
            }
            /*
            {
                final String host = "www.alipay.com";

                final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
                HttpUtil.setKeepAlive(request, true);
                request.headers().set(HttpHeaderNames.HOST, host);

                LOG.debug("send request:{}", request);

                LOG.info("recv:{}", sendRequestAndRecv(client, request, host, sslfeature));
            }
            */
            /*
            {
                final String host = "github.com";

                final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_0, HttpMethod.GET, "/isdom");
                HttpUtil.setKeepAlive(request, true);
                request.headers().set(HttpHeaderNames.HOST, host);

                LOG.debug("send request:{}", request);

                LOG.info("recv:{}", sendRequestAndRecv(client, request, host, sslfeature));
            }
            */
        }
    }

    private static String sendRequestAndRecv(final HttpClient client,
            final String host,
            final int port,
            final DefaultFullHttpRequest request,
            final Feature... features) {
        final HttpInitiator initiator =
                client.initiator().remoteAddress(new InetSocketAddress(host, port))
                .feature(features)
                .build()
                .toBlocking()
                .single();

        final TrafficCounter counter = initiator.traffic();
        final String resp1 = sendAndRecv(initiator, request).toBlocking().single();
        LOG.debug("1 interaction: {}", resp1);

        final String resp2 = sendAndRecv(initiator, request).toBlocking().single();
        LOG.debug("2 interaction: {}", resp2);

        LOG.debug("upload {}/download {}", counter.outboundBytes(), counter.inboundBytes());
        initiator.close();
        return resp1 /* + resp2 */;

//        .flatMap(new Func1<HttpInitiator, Observable<String>>() {
//            @Override
//            public Observable<String> call(final HttpInitiator initiator) {
//                final TrafficCounter counter = initiator.enable(APPLY.TRAFFICCOUNTER);
//
//                final Observable<String> respContent = sendAndRecv(initiator, request);
//                return respContent.doOnUnsubscribe(new Action0() {
//                    @Override
//                    public void call() {
//                        LOG.debug("upload {}/download {}", counter.outboundBytes(), counter.inboundBytes());
////                        initiator.close();
//                    }});
//            }})
//        .toBlocking().single();
    }

    private static Observable<String> sendAndRecv(
            final HttpInitiator initiator,
            final DefaultFullHttpRequest request) {
        final Observable<? extends FullMessage<HttpResponse>> resp = initiator.defineInteraction(Observable.just(request));
        return resp.flatMap(new Func1<FullMessage<HttpResponse>, Observable<DisposableWrapper<? extends ByteBuf>>>() {
            @Override
            public Observable<DisposableWrapper<? extends ByteBuf>> call(final FullMessage<HttpResponse> fullresp) {
                return fullresp.body().flatMap(new Func1<MessageBody, Observable<DisposableWrapper<? extends ByteBuf>>>() {
                    @Override
                    public Observable<DisposableWrapper<? extends ByteBuf>> call(final MessageBody body) {
                        return body.content().compose(MessageUtil.AUTOSTEP2DWB);
                    }});
            }})
        .toList()
        .map(new Func1<List<DisposableWrapper<? extends ByteBuf>>, String>() {
            @Override
            public String call(final List<DisposableWrapper<? extends ByteBuf>> dwbs) {
                final ByteBuf content = Nettys.dwbs2buf(dwbs);
                try {
                    return MessageUtil.parseContentAsString(MessageUtil.contentAsInputStream(content));
                } finally {
                    content.release();
                }
            }
        });
    }

}
