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
     * 通过Httpclient实例发送Http请求
     * @param remoteAddress 远端地址
     * @param request 要发送的HttpRequest (HttpContent)*
     * @return Observable<HttpObject> response: Observable of HttpObject, 
     * 推送内容为 HttpResponse + 0~N (HttpContent)
     */
    public Observable<HttpObject> sendRequest(
            final SocketAddress remoteAddress, 
            final Observable<? extends HttpObject> request,
            final OutboundFeature.Applicable... features);
}
