/**
 * 
 */
package org.jocean.http.server;

import java.io.Closeable;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObject;
import rx.Observable;

/**
 * @author isdom
 *
 */
public interface HttpTrade extends Closeable {
    public Observable<? extends HttpObject> request();
    public FullHttpRequest retainFullHttpRequest();
    public void response(final Observable<? extends HttpObject> response);
}
