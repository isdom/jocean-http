/**
 * 
 */
package org.jocean.http.server;

import java.io.Closeable;
import java.net.SocketAddress;

import org.jocean.http.Feature;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func0;

/**
 * @author isdom
 *
 */
//  TODO, rename this interface to creator or sth. else
//  it's !NOT! server BUT server generator
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
        public boolean isActive();
        public boolean isEndedWithKeepAlive();
        //  try to abort trade explicit
        public void abort();
        public Observable<? extends HttpObject> inboundRequest();
        public Subscription outboundResponse(final Observable<? extends HttpObject> response);
        public Subscription outboundResponse(final Observable<? extends HttpObject> response,
                    final Action1<Throwable> onError);
        public boolean readyforOutboundResponse();
        public Object transport();
        public HttpTrade doOnClosed(final Action1<HttpTrade> onClosed);
        public void undoOnClosed(final Action1<HttpTrade> onClosed);
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
