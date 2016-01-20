package org.jocean.http.client;

public interface InteractionMeter {
    
    public long uploadBytes();
    
    public long downloadBytes();
}
