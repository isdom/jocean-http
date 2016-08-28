/**
 * 
 */
package org.jocean.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SslContext;
import rx.functions.Func0;
import rx.functions.Func2;

/**
 * @author isdom
 *
 */
public interface Feature {
    public static class Util {
        public static Feature[] union(final Feature[] features1, final Feature... features2) {
            final List<Feature> unioned = new ArrayList<>(
                    null != features1 
                    ? Arrays.asList(features1) 
                    : Collections.<Feature>emptyList());
            if (null != features2) {
                for (Feature toadd : features2) {
                    if (!unioned.contains(toadd)) {
                        unioned.add(toadd);
                    }
                }
            }
            return unioned.toArray(Feature.EMPTY_FEATURES);
        }
    }
    
    public interface FeaturesAware {
        public void setFeatures(final Feature[] features);
    }
    
    public interface FeatureOverChannelHandler extends Feature, Func2<Feature.HandlerBuilder, ChannelPipeline, ChannelHandler> {
    }
    
    public interface HandlerBuilder {
        public ChannelHandler build(final Feature feature, final ChannelPipeline pipeline, final Object... args);
    }
    
    public static final Feature[] EMPTY_FEATURES = new Feature[0];

    public static final Func0<Feature[]> FEATURESBUILDER_FOR_EMPTY = new Func0<Feature[]>() {
        @Override
        public Feature[] call() {
            return EMPTY_FEATURES;
        }
    };
    
    public static abstract class AbstractFeature0 implements FeatureOverChannelHandler {
        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return builder.build(this, pipeline);
        }
    };
    
    public static final Feature ENABLE_LOGGING = new AbstractFeature0() {
        @Override
        public String toString() {
            return "ENABLE_LOGGING";
        }
    };
    
    public static final Feature ENABLE_LOGGING_OVER_SSL = new AbstractFeature0() {
        @Override
        public String toString() {
            return "ENABLE_LOGGING_PREV_SSL";
        }
    };
    
    public static final Feature ENABLE_COMPRESSOR = new AbstractFeature0() {
        @Override
        public String toString() {
            return "ENABLE_COMPRESSOR";
        }
    };
    
    public static final class ENABLE_CLOSE_ON_IDLE implements FeatureOverChannelHandler {
        public ENABLE_CLOSE_ON_IDLE(final int allIdleTimeout) {
            this._allIdleTimeout = allIdleTimeout;
        }
        
        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return builder.build(this, pipeline, this._allIdleTimeout);
        }
        
        @Override
        public String toString() {
            return "ENABLE_CLOSE_ON_IDLE";
        }
        
        private final int _allIdleTimeout;
    }
    
    public static final class ENABLE_SSL implements FeatureOverChannelHandler {
        public ENABLE_SSL(final SslContext sslCtx) {
            this._sslCtx = sslCtx;
        }
        
        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return builder.build(this, pipeline, pipeline.channel(), this._sslCtx);
        }
        
        @Override
        public String toString() {
            return "ENABLE_SSL";
        }
        
        private final SslContext _sslCtx;
    }
}
