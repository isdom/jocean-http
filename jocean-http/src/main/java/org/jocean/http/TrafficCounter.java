package org.jocean.http;

public interface TrafficCounter {
    
    public long outboundBytes();
    
    public long inboundBytes();
}
