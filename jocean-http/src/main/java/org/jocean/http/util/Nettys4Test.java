package org.jocean.http.util;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.jocean.idiom.Pair;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.LastHttpContent;
import rx.Observer;

public class Nettys4Test {
    private static final LocalEventLoopGroup EVENTLOOP4CLIENT = new LocalEventLoopGroup(1);
    private static final LocalEventLoopGroup EVENTLOOP4BOSS = new LocalEventLoopGroup(1);
    private static final LocalEventLoopGroup EVENTLOOP4SERVER = new LocalEventLoopGroup(1);

    private Nettys4Test() {
        throw new IllegalStateException("No instances!");
    }
    
    public static Pair<Channel,Channel> createLocalConnection4Http(final String addr) 
            throws Exception {
        final BlockingQueue<Channel> serverChannels = 
                new ArrayBlockingQueue<Channel>(1);
        final Bootstrap clientbootstrap = new Bootstrap()
                .group(Nettys4Test.EVENTLOOP4CLIENT)
                .channel(LocalChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(final Channel ch) throws Exception {
                        APPLY.LOGGING.applyTo(ch.pipeline());
                        APPLY.HTTPCLIENT.applyTo(ch.pipeline());
                    }})
                .remoteAddress(new LocalAddress(addr));

        final Channel acceptorChannel = new ServerBootstrap()
                .group(EVENTLOOP4BOSS, EVENTLOOP4SERVER)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(final Channel ch) throws Exception {
                        APPLY.LOGGING.applyTo(ch.pipeline());
                        APPLY.HTTPSERVER.applyTo(ch.pipeline());
                        serverChannels.offer(ch);
                    }})
                .localAddress(new LocalAddress(addr))
                .bind()
                .sync()
                .channel();
        
        try {
            final Channel client = clientbootstrap.connect().sync().channel();
            final Channel server = serverChannels.take();
            
            return Pair.of(client, server);
        } finally {
            acceptorChannel.close().sync();
        }
    }

    public static void emitHttpObjects(final Observer<? super HttpObject> observer,
            final HttpObject... objs) {
        for (HttpObject obj : objs) {
            observer.onNext(obj);
            if (obj instanceof LastHttpContent) {
                observer.onCompleted();
            }
        }
    }
}
