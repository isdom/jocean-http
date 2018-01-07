package org.jocean.http;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.idiom.DisposableWrapperUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpContent;
import rx.Observable;
import rx.Single;
import rx.SingleSubscriber;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func2;

public interface ReadPolicy {
    public Single<?> whenToRead(final Inboundable inboundable);

    public interface Inboundable {
        public long durationFromRead();
        public long durationFromBegin();
        public long inboundBytes();
    }

    public class Util {
        private static final Logger LOG = LoggerFactory
                .getLogger(Util.class);
        
        private static final Object _NOTIFIER = new Object();
        
        private Util() {
            throw new IllegalStateException("No instances!");
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
        
        private static final ReadPolicy POLICY_NEVER = new ReadPolicy() {
            @Override
            public Single<?> whenToRead(final Inboundable inboundable) {
                return Observable.never().toSingle();
            }};
            
        public static ReadPolicy never() {
            return POLICY_NEVER;
        }
        
        public static ReadPolicy maxbps() {
            return maxbps(8192L, 500L);
        }
        
        public static ReadPolicy maxbps(final long maxBytesPerSecond, final long maxDelay) {
            return new MaxBPS(maxBytesPerSecond, maxDelay);
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

        public static ReadPolicy byoutbound(final long maxDelay, final WriteCtrl sendctrl) {
            return new ByOutbound(sendctrl);
        }
        
        static class ByOutbound implements ReadPolicy {
            
            ByOutbound(final WriteCtrl writeCtrl) {
                this._writeCtrl = writeCtrl;
            }
            
            @Override
            public Single<?> whenToRead(final Inboundable inbound) {
                return Single.create(new Single.OnSubscribe<Object>() {
                    @Override
                    public void call(final SingleSubscriber<? super Object> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            ctrlSpeed(inbound, subscriber, _writeCtrl);
                        }
                    }});
            }
            
            private static void ctrlSpeed(final Inboundable inbound, 
                    final SingleSubscriber<? super Object> subscriber,
                    final WriteCtrl writeCtrl) {
                // TBD: unsubscribe writability()
                writeCtrl.writability().subscribe(new Action1<Boolean>() {
                    @Override
                    public void call(final Boolean iswritable) {
                        if (iswritable) {
                            LOG.info("inbound {} 's peer sendctrl {} can write, then perform read", 
                                    inbound, writeCtrl);
                            if (!subscriber.isUnsubscribed()) {
                                subscriber.onSuccess(_NOTIFIER);
                            }
                        } else {
                            LOG.info("inbound {} 's peer sendctrl {} CAN'T write, then waiting", 
                                    inbound, writeCtrl);
                        }
                    }});
            }

            private final WriteCtrl _writeCtrl;
        }
        
        public static ReadPolicy bysended(final Func0<Long> size4sending, final WriteCtrl writeCtrl, final int maxPendingSize) {
            return new BySended(size4sending, writeCtrl, maxPendingSize);
        }
        
        static class BySended implements ReadPolicy {
            
            BySended(final Func0<Long> size4sending, final WriteCtrl writeCtrl, final int maxPendingSize) {
                this._maxPendingSize = maxPendingSize;
                this._size4sending = size4sending;
                writeCtrl.sended().subscribe(sended -> {
                    final Object unwrap = DisposableWrapperUtil.unwrap(sended);
                    if (unwrap instanceof HttpContent) {
                        this._size4sended.addAndGet(((HttpContent)unwrap).content().readableBytes());
                    }
                    checkPeningRead();
                });
            }
            
            private void checkPeningRead() {
                final long pendingSize = this._size4sending.call() - this._size4sended.get();
                if (pendingSize <= this._maxPendingSize) {
                    LOG.info("pengind size {} <= {}, then check pending read", pendingSize, this._maxPendingSize);
                    final SingleSubscriber<? super Object> subscriber = this._pendingRead.getAndSet(null);
                    if (null != subscriber && !subscriber.isUnsubscribed()) {
                        LOG.info("notify pending read {}", subscriber);
                        subscriber.onSuccess(_NOTIFIER);
                    } else {
                        LOG.info("NONE pending read exist");
                    }
                } else {
                    LOG.info("pengind size {} > {}, just wait", pendingSize, this._maxPendingSize);
                }
            }

            @Override
            public Single<?> whenToRead(final Inboundable inbound) {
                return Single.create(new Single.OnSubscribe<Object>() {
                    @Override
                    public void call(final SingleSubscriber<? super Object> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            _pendingRead.set(subscriber);
                            checkPeningRead();
                        }
                    }});
            }
            
            private final Func0<Long> _size4sending;
            private final AtomicLong _size4sended = new AtomicLong(0);
            private final long _maxPendingSize;
            private final AtomicReference<SingleSubscriber<? super Object>> _pendingRead = new AtomicReference<>(null);
        }
    }
}
