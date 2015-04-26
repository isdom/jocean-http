package org.jocean.http.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;

import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys.ToOrdinal;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.rx.RxFunctions;

import rx.functions.Func1;
import rx.functions.FuncN;
import rx.functions.Functions;

public enum InboundFeature {
    LOGGING(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
    CLOSE_ON_IDLE(Functions.fromFunc(Nettys.CLOSE_ON_IDLE_FUNC2)),
    ENABLE_SSL(Functions.fromFunc(Nettys.SSL_FUNC2)),
    HTTPSERVER_CODEC(null),
    CONTENT_COMPRESSOR(Nettys.CONTENT_COMPRESSOR_FUNCN),
    LAST_FEATURE(null)
    ;
    
    public interface Applicable extends Func1<Channel, ChannelHandler> {
    };
    
    public static final Applicable APPLY_CONTENT_COMPRESSOR = new Applicable() {
        @Override
        public ChannelHandler call(final Channel channel) {
            return CONTENT_COMPRESSOR.applyTo(channel);
        }
    };
    
    public static final Applicable APPLY_LOGGING = new Applicable() {
        @Override
        public ChannelHandler call(final Channel channel) {
            return LOGGING.applyTo(channel);
        }
    };
            
    public static final class APPLY_SSL implements Applicable {
        public APPLY_SSL(final SslContext sslCtx) {
            this._sslCtx = sslCtx;
        }
        
        @Override
        public ChannelHandler call(final Channel channel) {
            return ENABLE_SSL.applyTo(channel, this._sslCtx);
        }
        
        private final SslContext _sslCtx;
    }
    
    public static final class APPLY_CLOSE_ON_IDLE implements Applicable {
        public APPLY_CLOSE_ON_IDLE(final int allIdleTimeout) {
            this._allIdleTimeout = allIdleTimeout;
        }
        
        @Override
        public ChannelHandler call(final Channel channel) {
            return CLOSE_ON_IDLE.applyTo(channel, this._allIdleTimeout);
        }
        
        private final int _allIdleTimeout;
    }
    
    public ChannelHandler applyTo(final Channel channel, final Object ... args) {
        if (null==this._factory) {
            throw new UnsupportedOperationException("ChannelHandler's factory is null");
        }
        return Nettys.insertHandler(
            channel.pipeline(),
            this.name(), 
            this._factory.call(JOArrays.addFirst(args, channel, Object[].class)), 
            TO_ORDINAL);
    }

    public static final ToOrdinal TO_ORDINAL = Nettys.ordinal(InboundFeature.class);
    
    private InboundFeature(final FuncN<ChannelHandler> factory) {
        this._factory = factory;
    }

    private final FuncN<ChannelHandler> _factory;
}
