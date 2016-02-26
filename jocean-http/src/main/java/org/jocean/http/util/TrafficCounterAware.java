package org.jocean.http.util;

import org.jocean.http.TrafficCounter;

public interface TrafficCounterAware {
    public void setTrafficCounter(final TrafficCounter counter);
}
