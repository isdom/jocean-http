package org.jocean.http.util;

import java.util.concurrent.TimeUnit;

import org.jocean.http.InboundEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.AttributeKey;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import rx.functions.Action1;
import rx.functions.Func0;

public class InboundSpeedController {
    private static final Logger LOG = LoggerFactory
            .getLogger(InboundSpeedController.class);
    
    public void setTimer(final Timer timer) {
        this._timer = timer;
    }
    
    public void setMaxBytesPerSecond(final long maxBytesPerSecond) {
        this._maxBytesPerSecond = maxBytesPerSecond;
    }
    
    public void setMaxDelay(final long maxDelay) {
        this._maxDelay = maxDelay;
    }
    
    public void applyTo(final InboundEndpoint inbound) {
        inbound.setReadPolicy(this._ctrlInboundSpeed);
    }
    
    private void scheduleNextCall(final InboundEndpoint inbound, 
            final long timeoutToRead,
            final Action1<InboundEndpoint> policy) {
        final TimerTask readInboundTask = new TimerTask() {
            @Override
            public void run(final Timeout timeout) throws Exception {
                policy.call(inbound);
            }};
        _timer.newTimeout(readInboundTask, timeoutToRead, TimeUnit.MILLISECONDS);
    }

    private final static AttributeKey<Func0<Boolean>> _CAN_READ = AttributeKey.valueOf("_CAN_READ");
    
    public static void setCanRead(final InboundEndpoint inbound, final Func0<Boolean> canRead) {
        inbound.attr(_CAN_READ).set(canRead);
    }
    
    private final Action1<InboundEndpoint> _ctrlInboundSpeed = new Action1<InboundEndpoint>() {
        @Override
        public void call(final InboundEndpoint inbound) {
            if (!inbound.isActive()) {
                // if inbound is unactive , then ignore
                return;
            }
            final Func0<Boolean> canRead = inbound.attr(_CAN_READ).get();
            if (null != canRead 
                && !canRead.call()) {
                scheduleNextCall(inbound, 50, this);
                return;
            }
            if (inbound.unreadDurationInMs() > _maxDelay - 50) {
                LOG.info("inbound {} wait {} MILLISECONDS, then perform read", 
                        inbound, inbound.unreadDurationInMs());
                inbound.readMessage();
                return;
            }
            final long currentSpeed = (long) (inbound.inboundBytes() 
                    / (inbound.timeToLive() / 1000.0F));
            if (currentSpeed <= _maxBytesPerSecond) {
                LOG.info("now speed: {} BPS <= MAX ISC: {} BPS, inbound {} perform read RIGHT NOW", 
                        currentSpeed, _maxBytesPerSecond, inbound);
                inbound.readMessage();
                return;
            }
            // calculate next read time to wait, and max time to delay is 500ms
            final long timeoutToRead = Math.max(Math.min(
                    (long) (((float)inbound.inboundBytes() / _maxBytesPerSecond) * 1000L
                    - inbound.timeToLive())
                    , _maxDelay), 1);
            LOG.info("now speed: {} BPS > MAX ISC: {} BPS, "
                    + "inbound {} read action will be delay {} MILLISECONDS", 
                    currentSpeed, _maxBytesPerSecond, inbound, timeoutToRead);
            scheduleNextCall(inbound, timeoutToRead, this);
        }};
        
    private long _maxBytesPerSecond = 8192L;
    private long _maxDelay = 500L;
    private Timer _timer = null;
}
