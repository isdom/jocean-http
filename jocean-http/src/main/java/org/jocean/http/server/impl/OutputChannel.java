package org.jocean.http.server.impl;

public interface OutputChannel {
    public void output(final Object msg);
    public void onTradeFinished(boolean isCompleted);
}
