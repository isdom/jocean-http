package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GenericFutureListener;

import org.jocean.http.client.HttpClient.Feature;
import org.jocean.idiom.Features;

import rx.Subscriber;

final class RequestSubscriber extends Subscriber<HttpObject> {

	RequestSubscriber(
        final int featuresAsInt, 
        final Channel channel,
        final Subscriber<? super HttpObject> responseSubscriber) {
		this._featuresAsInt = featuresAsInt;
		this._channel = channel;
		this._responseSubscriber = responseSubscriber;
	}

	@Override
	public void onCompleted() {
		// TODO Auto-generated method stub
	}

	@Override
	public void onError(final Throwable e) {
		this._responseSubscriber.onError(e);
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
						_responseSubscriber.onError(future.cause());
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

	// private void doOnComplete() {
	// if (null!=this._subscriber) {
	// final Subscriber<? super HttpObject> subscriber = this._subscriber;
	// this._subscriber = null;
	// subscriber.onCompleted();
	// }
	// }
	//
	// private void doOnError(final Throwable cause) {
	// if (null!=this._subscriber) {
	// final Subscriber<? super HttpObject> subscriber = this._subscriber;
	// this._subscriber = null;
	// subscriber.onError(cause);
	// }
	// }

	private final int _featuresAsInt;
	private final Channel _channel;
	private final Subscriber<? super HttpObject> _responseSubscriber;
}
