package org.jocean.http.util;

import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.Feature.FeatureOverChannelHandler;
import org.jocean.http.PayloadCounter;
import org.jocean.http.TrafficCounter;

import io.netty.handler.codec.http.HttpMethod;

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
    
    public static HttpMethod fromJSR331Type(final Class<?> httpMethodType) {
        final javax.ws.rs.HttpMethod rsHttpMethod = 
                httpMethodType.getAnnotation(javax.ws.rs.HttpMethod.class);
        if (null != rsHttpMethod) {
            return HttpMethod.valueOf(rsHttpMethod.value());
        } else {
            return null;
        }
    }
}
