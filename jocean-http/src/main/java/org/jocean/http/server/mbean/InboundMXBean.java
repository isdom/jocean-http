package org.jocean.http.server.mbean;

public interface InboundMXBean {
    
    public String getHost();
    
    public String getHostIp();
    
    public String getBindIp();
    
    public int getPort();
}
