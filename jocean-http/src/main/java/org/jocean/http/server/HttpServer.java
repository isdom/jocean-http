/**
 * 
 */
package org.jocean.http.server;

import io.netty.handler.codec.http.HttpObject;

import java.io.Closeable;
import java.net.SocketAddress;

import org.jocean.http.Feature;

import rx.Observable;
import rx.Observer;

/**
 * @author isdom
 *
 */
public interface HttpServer extends Closeable {
    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress, 
            final Feature ... features);
    
    public interface HttpTrade {
        public Observable<? extends HttpObject> request();
        public Observer<HttpObject> responseObserver();
    }
}
