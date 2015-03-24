package org.jocean.http.client.impl;

import java.util.concurrent.CountDownLatch;

import rx.Subscription;

public class TestSubscription implements Subscription {
    private final CountDownLatch _unsubscribed = new CountDownLatch(1);

    @Override
    public void unsubscribe() {
        this._unsubscribed.countDown();
    }

    @Override
    public boolean isUnsubscribed() {
        return this._unsubscribed.getCount()==0;
    }
    
    public void awaitUnsubscribed() throws InterruptedException {
        this._unsubscribed.await();
    }
}
