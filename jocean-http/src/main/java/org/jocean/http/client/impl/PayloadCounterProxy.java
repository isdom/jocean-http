/**
 * 
 */
package org.jocean.http.client.impl;

import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.client.Outbound;
import org.jocean.http.client.PayloadCounter;

/**
 * @author isdom
 *
 */
class PayloadCounterProxy extends Feature.AbstractFeature0 
    implements Outbound.PayloadCounterFeature, PayloadCounterAware {

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
