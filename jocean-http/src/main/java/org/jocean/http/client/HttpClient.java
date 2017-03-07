/**
 * 
 */
package org.jocean.http.client;

import java.io.Closeable;
import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.http.InboundEndpoint;
import org.jocean.http.OutboundEndpoint;
import org.jocean.http.TrafficCounter;
import org.jocean.idiom.TerminateAware;

import io.netty.util.AttributeMap;
import rx.Observable;

/**
 * @author isdom
 *
 */
public interface HttpClient extends Closeable {
    public interface HttpInitiator 
        extends AutoCloseable, TerminateAware<HttpInitiator>, AttributeMap {
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
}
