package org.jocean.http.client.impl;

import java.util.concurrent.atomic.AtomicBoolean;

import rx.functions.Action1;

public class OnNextSensor<T> implements Action1<T> {
    
    @Override
    public void call(final T t) {
        this._onNextCalled.set(true);
    }
    
    private final AtomicBoolean _onNextCalled = 
            new AtomicBoolean(false);
    
    public void assertNotCalled() {
        if (this._onNextCalled.get()) {
            throw new AssertionError("On Next called");
        }
    }
    
    public void assertCalled() {
        if (!this._onNextCalled.get()) {
            throw new AssertionError("On Next !NOT! called");
        }
    }
};
    
