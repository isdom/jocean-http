/**
 * 
 */
package org.jocean.http.client.impl;

import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.client.InteractionMeter;
import org.jocean.http.client.Outbound;

/**
 * @author isdom
 *
 */
class InteractionMeterProxy extends Feature.AbstractFeature0 
    implements Outbound.InteractionMeterFeature, InteractionMeterAware {

    @Override
    public String toString() {
        return "INTERACTION_METER";
    }
    
    /* (non-Javadoc)
     * @see org.jocean.http.client.InteractionMeter#uploadBytes()
     */
    @Override
    public long uploadBytes() {
        final InteractionMeter impl = this._ref.get();
        
        return null != impl ? impl.uploadBytes() : 0;
    }

    /* (non-Javadoc)
     * @see org.jocean.http.client.InteractionMeter#downloadBytes()
     */
    @Override
    public long downloadBytes() {
        final InteractionMeter impl = this._ref.get();
        
        return null != impl ? impl.downloadBytes() : 0;
    }

    public void setInteractionMeter(final InteractionMeter ref) {
        this._ref.set(ref);
    }
    
    private final AtomicReference<InteractionMeter> _ref = new AtomicReference<InteractionMeter>();
}
