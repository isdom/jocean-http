package org.jocean.http.server.mbean;

public interface TradeHolderMXBean {
    
    public int getCurrentInboundMemoryInBytes();
    
    public int getPeakInboundMemoryInBytes();
    
    public float getCurrentInboundMemoryInMBs();
    
    public float getPeakInboundMemoryInMBs();
    
    public long getNumCompletedTrades();
    
    public int getNumActiveTrades();
    
    public String[] getAllActiveTrade();
}
