/**
 * 
 */
package org.jocean.http;

import org.jocean.idiom.DisposableWrapper;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.Observable.Transformer;

/**
 * @author isdom
 *
 */
public interface HttpObjectWrapper extends DisposableWrapper<HttpObject> {
    public static class Util {
        public static Transformer<HttpObjectWrapper,  FullHttpRequest> toFullRequest() {
            return new Transformer<HttpObjectWrapper,  FullHttpRequest>() {
                @Override
                public Observable<FullHttpRequest> call(final Observable<HttpObjectWrapper> obsrequest) {
                    //  TBD
                    return null;
                }};
        }
    }
}
