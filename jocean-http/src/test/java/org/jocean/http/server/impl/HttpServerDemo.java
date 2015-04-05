package org.jocean.http.server.impl;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;

import java.io.IOException;
import java.util.Iterator;

import org.jocean.event.api.EventEngine;
import org.jocean.event.extend.Runners;
import org.jocean.event.extend.Services;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.client.impl.TestChannelCreator;
import org.jocean.http.server.HttpServer;
import org.jocean.http.util.RxNettys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;

public class HttpServerDemo {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(HttpServerDemo.class);

    public static void main(final String[] args) throws Exception {
//        SelfSignedCertificate ssc = new SelfSignedCertificate();
//        final SslContext sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());

        final EventEngine engine = 
                Runners.build(new Runners.Config()
                    .objectNamePrefix("demo:type=test")
                    .name("demo")
                    .timerService(Services.lookupOrCreateTimerService("demo"))
                    .executorSource(Services.lookupOrCreateFlowBasedExecutorSource("demo"))
                    );
        
        //  create for LocalChannel
        @SuppressWarnings("resource")
        final HttpServer server = new DefaultHttpServer(new AbstractBootstrapCreator(
                new LocalEventLoopGroup(1), new LocalEventLoopGroup()) {
            @Override
            protected void initializeBootstrap(final ServerBootstrap bootstrap) {
                bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
                bootstrap.channel(LocalServerChannel.class);
            }});
        
        final Subscription subscription = 
        server.create(new LocalAddress("test"))
            .doOnNext(new Action1<Channel>() {
                @Override
                public void call(final Channel channel) {
                    InboundFeature.CONTENT_COMPRESSOR.applyTo(channel);
//                    InboundFeature.ENABLE_SSL.applyTo(channel, sslCtx);
                    InboundFeature.CLOSE_ON_IDLE.applyTo(channel, 10);
                    InboundFeature.LOGGING.applyTo(channel);
                }})
            .subscribe(new Action1<Channel>() {
                @Override
                public void call(final Channel channel) {
                  final Iterator<HttpObject> itr = 
                    new DefaultHttpInbound(channel, engine).request()
                    .map(RxNettys.<HttpObject>retainMap())
                    .toBlocking().toIterable().iterator();
                  
                  new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                            LOG.info("recv {}", new String(bytes, "UTF-8"));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }}).start();
                }});
        
        Services.lookupOrCreateTimerService("demo").schedule(new Runnable() {
            @Override
            public void run() {
                subscription.unsubscribe();
            }}, 10 * 1000);
        
        while (true) {
            final ByteBuf content = Unpooled.buffer(0);
            content.writeBytes("test content".getBytes("UTF-8"));
            final DefaultFullHttpRequest request = 
                    new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", content);
            HttpHeaders.setContentLength(request, content.readableBytes());
            
            @SuppressWarnings("resource")
            final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator());
            client.sendRequest(
                new LocalAddress("test"), 
                Observable.just(request)
//                HttpFeature.EnableLOG,
//                HttpFeature.EnableSSL
                )
                .subscribe();
            Thread.sleep(1000);
        }
    }
}
