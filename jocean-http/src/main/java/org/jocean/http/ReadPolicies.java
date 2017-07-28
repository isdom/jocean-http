package org.jocean.http;

import java.util.concurrent.TimeUnit;

import org.jocean.http.WritePolicy.Outboundable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Single;
import rx.SingleSubscriber;
import rx.functions.Action1;
import rx.functions.Func2;

public class ReadPolicies {
    private static final Logger LOG = LoggerFactory
            .getLogger(ReadPolicies.class);
    
    private static final Object _NOTIFIER = new Object();
    
    private ReadPolicies() {
        throw new IllegalStateException("No instances!");
    }

    public static ReadPolicy maxbps() {
        return maxbps(8192L, 500L);
    }
    
    public static ReadPolicy maxbps(final long maxBytesPerSecond, final long maxDelay) {
        return new MaxBPS(maxBytesPerSecond, maxDelay);
    }
    
    public static ReadPolicy byoutbound(final long maxDelay, final Outboundable outboundable) {
        return new ByOutbound(outboundable);
    }
    
    public static ReadPolicy composite(final ReadPolicy policy1, final ReadPolicy policy2) {
        return new ReadPolicy() {
            @Override
            public Single<?> whenToRead(final Inboundable inboundable) {
                return Single.zip(policy1.whenToRead(inboundable), 
                        policy2.whenToRead(inboundable),
                        new Func2<Object, Object, Object>() {
                            @Override
                            public Object call(Object t1, Object t2) {
                                return t1;
                            }});
                
            }};
    }
    
    static class MaxBPS implements ReadPolicy {
        
        MaxBPS(final long maxBytesPerSecond, final long maxDelay) {
            this._maxBytesPerSecond = maxBytesPerSecond;
            this._maxDelay = maxDelay;
        }
        
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
        
        private static void ctrlSpeed(final Inboundable inbound, 
                final SingleSubscriber<? super Object> subscriber,
                final long maxBytesPerSecond, 
                final long maxDelay) {
            if (inbound.durationFromRead() > maxDelay - 50) {
                LOG.info("inbound {} wait {} MILLISECONDS, then perform read", 
                        inbound, inbound.durationFromRead());
                subscriber.onSuccess(_NOTIFIER);
                return;
            }
            final long currentSpeed = (long) (inbound.inboundBytes() 
                    / (inbound.durationFromBegin() / 1000.0F));
            if (currentSpeed <= maxBytesPerSecond) {
                LOG.info("now speed: {} BPS <= MAX Limited Speed: {} BPS, inbound {} perform read RIGHT NOW", 
                        currentSpeed, maxBytesPerSecond, inbound);
                subscriber.onSuccess(_NOTIFIER);
                return;
            }
            // calculate next read time to wait, and max time to delay is 500ms
            final long timeoutToRead = Math.max(Math.min(
                    (long) (((float)inbound.inboundBytes() / maxBytesPerSecond) * 1000L
                    - inbound.durationFromBegin())
                    , maxDelay), 1);
            LOG.info("now speed: {} BPS > MAX Limited Speed: {} BPS, "
                    + "inbound {} read action will be delay {} MILLISECONDS", 
                    currentSpeed, maxBytesPerSecond, inbound, timeoutToRead);
            Observable.timer(timeoutToRead, TimeUnit.MILLISECONDS).toSingle()
                .subscribe(subscriber);
        }

        private final long _maxBytesPerSecond;
        private final long _maxDelay;
    }

    static class ByOutbound implements ReadPolicy {
        
        ByOutbound(final Outboundable outboundable) {
            this._outboundable = outboundable;
        }
        
        @Override
        public Single<?> whenToRead(final Inboundable inbound) {
            return Single.create(new Single.OnSubscribe<Object>() {
                @Override
                public void call(final SingleSubscriber<? super Object> subscriber) {
                    if (!subscriber.isUnsubscribed()) {
                        ctrlSpeed(inbound, subscriber, _outboundable);
                    }
                }});
        }
        
        private static void ctrlSpeed(final Inboundable inbound, 
                final SingleSubscriber<? super Object> subscriber,
                final Outboundable outboundable) {
            // TBD: unsubscribe writability()
            outboundable.writability().subscribe(new Action1<Boolean>() {
                @Override
                public void call(final Boolean iswritable) {
                    if (iswritable) {
                        LOG.info("inbound {} 's peer outbound {} can write, then perform read", 
                                inbound, outboundable);
                        if (!subscriber.isUnsubscribed()) {
                            subscriber.onSuccess(_NOTIFIER);
                        }
                    } else {
                        LOG.info("inbound {} 's peer outbound {} CAN'T write, then waiting", 
                                inbound, outboundable);
                    }
                }});
        }

        private final Outboundable _outboundable;
    }
}
