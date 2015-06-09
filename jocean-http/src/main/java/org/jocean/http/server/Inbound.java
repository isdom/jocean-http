package org.jocean.http.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;

import org.jocean.http.Feature;

public class Inbound {
    
    private Inbound() {
        throw new IllegalStateException("No instances!");
    }

    public static final Feature ENABLE_LOGGING = new Feature() {
        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return builder.build(this, pipeline);
        }
    };
    
    public static final Feature ENABLE_COMPRESSOR = new Feature() {
        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return builder.build(this, pipeline);
        }
    };
    
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
}
