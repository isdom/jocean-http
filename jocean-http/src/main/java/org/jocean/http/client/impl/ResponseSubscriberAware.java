package org.jocean.http.client.impl;

import rx.Subscriber;

public interface ResponseSubscriberAware {
    
    public void setResponseSubscriber(final Subscriber<Object> subscriber);
}
