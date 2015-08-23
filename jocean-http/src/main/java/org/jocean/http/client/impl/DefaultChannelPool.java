package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;

import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultChannelPool extends AbstractChannelPool {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultChannelPool.class);

    private static final AttributeKey<Object> TRANSACTIONING = AttributeKey.valueOf("__TRANSACTIONING");
    private static final Object OK = new Object();
    private static final AttributeKey<Boolean> KEEPALIVE = AttributeKey.valueOf("__KEEPALIVE");
    
    @Override
    public void beforeSendRequest(final Channel channel, final HttpRequest request) {
        //  当Channel被重用，但由于source cancel等情况，没有发送过request
        //  则此时仍然可以被再次回收
        channel.attr(TRANSACTIONING).set(OK);
        channel.attr(KEEPALIVE).set(HttpHeaders.isKeepAlive(request));
    }

    @Override
    public void afterReceiveLastContent(final Channel channel) {
        if (channel.attr(KEEPALIVE).get()) {
            channel.attr(TRANSACTIONING).remove();
        }
    }

    @Override
    protected Channel reuseChannel(final SocketAddress address) {
        final Queue<Channel> channels = getChannels(address);
        if (null == channels) {
            return null;
        }
        Channel channel = null;
        do {
            channel = channels.poll();
        } while (null != channel && !channel.isActive());
        return channel;
    }

    protected Queue<Channel> getChannels(final SocketAddress address) {
        return this._channels.get(address);
    }
    
    protected Queue<Channel> getOrCreateChannels(final SocketAddress address) {
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
    
    @Override
    public boolean recycleChannel(final Channel channel) {
        if (channel.isActive() 
            && ChannelPool.Util.isChannelReady(channel)
            && null == channel.attr(TRANSACTIONING).get()) {
//            try {
//                Observable.from(channel.pipeline()).subscribe(new Action1<Entry<String,ChannelHandler>>(){
//                    @Override
//                    public void call(Entry<String, ChannelHandler> entry) {
//                        LOG.info("recycleChannel({}) handler:{}/{}", channel, entry.getKey(), entry.getValue());
//                    }});
//            } catch (Throwable e) {
//                LOG.error("recycleChannel: {}", e);
//            }
            final SocketAddress address = channel.remoteAddress();
            if (null!=address) {
                getOrCreateChannels(address).add(channel);
                LOG.info("channel({}) save to queue for ({}), can be reused.", channel, address);
                return  true;
            }
        }
        
        channel.close();
        LOG.info("channel({}) has been closed.", channel);
        return false;
    }

    private final ConcurrentMap<SocketAddress, Queue<Channel>> _channels = 
            new ConcurrentHashMap<>();
}
