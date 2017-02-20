/**
 * 
 */
package org.jocean.http.server;

import java.io.Closeable;
import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.http.InboundEndpoint;
import org.jocean.http.TrafficCounter;

import io.netty.handler.codec.http.HttpObject;
import io.netty.util.AttributeMap;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
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
    
    public interface HttpTrade extends AttributeMap {
        public TrafficCounter trafficCounter();
        public boolean isEndedWithKeepAlive();
        public Subscription outboundResponse(final Observable<? extends HttpObject> response);
        public boolean readyforOutboundResponse();
        public Object transport();
        
        //  try to abort trade explicit
        public void abort();
        public boolean isActive();
        public HttpTrade addCloseHook(final Action1<HttpTrade> onClosed);
        public void removeCloseHook(final Action1<HttpTrade> onClosed);
        
        public InboundEndpoint inbound();
    }
}
