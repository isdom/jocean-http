/**
 * 
 */
package org.jocean.http.server;

import java.io.Closeable;
import java.net.SocketAddress;
import java.util.concurrent.Executor;

import org.jocean.http.Feature;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
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
        public boolean isActive();
        public boolean isEndedWithKeepAlive();
        //  try to abort trade explicit
        public void abort();
        public Observable<? extends HttpObject> inboundRequest();
        public Subscription outboundResponse(final Observable<? extends HttpObject> response);
        public boolean readyforOutboundResponse();
        public Executor requestExecutor();
        public Object transport();
        public HttpTrade doOnClosed(final Action1<HttpTrade> onClosed);
        public void undoOnClosed(final Action1<HttpTrade> onClosed);
        public CachedHttpTrade cached(final int maxBlockSize);
    }
    
    public interface CachedHttpTrade extends HttpTrade {
        public FullHttpRequest retainFullHttpRequest();
        public int currentBlockSize();
        public int currentBlockCount();
        public int requestHttpObjCount();
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
