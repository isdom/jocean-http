/**
 * 
 */
package org.jocean.http.server;

import io.netty.handler.codec.http.HttpObject;

import java.io.Closeable;
import java.net.SocketAddress;
import java.util.concurrent.Executor;

import org.jocean.http.Feature;

import rx.Observable;
import rx.Observer;
import rx.functions.Func0;

/**
 * @author isdom
 *
 */
public interface HttpServer extends Closeable {
    
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
    
    public interface HttpTrade {
        public Observable<? extends HttpObject> request();
        public Executor requestExecutor();
        public Observer<HttpObject> responseObserver();
        public Object transport();
    }
    
    public class TransportException extends RuntimeException {

        private static final long serialVersionUID = 1620281485023205687L;
        
        public TransportException(final String message) {
            super(message);
        }
        
        public TransportException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
