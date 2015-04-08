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
    public Observable<HttpTrade> create(final SocketAddress localAddress);
}
