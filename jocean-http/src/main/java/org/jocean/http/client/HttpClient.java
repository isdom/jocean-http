/**
 * 
 */
package org.jocean.http.client;

import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.http.util.HttpHandlers;
import org.jocean.idiom.TerminateAware;

import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.Single;
import rx.functions.Action0;
import rx.functions.Action1;

/**
 * @author isdom
 *
 */
public interface HttpClient extends AutoCloseable {
    public void close();
    
    public interface ReadPolicy {
        public Single<?> whenToRead(final HttpInitiator initiator);
    }
    
    public interface HttpInitiator
    extends AutoCloseable, TerminateAware<HttpInitiator> {
        public Object transport();
        
        public Action0 closer();
        public void close();
        
        //  replace trafficCounter() by enable(...)
        public <T extends ChannelHandler> T enable(final HttpHandlers apply, final Object... args);
        
        public boolean isActive();
        public long unreadDurationInMs();
        public long readingDurationInMS();
        
        public void setReadPolicy(final ReadPolicy readPolicy);
        
        public void setFlushPerWrite(final boolean isFlushPerWrite);
        public void setWriteBufferWaterMark(final int low, final int high);
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
