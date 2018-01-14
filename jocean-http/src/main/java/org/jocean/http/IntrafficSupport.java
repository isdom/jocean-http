package org.jocean.http;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.jocean.http.ReadPolicy.Inboundable;

import rx.Single;
import rx.Subscription;
import rx.functions.Action1;

public abstract class IntrafficSupport implements Intraffic {
    public void setReadPolicy(final ReadPolicy readPolicy) {
        runAtEventLoop0(new Runnable() {
            @Override
            public void run() {
                setReadPolicy0(readPolicy);
            }});
    }
    
    private void setReadPolicy0(final ReadPolicy readPolicy) {
        this._whenToRead = null != readPolicy 
                ? readPolicy.whenToRead(buildInboundable()) 
                : null;
        final Subscription pendingRead = pendingReadUpdater.getAndSet(this, null);
        if (null != pendingRead && !pendingRead.isUnsubscribed()) {
            pendingRead.unsubscribe();
            // perform other read action
            onReadComplete();
        }
    }

    protected void onReadComplete() {
        this._unreadBegin = System.currentTimeMillis();
        if (needRead()) {
            final Single<?> when = this._whenToRead;
            if (null != when) {
                final Subscription pendingRead = when.subscribe(new Action1<Object>() {
                    @Override
                    public void call(final Object nouse) {
                        readMessage0();
                    }});

                pendingReadUpdater.set(this, pendingRead);
            } else {
                //  perform read at once
                readMessage0();
            }
        }
    }

    protected abstract Inboundable buildInboundable();
        
    protected abstract boolean needRead();

    protected abstract void readMessage0();
    
    protected abstract void runAtEventLoop0(Runnable runnable);

    private volatile Single<?> _whenToRead = null;
    
    private static final AtomicReferenceFieldUpdater<IntrafficSupport, Subscription> pendingReadUpdater =
            AtomicReferenceFieldUpdater.newUpdater(IntrafficSupport.class, Subscription.class, "_pendingRead");
    
    @SuppressWarnings("unused")
    private volatile Subscription _pendingRead = null;
    
    protected volatile long _unreadBegin = 0;
}
