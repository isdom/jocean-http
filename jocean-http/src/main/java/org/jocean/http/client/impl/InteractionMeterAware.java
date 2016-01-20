package org.jocean.http.client.impl;

import org.jocean.http.client.InteractionMeter;

public interface InteractionMeterAware {
    public void setInteractionMeter(final InteractionMeter meter);
}
