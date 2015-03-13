package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GenericFutureListener;

import org.jocean.http.client.HttpClient.Feature;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Features;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Subscriber;
import rx.functions.Action1;

final class RequestSubscriber extends Subscriber<HttpObject> {

    private static final Logger LOG =
            LoggerFactory.getLogger(RequestSubscriber.class);
    RequestSubscriber(
        final int featuresAsInt, 
        final Channel channel,
        final Action1<Throwable> onError) {
        this._featuresAsInt = featuresAsInt;
        this._channel = channel;
        this._onError = onError;
    }

    @Override
    public void onCompleted() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("RequestSubscriber for channel:({}) onCompleted.", this._channel);
        }
    }

    @Override
    public void onError(final Throwable e) {
        LOG.warn("RequestSubscriber for channel:({}) onError:{}.", 
                this._channel, ExceptionUtils.exception2detail(e));
        this._onError.call(e);
    }

    @Override
    public void onNext(final HttpObject msg) {
        addAcceptEncodingHeaderIfNeed(msg);
        this._channel.writeAndFlush(ReferenceCountUtil.retain(msg))
            .addListener(new GenericFutureListener<ChannelFuture>() {
                @Override
                public void operationComplete(final ChannelFuture future)
                        throws Exception {
                    if (!future.isSuccess()) {
                        _onError.call(future.cause());
                    }
                }
            });
    }

    private void addAcceptEncodingHeaderIfNeed(final HttpObject msg) {
        if (isCompressEnabled() && msg instanceof HttpRequest) {
            HttpHeaders.addHeader((HttpRequest) msg,
                HttpHeaders.Names.ACCEPT_ENCODING, HttpHeaders.Values.GZIP
                    + "," + HttpHeaders.Values.DEFLATE);
        }
    }

    private boolean isCompressEnabled() {
        return !Features.isEnabled(this._featuresAsInt, Feature.DisableCompress);
    }

    private final int _featuresAsInt;
    private final Channel _channel;
    private final Action1<Throwable> _onError;
}
