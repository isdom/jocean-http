/**
 *
 */
package org.jocean.http.client;

import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.http.FullMessage;
import org.jocean.http.Inbound;
import org.jocean.http.Outbound;
import org.jocean.http.TrafficCounter;
import org.jocean.http.WriteCtrl;
import org.jocean.idiom.EndAware;

import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Func0;

/**
 * @author isdom
 *
 */
public interface HttpClient extends AutoCloseable {
    @Override
    public void close();

    public interface HttpInitiator
    extends Inbound, Outbound, AutoCloseable, EndAware<HttpInitiator> {
        public Observable<FullMessage<HttpResponse>> defineInteraction(final Observable<? extends Object> request);

        public Object transport();

        public Action0 closer();
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

    public interface InitiatorBuilder {

        public InitiatorBuilder remoteAddress(final SocketAddress remoteAddress);

        public InitiatorBuilder remoteAddress(final Func0<SocketAddress> remoteAddressProvider);

        public InitiatorBuilder feature(final Feature... features);

        public Observable<? extends HttpInitiator> build();
    }

    public InitiatorBuilder initiator();
}
