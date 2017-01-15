package org.jocean.http.util;

import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.Feature.FeatureOverChannelHandler;
import org.jocean.http.PayloadCounter;
import org.jocean.http.TrafficCounter;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpMethod;
import rx.Observable.Transformer;
import rx.functions.Func1;

public class HttpUtil {
    public interface TrafficCounterFeature extends  FeatureOverChannelHandler, TrafficCounter {
    }
    
    public interface PayloadCounterFeature extends  Feature, PayloadCounter {
    }
    
    static class TrafficCounterProxy extends Feature.AbstractFeature0
        implements TrafficCounterFeature, TrafficCounterAware {

        @Override
        public String toString() {
            return "TRAFFIC_COUNTER";
        }

        /*
         * (non-Javadoc)
         * 
         * @see org.jocean.http.client.InteractionMeter#uploadBytes()
         */
        @Override
        public long uploadBytes() {
            final TrafficCounter impl = this._ref.get();

            return null != impl ? impl.uploadBytes() : 0;
        }

        /*
         * (non-Javadoc)
         * 
         * @see org.jocean.http.client.InteractionMeter#downloadBytes()
         */
        @Override
        public long downloadBytes() {
            final TrafficCounter impl = this._ref.get();

            return null != impl ? impl.downloadBytes() : 0;
        }

        public void setTrafficCounter(final TrafficCounter ref) {
            this._ref.set(ref);
        }

        private final AtomicReference<TrafficCounter> _ref = 
                new AtomicReference<TrafficCounter>();
    }

    static class PayloadCounterProxy implements PayloadCounterFeature, PayloadCounterAware {
    
        @Override
        public String toString() {
            return "PAYLOAD_COUNTER";
        }
        
        @Override
        public long totalUploadBytes() {
            final PayloadCounter impl = this._ref.get();
            
            return null != impl ? impl.totalUploadBytes() : 0;
        }
    
        @Override
        public long totalDownloadBytes() {
            final PayloadCounter impl = this._ref.get();
            
            return null != impl ? impl.totalDownloadBytes() : 0;
        }
    
        public void setPayloadCounter(final PayloadCounter ref) {
            this._ref.set(ref);
        }
        
        private final AtomicReference<PayloadCounter> _ref = 
                new AtomicReference<PayloadCounter>();
    }
    
    public static TrafficCounterFeature buildTrafficCounterFeature() {
        return new TrafficCounterProxy();
    }

    public static PayloadCounterFeature buildPayloadCounterFeature() {
        return new PayloadCounterProxy();
    }
    
    public static HttpMethod fromJSR331Type(final Class<?> httpMethodType, final HttpMethod defaultMethod) {
        final javax.ws.rs.HttpMethod rsHttpMethod = 
                httpMethodType.getAnnotation(javax.ws.rs.HttpMethod.class);
        if (null != rsHttpMethod) {
            return HttpMethod.valueOf(rsHttpMethod.value());
        } else {
            return defaultMethod;
        }
    }

    public interface ComposeSourceFeature extends Feature, ComposeSource {
    }
    
    public static Feature buildHoldMessageFeature(final HttpMessageHolder holder) {
        return new ComposeSourceFeature() {
            @Override
            public <T> Transformer<T, T> transformer(final Channel channel) {
                return holder.assembleAndHold();
            }};
    }
    
    public static Feature buildHoldMessageFeature(final Func1<Channel, HttpMessageHolder> channel2holder) {
        return new ComposeSourceFeature() {
            @Override
            public <T> Transformer<T, T> transformer(final Channel channel) {
                return channel2holder.call(channel).assembleAndHold();
            }};
    }
}
