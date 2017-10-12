/**
 * 
 */
package org.jocean.http;

import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public interface HttpObjectWrapper extends DisposableWrapper<HttpObject> {
    public static class Util {
        private static final Func1<HttpObjectWrapper, HttpObject> _UNWRAP = 
        new Func1<HttpObjectWrapper, HttpObject>() {
            @Override
            public HttpObject call(final HttpObjectWrapper wrapper) {
                return wrapper.unwrap();
            }};
            
        public static Transformer<HttpObjectWrapper,  FullHttpRequest> toFullRequest() {
            return new Transformer<HttpObjectWrapper,  FullHttpRequest>() {
                @Override
                public Observable<FullHttpRequest> call(final Observable<HttpObjectWrapper> obsrequest) {
                    return obsrequest.map(_UNWRAP).toList().map(RxNettys.httpobjs2fullreq());
                }};
        }
        
        public static Transformer<HttpObjectWrapper,  FullHttpResponse> toFullResponse() {
            return new Transformer<HttpObjectWrapper,  FullHttpResponse>() {
                @Override
                public Observable<FullHttpResponse> call(final Observable<HttpObjectWrapper> obsresponse) {
                    return obsresponse.map(_UNWRAP).toList().map(RxNettys.httpobjs2fullresp());
                }};
        }
    }
}
