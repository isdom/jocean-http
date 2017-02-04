package org.jocean.http.util;

import static org.junit.Assert.*;

import org.jocean.http.TrafficCounter;
import static org.jocean.http.util.HttpUtil.TrafficCounterProxy;
import org.junit.Test;

public class TrafficCounterTestCase {

    @Test
    public final void testTrafficCounterProxyOutboundBytes() {
        final TrafficCounterProxy proxy = new TrafficCounterProxy();
        
        assertEquals(proxy.outboundBytes(), 0);
    }

    @Test
    public final void testTrafficCounterProxyInboundBytes() {
        final TrafficCounterProxy proxy = new TrafficCounterProxy();
        
        assertEquals(proxy.inboundBytes(), 0);
    }

    @Test
    public final void testTrafficCounterProxyAssignRefOutboundBytes() {
        final TrafficCounterProxy proxy = new TrafficCounterProxy();
        
        proxy.setTrafficCounter(new TrafficCounter() {
            @Override
            public long outboundBytes() {
                return 100;
            }
            @Override
            public long inboundBytes() {
                return 0;
            }});
        assertEquals(proxy.outboundBytes(), 100);
    }

    @Test
    public final void testTrafficCounterProxyAssignRefInboundBytes() {
        final TrafficCounterProxy proxy = new TrafficCounterProxy();
        
        proxy.setTrafficCounter(new TrafficCounter() {
            @Override
            public long outboundBytes() {
                return 100;
            }
            @Override
            public long inboundBytes() {
                return 100;
            }});
        assertEquals(proxy.inboundBytes(), 100);
    }
}
