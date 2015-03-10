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
	
	public void appendContent(final HttpContent content);
	
	public Observable<HttpObject> sendRequest(final HttpRequest request);
	
	public interface Builder {
		public Observable<HttpClient> build(final URI uri);
	}
}
