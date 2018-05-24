/**
 *
 */
package org.jocean.http.client;

import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.http.Inbound;
import org.jocean.http.Outbound;
import org.jocean.http.ReadPolicy;
import org.jocean.http.TrafficCounter;
import org.jocean.http.WriteCtrl;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.TerminateAware;

import io.netty.handler.codec.http.HttpObject;
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
    extends Inbound, Outbound, AutoCloseable, TerminateAware<HttpInitiator> {
        public Observable<? extends DisposableWrapper<HttpObject>> defineInteraction(
                final Observable<? extends Object> request);

        public Object transport();

        public Action0 closer();
        @Override
        public void close();

        public TrafficCounter traffic();

        public boolean isActive();

        // from Outtraffic
        @Override
        public WriteCtrl writeCtrl();

        // from Intraffic
        @Override
        public void setReadPolicy(final ReadPolicy readPolicy);
    }

    public interface InitiatorBuilder {

        public InitiatorBuilder remoteAddress(final SocketAddress remoteAddress);

        public InitiatorBuilder remoteAddress(final Func0<SocketAddress> remoteAddressProvider);

        public InitiatorBuilder feature(final Feature... features);

        public Observable<? extends HttpInitiator> build();
    }

    public InitiatorBuilder initiator();
}
