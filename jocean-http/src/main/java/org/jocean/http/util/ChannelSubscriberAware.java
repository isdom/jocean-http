package org.jocean.http.util;

import io.netty.channel.Channel;
import rx.Subscriber;

public interface ChannelSubscriberAware {
    
    public void setChannelSubscriber(final Subscriber<? super Channel> subscriber);
}
