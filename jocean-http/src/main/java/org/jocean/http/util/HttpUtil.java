package org.jocean.http.util;

import java.lang.annotation.Annotation;
import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;

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

        @Override
        public long outboundBytes() {
            final TrafficCounter impl = this._ref.get();

            return null != impl ? impl.outboundBytes() : 0;
        }

        @Override
        public long inboundBytes() {
            final TrafficCounter impl = this._ref.get();

            return null != impl ? impl.inboundBytes() : 0;
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
    
    public static Class<? extends Annotation> toJSR331Type(final HttpMethod method) {
        switch(method.name()) {
        case javax.ws.rs.HttpMethod.GET:
            return GET.class;
        case javax.ws.rs.HttpMethod.POST:
            return POST.class;
        case javax.ws.rs.HttpMethod.PUT:
            return PUT.class;
        case javax.ws.rs.HttpMethod.HEAD:
            return HEAD.class;
        case javax.ws.rs.HttpMethod.DELETE:
            return DELETE.class;
        case javax.ws.rs.HttpMethod.OPTIONS:
            return OPTIONS.class;
        default:
            return null;
        }
    }
}
