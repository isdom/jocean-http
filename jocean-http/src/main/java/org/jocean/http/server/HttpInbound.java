/**
 * 
 */
package org.jocean.http.server;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;

/**
 * @author isdom
 *
 */
public interface HttpInbound {
    public Observable<HttpObject> request();
}
