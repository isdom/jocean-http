package org.jocean.http.util;

import org.jocean.http.Feature;

public class HttpUtil {
    
    public static Feature.TrafficCounterFeature buildTrafficCounterFeature() {
        return new TrafficCounterProxy();
    }

    public static Feature.PayloadCounterFeature buildPayloadCounterFeature() {
        return new PayloadCounterProxy();
    }
}
