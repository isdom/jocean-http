package org.jocean.http.server.impl;

import io.netty.handler.codec.http.HttpObject;
import rx.Observer;

public interface HttpObjectObserverAware {
    public void setHttpObjectObserver(final Observer<HttpObject> observer);
}
