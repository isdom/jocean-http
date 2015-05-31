package org.jocean.http.util;

import rx.Subscriber;

public interface ResponseSubscriberAware {
    
    public void setResponseSubscriber(final Subscriber<Object> subscriber);
    
    public Subscriber<Object> getResponseSubscriber();
}
