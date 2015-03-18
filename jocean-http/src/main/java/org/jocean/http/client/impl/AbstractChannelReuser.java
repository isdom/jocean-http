package org.jocean.http.client.impl;

import io.netty.channel.Channel;

import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

public abstract class AbstractChannelReuser implements ChannelReuser {

    @Override
    public Channel retainChannelFromPool(SocketAddress address) {
        final Queue<Channel> channels = getChannels(address);
        return (null!=channels) ? channels.poll() : null;
    }

    @Override
    public void releaseChannelToPool(SocketAddress address, Channel channel) {
        getOrCreateChannels(address).add(channel);
    }

    private Queue<Channel> getOrCreateChannels(final SocketAddress address) {
        final Queue<Channel> channels = this._channels.get(address);
        if (null == channels) {
            final Queue<Channel> newChannels = new ConcurrentLinkedQueue<Channel>();
            final Queue<Channel> previous = this._channels.putIfAbsent(address, newChannels);
            return  null!=previous ? previous : newChannels;
        }
        else {
            return channels;
        }
    }
    
    private Queue<Channel> getChannels(final SocketAddress address) {
        return this._channels.get(address);
    }
    
    private final ConcurrentMap<SocketAddress, Queue<Channel>> _channels = 
            new ConcurrentHashMap<>();
}
