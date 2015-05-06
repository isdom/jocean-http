/**
 * 
 */
package org.jocean.http.server;

import java.io.Closeable;
import java.net.SocketAddress;

import rx.Observable;

/**
 * @author isdom
 *
 */
public interface HttpServer extends Closeable {
    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress, 
            final InboundFeature.Applicable ... features);
}
