package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;

import java.net.SocketAddress;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.functions.Action1;

public class DefaultChannelPool extends AbstractChannelPool {

    private static final Logger LOG =
            LoggerFactory.getLogger(TestChannelPool.class);

    public DefaultChannelPool(final ChannelCreator channelCreator) {
        super(channelCreator);
    }

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
    public boolean recycleChannel(final SocketAddress address, final Channel channel) {
        if (null == channel.attr(TRANSACTIONING).get()) {
            try {
                Observable.from(channel.pipeline()).subscribe(new Action1<Entry<String,ChannelHandler>>(){
                    @Override
                    public void call(Entry<String, ChannelHandler> entry) {
                        LOG.info("recycleChannel({}) handler:{}/{}", channel, entry.getKey(), entry.getValue());
                    }});
            } catch (Throwable e) {
                LOG.error("recycleChannel: {}", e);
            }
            
            getOrCreateChannels(address).add(channel);
            return true;
        }
        else {
            return false;
        }
    }

}
