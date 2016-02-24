package org.jocean.http.client.impl;

import org.jocean.http.client.PayloadCounter;

public interface PayloadCounterAware {
    public void setPayloadCounter(final PayloadCounter counter);
}
