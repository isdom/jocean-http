package org.jocean.http.server.impl;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.Assert.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;

import org.jocean.event.api.EventEngine;
import org.jocean.event.extend.Runners;
import org.jocean.event.extend.Services;
import org.jocean.http.client.OutboundFeature;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.client.impl.TestChannelCreator;
import org.jocean.http.server.HttpServer;
import org.jocean.http.server.HttpTestServer;
import org.jocean.http.server.HttpTrade;
import org.jocean.http.server.InboundFeature;
import org.jocean.http.util.RxNettys;
import org.junit.Test;

import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action1;

public class DefaultHttpServerTestCase {
    
    final EventEngine engine = 
            Runners.build(new Runners.Config()
                .objectNamePrefix("demo:type=test")
                .name("demo")
                .timerService(Services.lookupOrCreateTimerService("demo"))
                .executorSource(Services.lookupOrCreateFlowBasedExecutorSource("demo"))
                );

    @Test
    public void testHttpHappyPathOnce() throws Exception {
        final HttpServer server = new DefaultHttpServer(
                engine,
                new AbstractBootstrapCreator(
                new LocalEventLoopGroup(1), new LocalEventLoopGroup()) {
            @Override
            protected void initializeBootstrap(final ServerBootstrap bootstrap) {
                bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
                bootstrap.channel(LocalServerChannel.class);
            }});
        
        final Subscription testServer = 
                server.defineServer(new LocalAddress("test"),
                InboundFeature.APPLY_LOGGING,
                InboundFeature.APPLY_CONTENT_COMPRESSOR)
            .subscribe(new Action1<HttpTrade>() {
                @Override
                public void call(final HttpTrade trade) {
                    trade.request().subscribe(new Subscriber<HttpObject>() {
                        @Override
                        public void onCompleted() {
                            final FullHttpRequest req = trade.retainFullHttpRequest();
                            if (null!=req) {
                                try {
                                    final InputStream is = new ByteBufInputStream(req.content());
                                    final byte[] bytes = new byte[is.available()];
                                    is.read(bytes);
                                    final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, 
                                            Unpooled.wrappedBuffer(bytes));
                                    response.headers().set(CONTENT_TYPE, "text/plain");
                                    response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                                    trade.response(
                                        Observable.<HttpObject>just(response));
                                } catch (IOException e) {
                                    e.printStackTrace();
                                } finally {
                                    req.release();
                                }
                            }
                        }
                        @Override
                        public void onError(Throwable e) {
                        }
                        @Override
                        public void onNext(final HttpObject msg) {
                        }});
                }});
        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator());
        try {
        
            final ByteBuf content = Unpooled.buffer(0);
            content.writeBytes(HttpTestServer.CONTENT);
            final DefaultFullHttpRequest request = 
                    new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", content);
            HttpHeaders.setContentLength(request, content.readableBytes());
            
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.just(request),
                    OutboundFeature.APPLY_LOGGING)
                .compose(RxNettys.objects2httpobjs())
                .map(RxNettys.<HttpObject>retainMap())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
        } finally {
            client.close();
            testServer.unsubscribe();
            server.close();
        }
    }

}
