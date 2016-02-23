/**
 * 
 */
package org.jocean.http.client.impl;

import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.client.TrafficCounter;
import org.jocean.http.client.Outbound;

/**
 * @author isdom
 *
 */
class TrafficCounterProxy extends Feature.AbstractFeature0 
    implements Outbound.TrafficCounterFeature, TrafficCounterAware {

    @Override
    public String toString() {
        return "INTERACTION_METER";
    }
    
    /* (non-Javadoc)
     * @see org.jocean.http.client.InteractionMeter#uploadBytes()
     */
    @Override
    public long uploadBytes() {
        final TrafficCounter impl = this._ref.get();
        
        return null != impl ? impl.uploadBytes() : 0;
    }

    /* (non-Javadoc)
     * @see org.jocean.http.client.InteractionMeter#downloadBytes()
     */
    @Override
    public long downloadBytes() {
        final TrafficCounter impl = this._ref.get();
        
        return null != impl ? impl.downloadBytes() : 0;
    }

    public void setTrafficCounter(final TrafficCounter ref) {
        this._ref.set(ref);
    }
    
    private final AtomicReference<TrafficCounter> _ref = 
            new AtomicReference<TrafficCounter>();
}
