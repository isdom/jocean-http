package org.jocean.http.client.impl;

import org.jocean.http.client.Outbound;

public class HttpClientUtil {
    
    public static Outbound.TrafficCounterFeature buildTrafficCounterFeature() {
        return new TrafficCounterProxy();
    }

    public static Outbound.PayloadCounterFeature buildPayloadCounterFeature() {
        return new PayloadCounterProxy();
    }
}
