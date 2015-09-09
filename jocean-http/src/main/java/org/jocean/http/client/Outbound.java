package org.jocean.http.client;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpRequest;

import org.jocean.http.Feature;

import rx.Subscriber;
import rx.functions.Action1;

public class Outbound {
    private Outbound() {
        throw new IllegalStateException("No instances!");
    }

    public interface ApplyToRequest extends Action1<HttpRequest> {
    }
    
    public interface ResponseSubscriberAware {
        public void setResponseSubscriber(final Subscriber<Object> subscriber);
    }
    
    public static final Feature ENABLE_MULTIPART = new Feature.AbstractFeature0() {
        @Override
        public String toString() {
            return "ENABLE_MULTIPART";
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

        @Override
        public String toString() {
            return "ENABLE_PROGRESSIVE";
        }
        
        private final long _minIntervalInMs;
        private Subscriber<Object> _responseSubscriber = null;
    }
}