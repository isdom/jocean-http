/**
 * 
 */
package org.jocean.http.client;

import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.http.InboundEndpoint;
import org.jocean.http.OutboundEndpoint;
import org.jocean.http.TrafficCounter;
import org.jocean.http.util.APPLY;
import org.jocean.idiom.TerminateAware;

import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpObject;
import io.netty.util.AttributeMap;
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
    
    public interface HttpInitiator 
        extends AutoCloseable, TerminateAware<HttpInitiator>, AttributeMap {
        public Action0 closer();
        public void close();
        
        public TrafficCounter trafficCounter();
        
        public Object transport();
        public boolean isActive();
        
        public OutboundEndpoint outbound();
        public InboundEndpoint inbound();
    }
    
    public interface InitiatorBuilder {
        
        public InitiatorBuilder remoteAddress(final SocketAddress remoteAddress);
        
        public InitiatorBuilder feature(final Feature... features);
        
        public Observable<? extends HttpInitiator> build();
    }
    
    public InitiatorBuilder initiator();

    public interface ReadPolicy {
        public Single<?> whenToRead(final HttpInitiator0 initiator);
    }
    
    public interface HttpInitiator0
    extends AutoCloseable, TerminateAware<HttpInitiator0> {
        public Action0 closer();
        public void close();
        
        //  replace trafficCounter() by enable(...)
        public <T extends ChannelHandler> T enable(final APPLY apply, final Object... args);
        
        public boolean isActive();
        public long unreadDurationInMs();
        public long readingDurationInMS();
        
        public void setReadPolicy(final ReadPolicy readPolicy);
        
        public void setFlushPerWrite(final boolean isFlushPerWrite);
        public void setWriteBufferWaterMark(final int low, final int high);
        public Action0 doOnSended(final Action1<Object> onSended);
        
        public Observable<? extends HttpObject> defineInteraction(
                final Observable<? extends Object> request);
    }

    public interface InitiatorBuilder0 {
        
        public InitiatorBuilder0 remoteAddress(final SocketAddress remoteAddress);
        
        public InitiatorBuilder0 feature(final Feature... features);
        
        public Observable<? extends HttpInitiator0> build();
    }
    
    public InitiatorBuilder0 initiator0();
}
