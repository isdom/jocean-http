/**
 * 
 */
package org.jocean.http.client;

import java.io.Closeable;
import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.idiom.rx.DoOnUnsubscribe;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public interface HttpClient extends Closeable {
    
    /**
     * 定义一次与http server的交互, 指定了远端地址和可选特性，并通过 requestProvider 创建 request
     * 当返回的Observable<? extends Object> 实例被subscribe时,才基于上述指定的参数真正发起交互动作
     * @param remoteAddress 远端地址
     * @param requestProvider 要发送的HttpRequest (Object)* 的发生器，创建的 request 可使用传入的 DoOnUnsubscribe
     *          实例，控制 request 相关资源的释放，该 DoOnUnsubscribe 实例确保和特定的一次交互生命周期一致
     * @return Observable<Object> response: Observable of HttpObject, 
     * 推送内容为 HttpResponse + 0~N (HttpContent)
     */
    public Observable<? extends HttpObject> defineInteraction(
            final SocketAddress remoteAddress, 
            //  wide type for custom http object , eg: HttpPostRequestEncoder used by DefaultSignalClient
            final Func1<DoOnUnsubscribe,Observable<? extends Object>> requestProvider,
            final Feature... features);
    
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
