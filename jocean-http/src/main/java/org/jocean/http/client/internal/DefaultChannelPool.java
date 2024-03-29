package org.jocean.http.client.internal;

import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.jocean.http.util.HandlerPrototype;
import org.jocean.http.util.Nettys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import rx.functions.Action0;

public class DefaultChannelPool extends AbstractChannelPool {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultChannelPool.class);

    public DefaultChannelPool(final HandlerPrototype onChannelInactive) {
        this._onChannelInactive = onChannelInactive;
    }

    @Override
    protected Channel findActiveChannel(final SocketAddress address) {
        final Queue<Channel> channels = getChannels(address);
        if (null == channels) {
            return null;
        }
        Channel channel = null;
        do {
            channel = channels.poll();
            if (null != channel) {
                if (channel.isActive()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("fetch active channel({}) from pool, try to reuse.", channel);
                    }
                    // 移除对 channel inactive 时的缺省处理 Handler
                    Nettys.removeHandler(channel.pipeline(), this._onChannelInactive);
                    break;
                } else {
                    LOG.debug("fetch inactive channel({}) from pool, drop it and fetch next from pool.", channel);
                    channel.close();
                }
            }
        } while (null != channel);
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
            && Nettys.isChannelReady(channel)) {
            final SocketAddress address = channel.remoteAddress();
            if (null!=address) {
                final Queue<Channel> channels = getOrCreateChannels(address);
                channels.add(channel);
                Nettys.applyHandler(channel.pipeline(), _onChannelInactive,
                    (Action0)() -> {
                            channels.remove(channel);
                            channel.close();
                            LOG.debug("removeChannel: channel({}) inactive, so remove from pool.", channel);
                        });
                LOG.debug("recycleChannel: channel({}) save to queue for ({}), can be reused.", channel, address);
                return  true;
            }
        }

        channel.close();
        LOG.debug("recycleChannel: try recycle channel({}), BUT it has been closed.", channel);
        return false;
    }

    private final ConcurrentMap<SocketAddress, Queue<Channel>> _channels =
            new ConcurrentHashMap<>();
    private final HandlerPrototype _onChannelInactive;
}
