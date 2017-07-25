/**
 * 
 */
package org.jocean.http.client;

import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.http.ReadPolicy;
import org.jocean.http.TrafficCounter;
import org.jocean.idiom.TerminateAware;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;

/**
 * @author isdom
 *
 */
public interface HttpClient extends AutoCloseable {
    public void close();
    
    public interface HttpInitiator
    extends AutoCloseable, TerminateAware<HttpInitiator> {
        public Object transport();
        
        public Action0 closer();
        public void close();
        
//        public <T extends ChannelHandler> T enable(final HttpHandlers handlerType, final Object... args);
        
        public TrafficCounter traffic();
        
        public boolean isActive();
        
        public void setReadPolicy(final ReadPolicy readPolicy);
        
        public void setFlushPerWrite(final boolean isFlushPerWrite);
        public void setWriteBufferWaterMark(final int low, final int high);
        
        public Observable<Boolean> writability();
        public Observable<Object> sended();
        public void setOnSended(final Action1<Object> onSended);
        
        public Observable<? extends HttpObject> defineInteraction(
                final Observable<? extends Object> request);
    }

    public interface InitiatorBuilder {
        
        public InitiatorBuilder remoteAddress(final SocketAddress remoteAddress);
        
        public InitiatorBuilder feature(final Feature... features);
        
        public Observable<? extends HttpInitiator> build();
    }
    
    public InitiatorBuilder initiator();
}
