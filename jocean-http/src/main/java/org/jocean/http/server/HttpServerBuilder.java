/**
 * 
 */
package org.jocean.http.server;

import java.io.Closeable;
import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.http.util.HttpMessageHolder;

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
    
    public interface HttpTrade {
        public int retainedInboundMemory();
        public boolean isActive();
        public boolean isEndedWithKeepAlive();
        //  try to abort trade explicit
        public void abort();
        public Observable<? extends HttpObject> inboundRequest();
        public HttpMessageHolder inboundHolder();
        public Subscription outboundResponse(final Observable<? extends HttpObject> response);
        public boolean readyforOutboundResponse();
        public Object transport();
        public HttpTrade addCloseHook(final Action1<HttpTrade> onClosed);
        public void removeCloseHook(final Action1<HttpTrade> onClosed);
    }
}
