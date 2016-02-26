package org.jocean.http.util;

import static org.junit.Assert.*;

import org.jocean.http.PayloadCounter;
import org.jocean.http.util.PayloadCounterProxy;
import org.junit.Test;

public class PayloadCounterTestCase {

    @Test
    public final void testPayloadCounterProxyUploadBytes() {
        final PayloadCounterProxy proxy = new PayloadCounterProxy();
        
        assertEquals(proxy.totalUploadBytes(), 0);
    }

    @Test
    public final void testPayloadCounterProxyDownloadBytes() {
        final PayloadCounterProxy proxy = new PayloadCounterProxy();
        
        assertEquals(proxy.totalDownloadBytes(), 0);
    }

    @Test
    public final void testPayloadCounterProxyAssignRefUploadBytes() {
        final PayloadCounterProxy proxy = new PayloadCounterProxy();
        
        proxy.setPayloadCounter(new PayloadCounter() {
            @Override
            public long totalUploadBytes() {
                return 100;
            }
            @Override
            public long totalDownloadBytes() {
                return 0;
            }});
        assertEquals(proxy.totalUploadBytes(), 100);
    }

    @Test
    public final void testPayloadCounterProxyAssignRefDownloadBytes() {
        final PayloadCounterProxy proxy = new PayloadCounterProxy();
        
        proxy.setPayloadCounter(new PayloadCounter() {
            @Override
            public long totalUploadBytes() {
                return 100;
            }
            @Override
            public long totalDownloadBytes() {
                return 100;
            }});
        assertEquals(proxy.totalDownloadBytes(), 100);
    }
}
