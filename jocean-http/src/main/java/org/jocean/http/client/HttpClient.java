/**
 * 
 */
package org.jocean.http.client;

import java.io.Closeable;
import java.net.SocketAddress;

import org.jocean.http.Feature;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;

/**
 * @author isdom
 *
 */
public interface HttpClient extends Closeable {
    
    //  TODO add new interface method to impl
    //  after channel pushed, begin to assemble http request message
    /**
     * 定义一次与http server的交互, 指定了远端地址和要发送的request和可选特性
     * 当返回的Observable<? extends Object> 实例被subscribe时,才基于上述指定的参数真正发起交互动作
     * @param remoteAddress 远端地址
     * @param request 要发送的HttpRequest (Object)*
     * @return Observable<Object> response: Observable of HttpObject, 
     * 推送内容为 HttpResponse + 0~N (HttpContent)
     */
    public Observable<? extends HttpObject> defineInteraction(
            final SocketAddress remoteAddress, 
            //  wide type for custom http object , eg: HttpPostRequestEncoder used by DefaultSignalClient
            final Observable<? extends Object> request,
            final Feature... features);
}
