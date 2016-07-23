package org.jocean.http.util;

import org.jocean.http.Feature;

import io.netty.handler.codec.http.HttpMethod;

public class HttpUtil {
    
    public static Feature.TrafficCounterFeature buildTrafficCounterFeature() {
        return new TrafficCounterProxy();
    }

    public static Feature.PayloadCounterFeature buildPayloadCounterFeature() {
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
