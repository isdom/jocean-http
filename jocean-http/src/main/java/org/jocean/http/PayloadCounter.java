package org.jocean.http;

public interface PayloadCounter {
    
    public long totalUploadBytes();
    
    public long totalDownloadBytes();
}
