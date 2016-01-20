package org.jocean.http.client.impl;

import org.jocean.http.client.Outbound;

public class HttpClientUtil {
    public static Outbound.InteractionMeterFeature buildInteractionMeter() {
        return new InteractionMeterProxy();
    }
}
