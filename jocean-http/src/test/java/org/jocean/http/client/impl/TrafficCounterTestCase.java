package org.jocean.http.client.impl;

import static org.junit.Assert.*;

import org.jocean.http.client.TrafficCounter;
import org.junit.Test;

public class TrafficCounterTestCase {

    @Test
    public final void testTrafficCounterProxyUploadBytes() {
        final TrafficCounterProxy proxy = new TrafficCounterProxy();
        
        assertEquals(proxy.uploadBytes(), 0);
    }

    @Test
    public final void testTrafficCounterProxyDownloadBytes() {
        final TrafficCounterProxy proxy = new TrafficCounterProxy();
        
        assertEquals(proxy.downloadBytes(), 0);
    }

    @Test
    public final void testTrafficCounterProxyAssignRefUploadBytes() {
        final TrafficCounterProxy proxy = new TrafficCounterProxy();
        
        proxy.setTrafficCounter(new TrafficCounter() {
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
    public final void testTrafficCounterProxyAssignRefDownloadBytes() {
        final TrafficCounterProxy proxy = new TrafficCounterProxy();
        
        proxy.setTrafficCounter(new TrafficCounter() {
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
