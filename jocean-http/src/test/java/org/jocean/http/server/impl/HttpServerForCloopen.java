package org.jocean.http.server.impl;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.net.InetSocketAddress;

import org.jocean.http.MessageUtil;
import org.jocean.http.server.HttpServerBuilder;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func1;

public class HttpServerForCloopen {

    private static final Logger LOG =
            LoggerFactory.getLogger(HttpServerForCloopen.class);

    public static void main(final String[] args) throws Exception {

        @SuppressWarnings("resource")
        final HttpServerBuilder server = new DefaultHttpServerBuilder();

        @SuppressWarnings("unused")
        final Subscription subscription =
        server.defineServer(new InetSocketAddress("0.0.0.0", 8888))
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
                                        final byte[] bytes = Nettys.dumpByteBufAsBytes(fullreq.content());
                                        final String reqcontent = bytes.length > 0 ? new String(bytes, Charsets.UTF_8) : "empty";
                                        LOG.debug("receive HttpRequest: {}\ncontent:\n{}",
                                                fullreq, reqcontent);
                                    } catch (final Exception e) {
                                        LOG.warn("exception when dump http req content, detail:{}",
                                                ExceptionUtils.exception2detail(e));
                                    }
                                    final byte[] bytes = new String("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><statuscode>000000</statuscode></Response>").getBytes(Charsets.UTF_8);

                                    final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK,
                                            Unpooled.wrappedBuffer(bytes));
                                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                                    return response;
                                }
                            }));
                }});
    }
}
