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
        public boolean isActive();
        public boolean isEndedWithKeepAlive();
        //  try to abort trade explicit
        public void abort();
        public Subscription outboundResponse(final Observable<? extends HttpObject> response);
        public boolean readyforOutboundResponse();
        public Object transport();
        public HttpTrade addCloseHook(final Action1<HttpTrade> onClosed);
        public void removeCloseHook(final Action1<HttpTrade> onClosed);
        
        public InboundEndpoint inbound();
        
//        public int retainedInboundMemory();
//        public Observable<? extends HttpObject> inboundRequest();
//        public HttpMessageHolder inboundHolder();
//        public void setInboundAutoRead(final boolean autoRead);
//        public void readInbound();
//        public HttpTrade addInboundReadCompleteHook(final Action1<HttpTrade> onReadComplete);
//        public void removeInboundReadCompleteHook(final Action1<HttpTrade> onReadComplete);
//        
//        public long timeToLive();
    }
}
