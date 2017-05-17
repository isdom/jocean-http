package org.jocean.http.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.util.Iterator;
import java.util.Map.Entry;

import org.jocean.http.client.impl.AbstractChannelPool;
import org.jocean.http.client.impl.ChannelPool;
import org.jocean.idiom.AnnotationWrapper;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.PairedVisitor;
import org.jocean.idiom.UnsafeOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ServerChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCounted;
import rx.functions.Func2;

public class Nettys {
    private static final Logger LOG =
            LoggerFactory.getLogger(Nettys.class);

    private Nettys() {
        throw new IllegalStateException("No instances!");
    }
    
    public interface ChannelAware {
        public void setChannel(final Channel channel);
    }
    
    public interface ServerChannelAware {
        public void setServerChannel(final ServerChannel serverChannel);
    }
    
    public static ChannelPool unpoolChannels() {
        return new AbstractChannelPool() {
            @Override
            protected Channel findActiveChannel(final SocketAddress address) {
                return null;
            }
            
            @Override
            public boolean recycleChannel(final Channel channel) {
                channel.close();
                return false;
            }
        };
    }
    
    public static PairedVisitor<Object> _NETTY_REFCOUNTED_GUARD = new PairedVisitor<Object>() {
        @Override
        public void visitBegin(final Object obj) {
            if ( obj instanceof ReferenceCounted ) {
                ((ReferenceCounted)obj).retain();
            }
        }
        @Override
        public void visitEnd(final Object obj) {
            if ( obj instanceof ReferenceCounted ) {
                ((ReferenceCounted)obj).release();
            }
        }
        @Override
        public String toString() {
            return "NettyUtils._NETTY_REFCOUNTED_GUARD";
        }};
        
    public static interface ToOrdinal extends Func2<String,ChannelHandler,Integer> {}
    
    public static ChannelHandler insertHandler(
            final ChannelPipeline pipeline, 
            final String name, 
            final ChannelHandler handler,
            final ToOrdinal toOrdinal) {
        final int toInsertOrdinal = toOrdinal.call(name, handler);
        final Iterator<Entry<String,ChannelHandler>> itr = pipeline.iterator();
        while (itr.hasNext()) {
            final Entry<String,ChannelHandler> entry = itr.next();
            try {
                final int order = toOrdinal.call(entry.getKey(), entry.getValue())
                        - toInsertOrdinal;
                if (order==0) {
                    //  order equals, same ordered handler already added, 
                    //  so replaced by new handler
                    LOG.warn("insertHandler: channel ({}) handler order({}) exist, old handler {}/{} will be replace by new handler {}/{}.",
                            pipeline.channel(), toInsertOrdinal, 
                            entry.getKey(), entry.getValue(),
                            name, handler);
                    pipeline.replace(entry.getValue(), name, handler);
                    return handler;
                }
                if (order < 0) {
                    // current handler's name less than name, continue compare
                    continue;
                }
                if (order > 0) {
                    //  OK, add handler before current handler
                    pipeline.addBefore(entry.getKey(), name, handler);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("insertHandler: channel ({}) insert handler({}/{}) before({}).", 
                                pipeline.channel(), name, handler, entry.getKey());
                    }
                    return handler;
                }
            } catch (IllegalArgumentException e) {
                // throw by toOrdinal.call, so just ignore this entry and continue
                LOG.warn("insertHandler: channel ({}) insert handler named({}), meet handler entry:{}, which is !NOT! ordinal, just ignore", 
                        pipeline.channel(), name, entry);
                LOG.warn("call from {}", ExceptionUtils.dumpCallStack(new Throwable(), "", 2));
                continue;
            }
        }
        pipeline.addLast(name, handler);
        if (LOG.isDebugEnabled()) {
            LOG.debug("insertHandler: channel ({}) add handler({}/{}) at last.", 
                    pipeline.channel(), name, handler);
        }
        return handler;
    }
    
    public static <E extends Enum<E>> ToOrdinal ordinal(final Class<E> cls) {
        return new ToOrdinal() {
            @Override
            public Integer call(final String name, final ChannelHandler handler) {
                if (handler instanceof Ordered) {
                    return ((Ordered)handler).ordinal();
                }
                else {
                    return Enum.valueOf(cls, name).ordinal();
                }
            }};
    }
    
    private static final AttributeKey<Object> READY_ATTR = AttributeKey.valueOf("__READY");
    
    public static void setChannelReady(final Channel channel) {
        channel.attr(READY_ATTR).set(new Object());
    }
    
    public static boolean isChannelReady(final Channel channel) {
        return null != channel.attr(READY_ATTR).get();
    }
    
    public static byte[] dumpByteBufAsBytes(final ByteBuf bytebuf)
        throws IOException {
        try (final InputStream is = new ByteBufInputStream(bytebuf)) {
            final byte[] bytes = new byte[is.available()];
            is.read(bytes);
            return bytes;
        }
    }

    public static String dumpByteBufHolder(final ByteBufHolder holder) {
        final ByteBuf content = holder.content();
        final ByteBuf unwrap = null != content.unwrap() ? content.unwrap() : content;
        
        final StringBuilder sb = new StringBuilder();
        sb.append(unwrap.toString());
        sb.append('@');
        sb.append(UnsafeOp.toAddress(unwrap));
        return sb.toString();
    }

    public static boolean isFieldAnnotatedOfHttpMethod(final Field field, final HttpMethod httpMethod) {
        final AnnotationWrapper wrapper = 
                field.getAnnotation(AnnotationWrapper.class);
        if ( null != wrapper ) {
            return wrapper.value().getSimpleName().equals(httpMethod.name());
        } else {
            return false;
        }
    }
    
    public static void fillByteBufHolderUsingBytes(final ByteBufHolder holder, final byte[] bytes) {
        try(final OutputStream os = new ByteBufOutputStream(holder.content())) {
            os.write(bytes);
        }
        catch (Throwable e) {
            LOG.warn("exception when write bytes to holder {}, detail:{}", 
                    holder, ExceptionUtils.exception2detail(e));
        }
    }

    public static ByteBuf unwrapIfNeed(final ByteBuf buf) {
        return buf.unwrap() != null ? buf.unwrap() : buf;
    }
    
    public static boolean isSameByteBuf(final ByteBuf buf1, final ByteBuf buf2) {
        return unwrapIfNeed(buf1) == unwrapIfNeed(buf2);
    }
    
    public static String dumpChannelConfig(final ChannelConfig config) {
        final StringBuilder sb = new StringBuilder();
        sb.append("allocator: ");
        sb.append(config.getAllocator().toString());
        sb.append('\n');
        sb.append("isAutoRead: ");
        sb.append(config.isAutoRead());
        sb.append('\n');
        return sb.toString();
    }
    
    public static boolean isSupportCompress(final Channel channel) {
        return APPLY.CONTENT_DECOMPRESSOR.hasApplyTo(channel.pipeline());
    }
}
