package org.jocean.http.server.impl;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.net.InetSocketAddress;

import org.jocean.http.server.HttpServer;
import org.jocean.http.server.HttpServer.HttpTrade;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action1;

public class HttpServerForCloopen {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(HttpServerForCloopen.class);

    public static void main(final String[] args) throws Exception {
        
        @SuppressWarnings("resource")
        final HttpServer server = new DefaultHttpServer();
        
        @SuppressWarnings("unused")
        final Subscription subscription = 
        server.defineServer(new InetSocketAddress("0.0.0.0", 8888))
            .subscribe(new Action1<HttpTrade>() {
                @Override
                public void call(final HttpTrade trade) {
                    final HttpMessageHolder holder = new HttpMessageHolder(0);
                    trade.inboundRequest().compose(holder.assembleAndHold()).subscribe(new Subscriber<HttpObject>() {
                        @Override
                        public void onCompleted() {
                            final FullHttpRequest req = holder.bindHttpObjects(RxNettys.BUILD_FULL_REQUEST).call();
                            if (null!=req) {
                                try {
                                    final byte[] bytes = Nettys.dumpByteBufAsBytes(req.content());
                                    final String reqcontent = bytes.length > 0 ? new String(bytes, Charsets.UTF_8) : "empty";
                                    LOG.debug("receive HttpRequest: {}\ncontent:\n{}", 
                                            req, reqcontent);
                                } catch (Exception e) {
                                    LOG.warn("exception when dump http req content, detail:{}", 
                                            ExceptionUtils.exception2detail(e));
                                } finally {
                                    req.release();
                                }
                            }
                            byte[] bytes = new String("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><statuscode>000000</statuscode></Response>").getBytes(Charsets.UTF_8);

                            final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, 
                                    Unpooled.wrappedBuffer(bytes));
                            response.headers().set(CONTENT_TYPE, "text/plain");
                            response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                            trade.outboundResponse(Observable.<HttpObject>just(response));
                        }
                        @Override
                        public void onError(Throwable e) {
                        }
                        @Override
                        public void onNext(final HttpObject msg) {
                        }});
                }});
    }
}
