/**
 *
 */
package org.jocean.http.server;

import java.io.Closeable;
import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.http.FullMessage;
import org.jocean.http.Inbound;
import org.jocean.http.Outbound;
import org.jocean.http.TrafficCounter;
import org.jocean.http.WriteCtrl;
import org.jocean.idiom.EndAware;

import io.netty.handler.codec.http.HttpRequest;
import rx.Completable;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Func0;

/**
 * @author isdom
 *
 */
public interface HttpServerBuilder extends Closeable {

    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress,
            final Feature ... features);

    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress,
            final Func0<Feature[]> featuresBuilder);

    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress,
            final Func0<Feature[]> featuresBuilder,
            final Feature ... features);

    public interface HttpTrade
        extends Inbound, Outbound, AutoCloseable, EndAware<HttpTrade> {

        public Completable inboundCompleted();

        public Observable<FullMessage<HttpRequest>> inbound();

        public Subscription outbound(final Observable<? extends Object> message);

        public Object transport();

        public Action0 closer();
        //  try to abort trade explicit
        @Override
        public void close();

        public TrafficCounter traffic();
        public boolean isActive();

        // from Outbound
        @Override
        public WriteCtrl writeCtrl();

        // from Inbound
        @Override
        public Intraffic intraffic();
    }
}
