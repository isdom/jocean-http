package org.jocean.http.client;

public interface PayloadCounter {
    
    public long totalUploadBytes();
    
    public long totalDownloadBytes();
}
