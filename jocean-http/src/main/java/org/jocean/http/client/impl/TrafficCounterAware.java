package org.jocean.http.client.impl;

import org.jocean.http.client.TrafficCounter;

public interface TrafficCounterAware {
    public void setTrafficCounter(final TrafficCounter counter);
}
