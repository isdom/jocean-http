package org.jocean.http.client;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;

import org.jocean.http.Feature;
import org.jocean.http.util.ResponseSubscriberAware;

import rx.Subscriber;

public class Outbound {
    private Outbound() {
        throw new IllegalStateException("No instances!");
    }

    public static final Feature ENABLE_MULTIPART = new Feature() {
        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return  builder.build(this, pipeline);
        }
    };
    
    public static final class ENABLE_PROGRESSIVE implements 
        Feature, ResponseSubscriberAware, Cloneable {
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
