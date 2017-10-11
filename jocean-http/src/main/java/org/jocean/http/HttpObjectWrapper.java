/**
 * 
 */
package org.jocean.http;

import java.util.ArrayList;
import java.util.List;

import org.jocean.idiom.DisposableWrapper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func1;

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
                    return obsrequest.toList().map(new Func1<List<HttpObjectWrapper>, FullHttpRequest>() {
                        @Override
                        public FullHttpRequest call(final List<HttpObjectWrapper> wrappers) {
                            if (wrappers.size() > 0 
                            && (wrappers.get(0).unwrap() instanceof HttpRequest) 
                            && (wrappers.get(wrappers.size()-1) instanceof LastHttpContent)) {
                                if (wrappers.get(0).unwrap() instanceof FullHttpRequest) {
                                    return ((FullHttpRequest)wrappers.get(0).unwrap()).retainedDuplicate();
                                }
                                
                                final List<ByteBuf> torelease = new ArrayList<>();
                                try {
                                    final HttpRequest req = (HttpRequest)wrappers.get(0).unwrap();
                                    final ByteBuf[] bufs = new ByteBuf[wrappers.size()-1];
                                    for (int idx = 1; idx<wrappers.size(); idx++) {
                                        bufs[idx-1] = ((HttpContent)wrappers.get(idx).unwrap()).content().retain();
                                        torelease.add(bufs[idx-1]);
                                    }
                                    final DefaultFullHttpRequest fullreq = new DefaultFullHttpRequest(
                                            req.protocolVersion(), 
                                            req.method(), 
                                            req.uri(), 
                                            Unpooled.wrappedBuffer(bufs));
                                    fullreq.headers().add(req.headers());
                                    //  ? need update Content-Length header field ?
                                    return fullreq;
                                } catch (Throwable e) {
                                    for (ByteBuf b : torelease) {
                                        b.release();
                                    }
                                    return null;
                                }
                            } else {
                                return null;
                            }
                        }});
                }};
        }
    }
}
