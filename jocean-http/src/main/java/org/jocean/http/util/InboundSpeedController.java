package org.jocean.http.util;

import java.util.concurrent.TimeUnit;

import org.jocean.http.InboundEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import rx.functions.Action1;

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
        inbound.setAutoRead(false);
        inbound.doOnReadComplete(this._ctrlInboundSpeed);
        inbound.readMessage();
    }
    
    private final Action1<InboundEndpoint> _ctrlInboundSpeed = new Action1<InboundEndpoint>() {
        @Override
        public void call(final InboundEndpoint inbound) {
            final long currentSpeed = (long) (inbound.inboundBytes() 
                    / (inbound.timeToLive() / 1000.0F));
            if (currentSpeed > _maxBytesPerSecond) {
                // calculate next read time to wait, and max time to delay is 500ms
                final long timeoutToRead = Math.min(
                        (long) (((float)inbound.inboundBytes() / _maxBytesPerSecond) * 1000L
                        - inbound.timeToLive())
                        , _maxDelay);
                if (timeoutToRead > 0) {
                    final TimerTask readInboundTask = new TimerTask() {
                        @Override
                        public void run(final Timeout timeout) throws Exception {
                            LOG.info("trade {} read inbound for ISC wait {} MILLISECONDS", 
                                    inbound, timeoutToRead);
                            inbound.readMessage();
                        }};
                    LOG.info("inbound bytes:{} bytes/cost time:{} MILLISECONDS\ncurrent inbound speed: {} Bytes/Second more than MAX ISC: {} Bytes/Second, "
                            + "so trade {} read inbound will be delay {} MILLISECONDS for ISC", 
                            inbound.inboundBytes(), inbound.timeToLive(),
                            currentSpeed, _maxBytesPerSecond, inbound, timeoutToRead);
                    _timer.newTimeout(readInboundTask, timeoutToRead, TimeUnit.MILLISECONDS);
                    return;
                }
            }
            LOG.info("current inbound speed: {} Bytes/Second less than MAX ISC: {} Bytes/Second, so trade {} read inbound right now", 
                    currentSpeed, _maxBytesPerSecond, inbound);
            inbound.readMessage();
        }};
        
    private long _maxBytesPerSecond = 8192L;
    private long _maxDelay = 500L;
    private Timer _timer = null;
}
