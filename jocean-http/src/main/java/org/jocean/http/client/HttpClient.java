/**
 * 
 */
package org.jocean.http.client;

import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.http.IntrafficController;
import org.jocean.http.ReadPolicy;
import org.jocean.http.TrafficCounter;
import org.jocean.http.WritePolicy;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.TerminateAware;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.functions.Action0;

/**
 * @author isdom
 *
 */
public interface HttpClient extends AutoCloseable {
    public void close();
    
    public interface HttpInitiator
    extends IntrafficController, AutoCloseable, TerminateAware<HttpInitiator> {
        public Object transport();
        
        public Action0 closer();
        public void close();
        
        public TrafficCounter traffic();
        
        public boolean isActive();

        public Observable<? extends HttpObject> defineInteraction(
                final Observable<? extends Object> request);
        
        public Observable<? extends HttpObject> defineInteraction(
                final Observable<? extends Object> request, final WritePolicy writePolicy);
        
        public Observable<? extends DisposableWrapper<HttpObject>> defineInteraction2(
                final Observable<? extends Object> request);
        
        public Observable<? extends DisposableWrapper<HttpObject>> defineInteraction2(
                final Observable<? extends Object> request, final WritePolicy writePolicy);
        
        // from IntrafficController
        public void setReadPolicy(final ReadPolicy readPolicy);
    }

    public interface InitiatorBuilder {
        
        public InitiatorBuilder remoteAddress(final SocketAddress remoteAddress);
        
        public InitiatorBuilder feature(final Feature... features);
        
        public Observable<? extends HttpInitiator> build();
    }
    
    public InitiatorBuilder initiator();
}
