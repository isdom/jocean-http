package org.jocean.http.client;

public interface TrafficCounter {
    
    public long uploadBytes();
    
    public long downloadBytes();
}
