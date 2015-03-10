/**
 * 
 */
package org.jocean.http.client;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;

import java.io.Closeable;
import java.net.URI;

import rx.Observable;

/**
 * @author isdom
 *
 */
public interface HttpClient extends Closeable {
	
	/**
	 * 添加可发送的HttpContent作为HttpRequest的补充,
	 * 当sendRequest未被调用前content会被缓存,而sendRequest如已经被调用
	 * 则content会立即通过HttpClient实例被发送
	 * @param content 要发送的HttpContent实例
	 * @return HttpClient实例,可用于链式表达式
	 */
	public HttpClient appendContent(final HttpContent content);
	
	/**
	 * 通过Httpclient实例发送Http请求
	 * @param request 要发送的HttpRequest
	 * @return Observable<HttpObject> Observable of HttpObject, 
	 * 推送内容为 HttpResponse + 0~N (HttpContent)
	 */
	public Observable<HttpObject> sendRequest(final HttpRequest request);
	
	public interface Builder {
		public Observable<HttpClient> build(final URI uri);
	}
}
