package org.jocean.http.server.impl;

public interface ResponseSender {
    
    public void send(final Object msg);
    
    public void onTradeClosed(boolean isResponseCompleted);
}
