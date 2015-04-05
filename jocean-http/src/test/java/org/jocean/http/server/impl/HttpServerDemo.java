package org.jocean.http.server.impl;

import java.util.Iterator;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;

import org.jocean.event.api.EventEngine;
import org.jocean.event.extend.Runners;
import org.jocean.event.extend.Services;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.client.impl.TestChannelCreator;
import org.jocean.http.server.HttpServer;
import org.jocean.http.util.RxNettys;

import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;

public class HttpServerDemo {

    public static void main(final String[] args) throws Exception {
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
        
        final Iterator<HttpObject> itr = 
        server.create(new LocalAddress("test"))
            .doOnNext(new Action1<Channel>() {
                @Override
                public void call(final Channel channel) {
                    InboundFeature.CONTENT_COMPRESSOR.applyTo(channel);
                    InboundFeature.CLOSE_ON_IDLE.applyTo(channel, 180);
                    InboundFeature.LOGGING.applyTo(channel);
                }})
            .flatMap(new Func1<Channel, Observable<HttpObject>>() {
                @Override
                public Observable<HttpObject> call(final Channel channel) {
                    return new DefaultHttpInbound(channel, engine).request();
                }})
            .map(RxNettys.<HttpObject>retainMap())
            .toBlocking().toIterable().iterator();
        
        {
            final ByteBuf content = Unpooled.buffer(0);
            content.writeBytes("test content".getBytes("UTF-8"));
            final DefaultFullHttpRequest request = 
                    new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", content);
            
            final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator());
            client.sendRequest(
                new LocalAddress("test"), 
                Observable.just(request))
                .subscribe();
        }
                
        final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
        System.out.println(new String(bytes, "UTF-8"));
    }
}
