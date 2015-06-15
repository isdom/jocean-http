package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import rx.Subscriber;

interface ChannelSubscriberAware {
    
    public void setChannelSubscriber(final Subscriber<? super Channel> subscriber);
}
