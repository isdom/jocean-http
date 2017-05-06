package io.netty.handler.traffic;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在官方的基础上增加了ReadMessages和WrittenMessages,并加入最大值记录
 */
public class TrafficCounterExt extends TrafficCounter {
    private GlobalByteTrafficMonitor byteTrafficMonitor;
    private GlobalMessageTrafficMonitor messageTrafficMonitor;

    /**
     * Current written bytes
     */
    private final AtomicLong currentWrittenMessages = new AtomicLong(0);

    /**
     * Current read bytes
     */
    private final AtomicLong currentReadMessages = new AtomicLong(0);

    /**
     * Long life written Messages
     */
    private final AtomicLong cumulativeWrittenMessages = new AtomicLong(0);

    /**
     * Long life read Messages
     */
    private final AtomicLong cumulativeReadMessages = new AtomicLong(0);

    /**
     * Long life registered Channels
     */
    private final AtomicLong cumulativeRegisteredChannels = new AtomicLong(0);

    /**
     * Long life unregistered Channels
     */
    private final AtomicLong cumulativeUnregisteredChannels = new AtomicLong(0);

    /**
     * Current registered Channels
     */
    private final AtomicLong currentRegisteredChannels = new AtomicLong(0);

    /**
     * Max writing bandwidth
     */
    private long maxWriteThroughput = 0;

    /**
     * Max reading bandwidth
     */
    private long maxReadThroughput = 0;

    /**
     * Max writing message bandwidth
     */
    private long maxWriteMessageThroughput = 0;

    /**
     * Max reading message bandwidth
     */
    private long maxReadMessageThroughput = 0;

    /**
     * Last writing message bandwidth
     */
    private long lastWriteMessageThroughput = 0;

    /**
     * Last reading message bandwidth
     */
    private long lastReadMessageThroughput = 0;

    /**
     * Last Time Check taken
     */
    private final AtomicLong lastTime = new AtomicLong(0);

    /**
     * Last written bytes number during last check interval
     */
    private long lastWrittenMessages = 0;

    /**
     * Last read bytes number during last check interval
     */
    private long lastReadMessages = 0;

    /**
     * Reset the accounting on Read and Write
     *
     * @param newLastTime the millisecond unix timestamp that we should be considered up-to-date for
     */
    public void resetAccounting(long newLastTime) {
        super.resetAccounting(newLastTime);
        synchronized (lastTime) {
            long interval = newLastTime - lastTime.getAndSet(newLastTime);
            if (interval == 0) {
                // nothing to do
                return;
            }
            lastReadMessages = currentReadMessages.getAndSet(0);
            lastWrittenMessages = currentWrittenMessages.getAndSet(0);
            // nb byte / checkInterval in ms * 1000 (1s)
            lastReadMessageThroughput = lastReadMessages * 1000 / interval;
            lastWriteMessageThroughput = lastWrittenMessages * 1000 / interval;

            maxReadThroughput = Math.max(lastReadThroughput(), maxReadThroughput);
            maxWriteThroughput = Math.max(lastWriteThroughput(), maxWriteThroughput);
            maxReadMessageThroughput = Math.max(lastReadMessageThroughput, maxReadMessageThroughput);
            maxWriteMessageThroughput = Math.max(lastWriteMessageThroughput, maxWriteMessageThroughput);
        }
    }

    public TrafficCounterExt(GlobalByteTrafficMonitor trafficShapingHandler,
                             ScheduledExecutorService executor, String name, long checkInterval) {
        super(trafficShapingHandler, executor, name, checkInterval);
        this.byteTrafficMonitor = trafficShapingHandler;
        messageTrafficMonitor = new GlobalMessageTrafficMonitor(this);
    }

    void messageReceived() {
        currentReadMessages.incrementAndGet();
        cumulativeReadMessages.incrementAndGet();
    }

    void messageWritten() {
        currentWrittenMessages.incrementAndGet();
        cumulativeWrittenMessages.incrementAndGet();
    }

    void channelRegistered() {
        cumulativeRegisteredChannels.incrementAndGet();
        currentRegisteredChannels.incrementAndGet();
    }

    void channelUnregistered() {
        cumulativeUnregisteredChannels.incrementAndGet();
        currentRegisteredChannels.decrementAndGet();
    }

    public long getLastWrittenMessages() {
        return lastWrittenMessages;
    }

    public long getLastReadMessages() {
        return lastReadMessages;
    }

    public long getCurrentWrittenMessages() {
        return currentWrittenMessages.get();
    }

    public long getCurrentReadMessages() {
        return currentReadMessages.get();
    }

    /**
     * @return the Time in millisecond of the last check as of System.currentTimeMillis()
     */
    public long getLastTime() {
        return lastTime.get();
    }

    /**
     * Reset both read and written cumulative bytes counters and the associated time.
     */
    public void resetCumulativeTime() {
        super.resetCumulativeTime();
        cumulativeReadMessages.set(0);
        cumulativeWrittenMessages.set(0);
        cumulativeRegisteredChannels.set(0);
        cumulativeUnregisteredChannels.set(0);
    }

    public long getCumulativeReadMessages() {
        return cumulativeReadMessages.get();
    }

    public long getCumulativeWrittenMessages() {
        return cumulativeWrittenMessages.get();
    }

    public long getLastWriteMessageThroughput() {
        return lastWriteMessageThroughput;
    }

    public long getLastReadMessageThroughput() {
        return lastReadMessageThroughput;
    }

    public long getMaxWriteThroughput() {
        return maxWriteThroughput;
    }

    public long getMaxReadThroughput() {
        return maxReadThroughput;
    }

    public long getMaxWriteMessageThroughput() {
        return maxWriteMessageThroughput;
    }

    public long getMaxReadMessageThroughput() {
        return maxReadMessageThroughput;
    }

    public GlobalByteTrafficMonitor getByteTrafficMonitor() {
        return byteTrafficMonitor;
    }

    public GlobalMessageTrafficMonitor getMessageTrafficMonitor() {
        return messageTrafficMonitor;
    }

    //TrafficCounter的一些属性不是以get开头,在JMX中只能以方法暴露,不能以属性暴露,这里转换一下

    /**
     * @return the name
     */
    public String getName() {
        return name;
    }

    public long getCheckInterval() {
        return checkInterval.get();
    }

    public long getLastReadThroughput() {
        return lastReadThroughput();
    }

    public long getLastWriteThroughput() {
        return lastWriteThroughput();
    }

    public long getLastReadBytes() {
        return lastReadBytes();
    }

    public long getLastWrittenBytes() {
        return lastWrittenBytes();
    }

    public long getCurrentReadBytes() {
        return currentReadBytes();
    }

    public long getCurrentWrittenBytes() {
        return currentWrittenBytes();
    }

    public long getCumulativeWrittenBytes() {
        return cumulativeWrittenBytes();
    }

    public long getCumulativeReadBytes() {
        return cumulativeReadBytes();
    }

    public long getLastCumulativeTime() {
        return lastCumulativeTime();
    }

    public long getCumulativeRegisteredChannels() {
        return cumulativeRegisteredChannels.get();
    }

    public long getCumulativeUnregisteredChannels() {
        return cumulativeUnregisteredChannels.get();
    }

    public long getCurrentRegisteredChannels() {
        return currentRegisteredChannels.get();
    }
}
