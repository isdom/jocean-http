package org.jocean.http;

import java.util.concurrent.TimeUnit;

import org.jocean.http.Inboundable.ReadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Single;
import rx.SingleSubscriber;

public class MaxBPSReadPolicy implements ReadPolicy {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(MaxBPSReadPolicy.class);
    private static Object _NOTIFIER = new Object();

    @Override
    public Single<?> whenToRead(final Inboundable inbound) {
        return Single.create(new Single.OnSubscribe<Object>() {
            @Override
            public void call(final SingleSubscriber<? super Object> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    ctrlSpeed(inbound, subscriber, _maxBytesPerSecond, _maxDelay);
                }
            }});
    }
    
    public void setMaxBytesPerSecond(final long maxBytesPerSecond) {
        this._maxBytesPerSecond = maxBytesPerSecond;
    }
    
    public void setMaxDelay(final long maxDelay) {
        this._maxDelay = maxDelay;
    }
    
    private static void ctrlSpeed(final Inboundable inbound, 
            final SingleSubscriber<? super Object> subscriber,
            final long maxBytesPerSecond, 
            final long maxDelay) {
        if (inbound.unreadDurationInMs() > maxDelay - 50) {
            LOG.info("inbound {} wait {} MILLISECONDS, then perform read", 
                    inbound, inbound.unreadDurationInMs());
            subscriber.onSuccess(_NOTIFIER);
        }
        final long currentSpeed = (long) (inbound.inboundBytes() 
                / (inbound.readingDurationInMS() / 1000.0F));
        if (currentSpeed <= maxBytesPerSecond) {
            LOG.info("now speed: {} BPS <= MAX Limited Speed: {} BPS, inbound {} perform read RIGHT NOW", 
                    currentSpeed, maxBytesPerSecond, inbound);
            subscriber.onSuccess(_NOTIFIER);
        }
        // calculate next read time to wait, and max time to delay is 500ms
        final long timeoutToRead = Math.max(Math.min(
                (long) (((float)inbound.inboundBytes() / maxBytesPerSecond) * 1000L
                - inbound.readingDurationInMS())
                , maxDelay), 1);
        LOG.info("now speed: {} BPS > MAX Limited Speed: {} BPS, "
                + "inbound {} read action will be delay {} MILLISECONDS", 
                currentSpeed, maxBytesPerSecond, inbound, timeoutToRead);
        Observable.timer(timeoutToRead, TimeUnit.MILLISECONDS).toSingle()
            .subscribe(subscriber);
    }

    private volatile long _maxBytesPerSecond = 8192L;
    private volatile long _maxDelay = 500L;
}
