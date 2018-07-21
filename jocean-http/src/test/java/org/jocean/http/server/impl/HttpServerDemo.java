package org.jocean.http.server.impl;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import org.jocean.http.Feature;
import org.jocean.http.MessageUtil;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.client.impl.TestChannelCreator;
import org.jocean.http.server.HttpServerBuilder;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapperUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func1;

public class HttpServerDemo {

    private static final Logger LOG =
            LoggerFactory.getLogger(HttpServerDemo.class);

    public static void main(final String[] args) throws Exception {
        final SelfSignedCertificate ssc = new SelfSignedCertificate();
        final SslContext sslCtx = // SslContext.newServerContext(ssc.certificate(), ssc.privateKey());
                SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();

        //  create for LocalChannel
        @SuppressWarnings("resource")
        final HttpServerBuilder server = new DefaultHttpServerBuilder(
                new AbstractBootstrapCreator(
                new DefaultEventLoopGroup(1), new DefaultEventLoopGroup()) {
            @Override
            protected void initializeBootstrap(final ServerBootstrap bootstrap) {
                bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
                bootstrap.channel(LocalServerChannel.class);
            }},
            Feature.ENABLE_LOGGING,
            new Feature.ENABLE_SSL(sslCtx)
            );

        @SuppressWarnings("unused")
        final Subscription subscription = server.defineServer(new LocalAddress("test"))
                .subscribe(new Action1<HttpTrade>() {
                    @Override
                    public void call(final HttpTrade trade) {
                        trade.outbound(trade.inbound()
                                .compose(MessageUtil.AUTOSTEP2DWH)
                                .compose(RxNettys.message2fullreq(trade))
                                .map(DisposableWrapperUtil.<FullHttpRequest>unwrap()).map(new Func1<FullHttpRequest, HttpObject>() {
                                    @Override
                                    public HttpObject call(final FullHttpRequest fullreq) {
                                        try {
                                            final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK,
                                                    Unpooled.wrappedBuffer(
                                                            Nettys.dumpByteBufAsBytes(fullreq.content())));
                                            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                                            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                                            return response;
                                        } catch (final Exception e) {
                                            throw new RuntimeException(e);
                                        }
                                    }
                                }));
                    }
                });

        @SuppressWarnings("resource")
        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(),
                Feature.ENABLE_LOGGING,
                new Feature.ENABLE_SSL(SslContextBuilder.forClient().build()));

        while (true) {
            final ByteBuf content = Unpooled.buffer(0);
            content.writeBytes("test content".getBytes("UTF-8"));
            final DefaultFullHttpRequest request =
                    new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", content);
            HttpUtil.setContentLength(request, content.readableBytes());

            /* // TODO using initiator
            final Iterator<HttpObject> itr =
                client.defineInteraction(
                new LocalAddress("test"),
                Observable.just(request))
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();

            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            LOG.info("recv Response: {}", new String(bytes, "UTF-8"));
            */

            Thread.sleep(1000);
        }
    }
}
