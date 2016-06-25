package org.jocean.http.rosa.impl;

import java.io.InputStream;
import java.nio.charset.Charset;

import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func0;
import rx.functions.Func1;

public class ToSignalResponse<RESP> implements Transformer<HttpObject, RESP> {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(ToSignalResponse.class);
    
    private Func1<HttpObject, Observable<RESP>> buildOnNext() {
        return new Func1<HttpObject, Observable<RESP>>() {
        @Override
        public Observable<RESP> call(final HttpObject input) {
            return Observable.empty();
        }};
    }
    
    private Func1<Throwable, Observable<RESP>> buildOnError() {
        return new Func1<Throwable, Observable<RESP>>() {
        @Override
        public Observable<RESP> call(final Throwable e) {
            return Observable.error(e);
        }};
    }

    ToSignalResponse(final Class<?> respCls) {
        this._respCls = respCls;
    }
    
    @Override
    public Observable<RESP> call(final Observable<HttpObject> source) {
        final HttpMessageHolder holder = new HttpMessageHolder(0);
        
        return source.compose(holder.assembleAndHold())
                .flatMap(buildOnNext(), buildOnError(), 
                    buildOnCompleted(holder.bindHttpObjects(RxNettys.BUILD_FULL_RESPONSE)))
                .doAfterTerminate(holder.release())
                .doOnUnsubscribe(holder.release());
    }

    private Func0<Observable<RESP>> buildOnCompleted(
            final Func0<FullHttpResponse> getHttpResponse) {
        return new Func0<Observable<RESP>>() {
            @SuppressWarnings("unchecked")
            @Override
            public Observable<RESP> call() {
                final FullHttpResponse fullresp = getHttpResponse.call();
                if (null!=fullresp) {
                    try {
                        final InputStream is = new ByteBufInputStream(fullresp.content());
                        try {
                            final byte[] bytes = new byte[is.available()];
                            @SuppressWarnings("unused")
                            final int readed = is.read(bytes);
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("receive signal response: {}",
                                        new String(bytes, Charset.forName("UTF-8")));
                            }
                            final Object resp = JSON.parseObject(bytes, _respCls);
                            return Observable.just((RESP)resp);
                        } finally {
                            is.close();
                        }
                    } catch (Exception e) {
                        LOG.warn("exception when parse response {}, detail:{}",
                                fullresp, ExceptionUtils.exception2detail(e));
                        Observable.error(e);
                    } finally {
                        fullresp.release();
                    }
                }
                return Observable.error(new RuntimeException("invalid response"));
            }};
    }

    private final Class<?> _respCls;
}
