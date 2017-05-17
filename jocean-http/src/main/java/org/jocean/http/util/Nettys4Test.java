package org.jocean.http.util;

import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Pair;
import org.jocean.idiom.UnsafeOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoop;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.ResourceLeakTracker;
import rx.Observer;

public class Nettys4Test {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(Nettys4Test.class);

    private static final DefaultEventLoopGroup EVENTLOOP4CLIENT = new DefaultEventLoopGroup(1);
    private static final DefaultEventLoopGroup EVENTLOOP4BOSS = new DefaultEventLoopGroup(1);
    private static final DefaultEventLoopGroup EVENTLOOP4SERVER = new DefaultEventLoopGroup(1);

    private Nettys4Test() {
        throw new IllegalStateException("No instances!");
    }
    
    public static ByteBuf buildByteBuf(final String content) {
        final ByteBuf buf = Unpooled.buffer(0);
        buf.writeBytes(content.getBytes(CharsetUtil.UTF_8));
        return buf;
    }
    
    public static HttpContent[] buildContentArray(final byte[] srcBytes, final int bytesPerContent) {
        final List<HttpContent> contents = new ArrayList<>();
        
        int startInBytes = 0;
        while (startInBytes < srcBytes.length) {
            final ByteBuf content = Unpooled.buffer(bytesPerContent);
            final int len = Math.min(bytesPerContent, srcBytes.length-startInBytes);
            
            content.writeBytes(srcBytes, startInBytes, len);
            startInBytes += len;
            
            if (LOG.isDebugEnabled()) {
                LOG.debug("build content: {}@{}",
                        content,
                        UnsafeOp.toAddress(content));
            }
            
            contents.add(new DefaultHttpContent(content));
        }
        return contents.toArray(new HttpContent[0]);
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
                        Nettys.applyHandler(APPLY.LOGGING, ch.pipeline());
                        Nettys.applyHandler(APPLY.HTTPCLIENT, ch.pipeline());
                    }})
                .remoteAddress(new LocalAddress(addr));

        final Channel acceptorChannel = new ServerBootstrap()
                .group(EVENTLOOP4BOSS, EVENTLOOP4SERVER)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(final Channel ch) throws Exception {
                        Nettys.applyHandler(APPLY.LOGGING, ch.pipeline());
                        Nettys.applyHandler(APPLY.HTTPSERVER, ch.pipeline());
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
    
    public static Channel dummyChannel() {
        return new Channel() {

            @Override
            public <T> Attribute<T> attr(AttributeKey<T> key) {
                return null;
            }

            @Override
            public int compareTo(Channel o) {
                return 0;
            }

            @Override
            public EventLoop eventLoop() {
                return null;
            }

            @Override
            public Channel parent() {
                return null;
            }

            @Override
            public ChannelConfig config() {
                return null;
            }

            @Override
            public boolean isOpen() {
                return false;
            }

            @Override
            public boolean isRegistered() {
                return false;
            }

            @Override
            public boolean isActive() {
                return false;
            }

            @Override
            public ChannelMetadata metadata() {
                return null;
            }

            @Override
            public SocketAddress localAddress() {
                return null;
            }

            @Override
            public SocketAddress remoteAddress() {
                return null;
            }

            @Override
            public ChannelFuture closeFuture() {
                return null;
            }

            @Override
            public boolean isWritable() {
                return false;
            }

            @Override
            public Unsafe unsafe() {
                return null;
            }

            @Override
            public ChannelPipeline pipeline() {
                return null;
            }

            @Override
            public ByteBufAllocator alloc() {
                return null;
            }

            @Override
            public ChannelPromise newPromise() {
                return null;
            }

            @Override
            public ChannelProgressivePromise newProgressivePromise() {
                return null;
            }

            @Override
            public ChannelFuture newSucceededFuture() {
                return null;
            }

            @Override
            public ChannelFuture newFailedFuture(Throwable cause) {
                return null;
            }

            @Override
            public ChannelPromise voidPromise() {
                return null;
            }

            @Override
            public ChannelFuture bind(SocketAddress localAddress) {
                return null;
            }

            @Override
            public ChannelFuture connect(SocketAddress remoteAddress) {
                return null;
            }

            @Override
            public ChannelFuture connect(SocketAddress remoteAddress,
                    SocketAddress localAddress) {
                return null;
            }

            @Override
            public ChannelFuture disconnect() {
                return null;
            }

            @Override
            public ChannelFuture close() {
                return null;
            }

            @Override
            public ChannelFuture deregister() {
                return null;
            }

            @Override
            public ChannelFuture bind(SocketAddress localAddress,
                    ChannelPromise promise) {
                return null;
            }

            @Override
            public ChannelFuture connect(SocketAddress remoteAddress,
                    ChannelPromise promise) {
                return null;
            }

            @Override
            public ChannelFuture connect(SocketAddress remoteAddress,
                    SocketAddress localAddress, ChannelPromise promise) {
                return null;
            }

            @Override
            public ChannelFuture disconnect(ChannelPromise promise) {
                return null;
            }

            @Override
            public ChannelFuture close(ChannelPromise promise) {
                return null;
            }

            @Override
            public ChannelFuture deregister(ChannelPromise promise) {
                return null;
            }

            @Override
            public Channel read() {
                return null;
            }

            @Override
            public ChannelFuture write(Object msg) {
                return null;
            }

            @Override
            public ChannelFuture write(Object msg, ChannelPromise promise) {
                return null;
            }

            @Override
            public Channel flush() {
                return null;
            }

            @Override
            public ChannelFuture writeAndFlush(Object msg,
                    ChannelPromise promise) {
                return null;
            }

            @Override
            public ChannelFuture writeAndFlush(Object msg) {
                return null;
            }
            
            @Override
            public <T> boolean hasAttr(AttributeKey<T> key) {
                return false;
            }

            @Override
            public ChannelId id() {
                return null;
            }

            @Override
            public long bytesBeforeUnwritable() {
                return 0;
            }

            @Override
            public long bytesBeforeWritable() {
                return 0;
            }};
    }
    
    public static String dumpByteBufLeakRecords(final ByteBuf buf) {
        try {
            final Field field = buf.getClass().getDeclaredField("leak");
            if (null != field) {
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                final ResourceLeakTracker<ByteBuf> leak = (ResourceLeakTracker<ByteBuf>)field.get(buf);
                return leak.toString();
            }
        } catch (Exception e) {
            LOG.warn("exception when access {}'s leak field, detail: {}",
                    buf, ExceptionUtils.exception2detail(e));
        }
        return null;
    }
}
