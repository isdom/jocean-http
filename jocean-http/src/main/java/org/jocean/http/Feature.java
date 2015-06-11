/**
 * 
 */
package org.jocean.http;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SslContext;
import rx.functions.Func2;

/**
 * @author isdom
 *
 */
public interface Feature extends Func2<Feature.HandlerBuilder, ChannelPipeline, ChannelHandler> {
    public interface HandlerBuilder {
        public ChannelHandler build(final Feature feature, final ChannelPipeline pipeline, final Object... args);
    }
    public static final Feature[] EMPTY_FEATURES = new Feature[0];

    public static abstract class AbstractFeature0 implements Feature {
        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return builder.build(this, pipeline);
        }
    };
    
    public static final Feature ENABLE_LOGGING = new AbstractFeature0() {};
    
    public static final Feature ENABLE_COMPRESSOR = new AbstractFeature0() {};
    
    public static final class ENABLE_CLOSE_ON_IDLE implements Feature {
        public ENABLE_CLOSE_ON_IDLE(final int allIdleTimeout) {
            this._allIdleTimeout = allIdleTimeout;
        }
        
        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return builder.build(this, pipeline, this._allIdleTimeout);
        }
        
        private final int _allIdleTimeout;
    }
    
    public static final class ENABLE_SSL implements Feature {
        public ENABLE_SSL(final SslContext sslCtx) {
            this._sslCtx = sslCtx;
        }
        
        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return builder.build(this, pipeline, pipeline.channel(), this._sslCtx);
        }
        
        private final SslContext _sslCtx;
    }
}
