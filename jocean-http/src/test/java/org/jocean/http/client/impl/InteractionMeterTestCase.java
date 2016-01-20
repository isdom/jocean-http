package org.jocean.http.client.impl;

import static org.junit.Assert.*;

import org.jocean.http.client.InteractionMeter;
import org.junit.Test;

public class InteractionMeterTestCase {

    @Test
    public final void testInteractionMeterProxyUploadBytes() {
        final InteractionMeterProxy proxy = new InteractionMeterProxy();
        
        assertEquals(proxy.uploadBytes(), 0);
    }

    @Test
    public final void testInteractionMeterProxyDownloadBytes() {
        final InteractionMeterProxy proxy = new InteractionMeterProxy();
        
        assertEquals(proxy.downloadBytes(), 0);
    }

    @Test
    public final void testInteractionMeterProxyAssignRefUploadBytes() {
        final InteractionMeterProxy proxy = new InteractionMeterProxy();
        
        proxy.setInteractionMeter(new InteractionMeter() {
            @Override
            public long uploadBytes() {
                return 100;
            }
            @Override
            public long downloadBytes() {
                return 0;
            }});
        assertEquals(proxy.uploadBytes(), 100);
    }

    @Test
    public final void testInteractionMeterProxyAssignRefDownloadBytes() {
        final InteractionMeterProxy proxy = new InteractionMeterProxy();
        
        proxy.setInteractionMeter(new InteractionMeter() {
            @Override
            public long uploadBytes() {
                return 100;
            }
            @Override
            public long downloadBytes() {
                return 100;
            }});
        assertEquals(proxy.downloadBytes(), 100);
    }
}
