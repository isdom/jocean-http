/**
 * 
 */
package org.jocean.http.client;

import io.netty.handler.codec.http.HttpObject;

import java.io.Closeable;
import java.net.SocketAddress;

import rx.Observable;

/**
 * @author isdom
 *
 */
public interface HttpClient extends Closeable {
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
            final Observable<? extends Object> request,
            final OutboundFeature.Applicable... features);
}
