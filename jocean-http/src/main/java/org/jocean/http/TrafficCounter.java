package org.jocean.http;

public interface TrafficCounter {
    
    public long uploadBytes();
    
    public long downloadBytes();
}
