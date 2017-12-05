package org.jocean.http;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;

import java.util.List;
import java.util.concurrent.BlockingQueue;

import org.jocean.http.server.HttpServerBuilder;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.server.impl.AbstractBootstrapCreator;
import org.jocean.http.server.impl.DefaultHttpServerBuilder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.rx.RxActions;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Action2;
import rx.functions.Func0;
import rx.functions.Func1;

public class TestHttpUtil {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(TestHttpUtil.class);
    
    public final static HttpServerBuilder TEST_SERVER_BUILDER = new DefaultHttpServerBuilder(
            new AbstractBootstrapCreator(
            new DefaultEventLoopGroup(1), new DefaultEventLoopGroup()) {
        @Override
        protected void initializeBootstrap(final ServerBootstrap bootstrap) {
            bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
            bootstrap.channel(LocalServerChannel.class);
        }});

    public static Subscription createTestServerWith(final String acceptId,
            final Action2<FullHttpRequest, HttpTrade> onRequestCompleted, final Feature... features) {
        return TEST_SERVER_BUILDER.defineServer(new LocalAddress(acceptId), features)
                .subscribe(new Action1<HttpTrade>() {
                    @Override
                    public void call(final HttpTrade trade) {
                        trade.inbound().compose(RxNettys.message2fullreq(trade)).map(DisposableWrapperUtil.<FullHttpRequest>unwrap())
                                .subscribe(RxActions.bindLastParameter(onRequestCompleted, trade));
                    }
                });
    }
    
    public static Subscription createTestServerWith(final String acceptId, final Action1<HttpTrade> onRequestCompleted,
            final Feature... features) {
        return TEST_SERVER_BUILDER.defineServer(new LocalAddress(acceptId), features)
                .subscribe(new Action1<HttpTrade>() {
                    @Override
                    public void call(final HttpTrade trade) {
                        trade.inbound().last().subscribe(RxSubscribers.ignoreNext(), RxSubscribers.ignoreError(),
                                RxActions.bindParameter(onRequestCompleted, trade));
                    }
                });
    }
    
    public static Subscription createTestServerWith(
            final String acceptId,
            final BlockingQueue<HttpTrade> trades,
            final Feature... features) {
        return TEST_SERVER_BUILDER.defineServer(new LocalAddress(acceptId), features)
            .subscribe(new Action1<HttpTrade>() {
                @Override
                public void call(final HttpTrade trade) {
                    LOG.debug("on trade {}", trade);
                    try {
                        trades.put(trade);
                        LOG.debug("after offer trade {}", trade);
                    } catch (InterruptedException e) {
                        LOG.warn("exception when put trade, detail: {}", ExceptionUtils.exception2detail(e));
                    }
                }});
    }
    
    public static Observable<HttpObject> buildBytesResponse(
            final String contentType, 
            final byte[] content) {
        final FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, OK, 
                Unpooled.wrappedBuffer(content));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        return  Observable.<HttpObject>just(response);
    }
    
    public static Observable<HttpObject> buildByteBufResponse(
            final String contentType, 
            final ByteBuf content) {
        final FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, OK, 
                content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        return  Observable.<HttpObject>just(response);
    }
}
