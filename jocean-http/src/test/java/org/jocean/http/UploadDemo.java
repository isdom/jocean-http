package org.jocean.http;

import java.net.InetSocketAddress;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import rx.Completable;
import rx.Observable;
import rx.subscriptions.CompositeSubscription;
import rx.subscriptions.Subscriptions;

public class UploadDemo {

    private static final Logger LOG =
            LoggerFactory.getLogger(UploadDemo.class);

    static final byte[] content = new byte[8192];

    public static void main(final String[] args) throws InterruptedException {
        try (final HttpClient client = new DefaultHttpClient()) {
            final String host = "127.0.0.1";
            final int port = 9090;
            try(final HttpInitiator initiator = client.initiator().remoteAddress(new InetSocketAddress(host, port))
                .feature(Feature.ENABLE_LOGGING)
                .build()
                .toBlocking()
                .single()) {
                final DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/newrest/upload");
                request.headers().set(HttpHeaderNames.HOST, host);
                request.headers().set(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE);
                request.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_OCTET_STREAM);
                request.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);

                final CompositeSubscription cs = new CompositeSubscription();
                final Completable completable = Completable.create(subscriber ->
                    cs.add(Subscriptions.create(() -> subscriber.onCompleted())));

                final Observable<Object> upload = Observable.<Object>just(request, DoFlush.Util.flushOnly()).concatWith(
                        completable.andThen(Observable.<Object>just(
                                Unpooled.wrappedBuffer(content),
                                LastHttpContent.EMPTY_LAST_CONTENT)));

                initiator.defineInteraction(upload).subscribe( resp -> {
                    LOG.info("resp: {}", resp.message());
                    if (resp.message().status().code() == 100) {
                        cs.unsubscribe();
                    }
                    final MessageBody body = resp.body().toBlocking().single();
                    LOG.info("body: {}", body);
                    body.content().compose(MessageUtil.AUTOSTEP2DWB).subscribe(dwb -> {
                        LOG.info("dwb: {}", dwb);
                    });
                });

                Thread.currentThread().sleep(1000 * 1000);
            }
        }
    }

}
