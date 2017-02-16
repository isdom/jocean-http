package org.jocean.http.server;

import java.util.concurrent.TimeUnit;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
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
    
    public void applyTo(final HttpTrade trade) {
        trade.setInboundAutoRead(false);
        trade.addInboundReadCompleteHook(this._ctrlInboundSpeed);
        trade.readInbound();
    }
    
    private final Action1<HttpTrade> _ctrlInboundSpeed = new Action1<HttpTrade>() {
        @Override
        public void call(final HttpTrade trade) {
            final long currentSpeed = (long) (trade.trafficCounter().inboundBytes() 
                    / (trade.timeToLive() / 1000.0F));
            if (currentSpeed > _maxBytesPerSecond) {
                // calculate next read time to wait, and max time to delay is 500ms
                final long timeoutToRead = Math.min(
                        (long) (((float)trade.trafficCounter().inboundBytes() / _maxBytesPerSecond) * 1000L
                        - trade.timeToLive())
                        , _maxDelay);
                if (timeoutToRead > 0) {
                    final TimerTask readInboundTask = new TimerTask() {
                        @Override
                        public void run(final Timeout timeout) throws Exception {
                            LOG.info("trade {} read inbound for ISC wait {} MILLISECONDS", 
                                    trade, timeoutToRead);
                            trade.readInbound();
                            //  check this task own the attr, and clear the attr
//                            trade.attr(DELAY_AUTOREAD_ON).compareAndSet(this, null);
                        }};
//                    final TimerTask current = trade.attr(DELAY_AUTOREAD_ON).setIfAbsent(delayAutoReadONTask);
//                    if (null == current) 
                    {
                        //  this is the first delay setAutoRead on task, set success
                        LOG.info("inbound bytes:{} bytes/cost time:{} MILLISECONDS\ncurrent inbound speed: {} Bytes/Second more than MAX ISC: {} Bytes/Second, "
                                + "so trade {} read inbound will be delay {} MILLISECONDS for ISC", 
                                trade.trafficCounter().inboundBytes(), trade.timeToLive(),
                                currentSpeed, _maxBytesPerSecond, trade, timeoutToRead);
                        _timer.newTimeout(readInboundTask, timeoutToRead, TimeUnit.MILLISECONDS);
                    } 
//                    else {
//                        LOG.info("trade {} has pending setInboundAutoRead ON TASK: {}, ignore Redundant ON TASK", 
//                            trade, current);
//                    }
                    return;
                }
            }
            LOG.info("current inbound speed: {} Bytes/Second less than MAX ISC: {} Bytes/Second, so trade {} read inbound right now", 
                    currentSpeed, _maxBytesPerSecond, trade);
            trade.readInbound();
        }};
        
    private long _maxBytesPerSecond = 8192L;
    private long _maxDelay = 500L;
    private Timer _timer = null;
}
