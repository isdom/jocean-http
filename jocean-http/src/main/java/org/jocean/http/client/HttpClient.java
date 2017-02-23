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
import org.jocean.idiom.rx.DoOnUnsubscribe;

import io.netty.handler.codec.http.HttpObject;
import io.netty.util.AttributeMap;
import rx.Observable;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public interface HttpClient extends Closeable {
    
    /**
     * 定义一次与http server的交互, 指定了远端地址和可选特性，并通过 requestProvider 创建 request
     * 当返回的Observable<? extends HttpObject> 实例被subscribe时,才基于上述指定的参数真正发起交互动作
     * @param remoteAddress 远端地址
     * @param requestProvider 要发送的HttpRequest (Object)* 的发生器，创建的 request 可使用传入的 DoOnUnsubscribe
     *          实例，控制 request 相关资源的释放，该 DoOnUnsubscribe 实例确保和特定的一次交互生命周期一致
     * @return Observable<HttpObject> response: Observable of HttpObject, 
     * 推送内容为 HttpResponse + 0~N (HttpContent)
     */
    public Observable<? extends HttpObject> defineInteraction(
            final SocketAddress remoteAddress, 
            //  wide type for custom http object , eg: HttpPostRequestEncoder used by DefaultSignalClient
            final Func1<DoOnUnsubscribe,Observable<? extends Object>> requestProvider,
            final Feature... features);
    
    /**
     * 定义一次与http server的交互, 指定了远端地址和要发送的request和可选特性
     * 当返回的Observable<? extends HttpObject> 实例被subscribe时,才基于上述指定的参数真正发起交互动作
     * @param remoteAddress 远端地址
     * @param request 要发送的HttpRequest (Object)*
     * @return Observable<HttpObject> response: Observable of HttpObject, 
     * 推送内容为 HttpResponse + 0~N (HttpContent)
     */
    public Observable<? extends HttpObject> defineInteraction(
            final SocketAddress remoteAddress, 
            //  wide type for custom http object , eg: HttpPostRequestEncoder used by DefaultSignalClient
            final Observable<? extends Object> request,
            final Feature... features);

    public interface InteractionBuilder {
        
        public InteractionBuilder remoteAddress(final SocketAddress remoteAddress);
        
        public InteractionBuilder request(final Observable<? extends Object> request);
        
        public InteractionBuilder requestProvider(final Func1<DoOnUnsubscribe,Observable<? extends Object>> requestProvider);
        
        public InteractionBuilder feature(final Feature... features);
        
        public Observable<? extends HttpObject> build();
    }
    
    public InteractionBuilder interaction();
    
    public interface HttpInitiator 
        extends AutoCloseable, TerminateAware<HttpInitiator>, AttributeMap {
        public void close();
        
        public TrafficCounter trafficCounter();
        
        public Object transport();
        public boolean isActive();
        
        public OutboundEndpoint outbound();
        public InboundEndpoint inbound();
    }
    
    public Observable<? extends HttpInitiator> initiator(
            final SocketAddress remoteAddress, 
            final Feature... features);
}
