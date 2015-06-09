package org.jocean.http.client;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;

import org.jocean.http.Feature;
import org.jocean.http.util.Oneoff;
import org.jocean.http.util.ResponseSubscriberAware;

import rx.Subscriber;

public class Outbound {
    private Outbound() {
        throw new IllegalStateException("No instances!");
    }

    public interface OneoffFeature extends Feature, Oneoff {
    };
    
    public interface FeaturesAware {
        public void setApplyFeatures(final Feature[] features);
    }
    
    public interface ApplyToRequest {
        public void applyToRequest(final HttpRequest request);
    }
    
    private abstract static class CLS_DECOMPRESSOR implements OneoffFeature, ApplyToRequest {
    }
    
    public static final Feature ENABLE_LOGGING = new OneoffFeature() {
        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return builder.build(this, pipeline);
        }
    };
            
    public static final Feature ENABLE_DECOMPRESSOR = new CLS_DECOMPRESSOR() {
        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return builder.build(this, pipeline);
        }
        
        @Override
        public void applyToRequest(final HttpRequest request) {
            HttpHeaders.addHeader(request,
                    HttpHeaders.Names.ACCEPT_ENCODING, 
                    HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE);
        }
    };
    
    public static final Feature ENABLE_MULTIPART = new Feature() {
        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return  builder.build(this, pipeline);
        }
    };
    
    public static final class ENABLE_CLOSE_ON_IDLE implements OneoffFeature {
        public ENABLE_CLOSE_ON_IDLE(final int allIdleTimeout) {
            this._allIdleTimeout = allIdleTimeout;
        }
        
        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return builder.build(this, pipeline, this._allIdleTimeout);
        }
        
        private final int _allIdleTimeout;
    }
    
    public static final class ENABLE_PROGRESSIVE implements 
        OneoffFeature, ResponseSubscriberAware, Cloneable {
        public ENABLE_PROGRESSIVE(final long minIntervalInMs) {
            this._minIntervalInMs = minIntervalInMs;
        }
        
        @Override
        public void setResponseSubscriber(Subscriber<Object> subscriber) {
            this._responseSubscriber = subscriber;
        }
        
        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return builder.build(this, pipeline, this._responseSubscriber, this._minIntervalInMs);
        }
        
        @Override
        public ENABLE_PROGRESSIVE clone() throws CloneNotSupportedException {
            return (ENABLE_PROGRESSIVE)super.clone();
        }

        private final long _minIntervalInMs;
        private Subscriber<Object> _responseSubscriber = null;
    }
}
