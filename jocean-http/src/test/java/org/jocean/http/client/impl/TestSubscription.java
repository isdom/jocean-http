package org.jocean.http.client.impl;

import java.util.concurrent.CountDownLatch;

import rx.Subscription;

public final class TestSubscription implements Subscription {
    private final CountDownLatch _unsubscribed = new CountDownLatch(1);

    @Override
    public void unsubscribe() {
        _unsubscribed.countDown();
    }

    @Override
    public boolean isUnsubscribed() {
        return _unsubscribed.getCount()==0;
    }
    
    public void awaitUnsubscribed() {
        try {
            _unsubscribed.await();
        } catch (InterruptedException e) {
        }
    }
}
