package org.jocean.http;

import org.jocean.http.server.HttpServerBuilder;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.server.impl.AbstractBootstrapCreator;
import org.jocean.http.server.impl.DefaultHttpServerBuilder;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.rx.RxActions;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Action2;
import rx.functions.Func0;

public class TestHttpUtil {
    private final static HttpServerBuilder TEST_SERVER_BUILDER = new DefaultHttpServerBuilder(
            new AbstractBootstrapCreator(
            new LocalEventLoopGroup(1), new LocalEventLoopGroup()) {
        @Override
        protected void initializeBootstrap(final ServerBootstrap bootstrap) {
            bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
            bootstrap.channel(LocalServerChannel.class);
        }});

    public static Subscription createTestServerWith(
            final String acceptId,
            final Action2<Func0<FullHttpRequest>, HttpTrade> onRequestCompleted,
            final Feature... features) {
        return TEST_SERVER_BUILDER.defineServer(new LocalAddress(acceptId), features)
            .subscribe(new Action1<HttpTrade>() {
                @Override
                public void call(final HttpTrade trade) {
                    final HttpMessageHolder holder = new HttpMessageHolder(0);
                    trade.inboundRequest()
                        .compose(holder.assembleAndHold())
                        .doOnCompleted(RxActions.bindParameter(onRequestCompleted,
                                holder.bindHttpObjects(RxNettys.BUILD_FULL_REQUEST), 
                                trade))
                        .doAfterTerminate(holder.release())
                        .doOnUnsubscribe(holder.release())
                        .subscribe();
                }});
    }
    
    public static Subscription createTestServerWith(
            final String acceptId,
            final Action1<HttpTrade> onRequestCompleted,
            final Feature... features) {
        return TEST_SERVER_BUILDER.defineServer(new LocalAddress(acceptId), features)
            .subscribe(new Action1<HttpTrade>() {
                @Override
                public void call(final HttpTrade trade) {
                    trade.inboundRequest()
                        .doOnCompleted(RxActions.bindParameter(onRequestCompleted, trade))
                        .subscribe();
                }});
    }
}
