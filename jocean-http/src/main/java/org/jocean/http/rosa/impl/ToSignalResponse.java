package org.jocean.http.rosa.impl;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action0;
import rx.functions.Func0;
import rx.functions.Func1;

public class ToSignalResponse implements Transformer<Object, Object> {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(ToSignalResponse.class);

    ToSignalResponse(final Class<?> respCls) {
        this._respCls = respCls;
    }
    
    @Override
    public Observable<Object> call(final Observable<Object> source) {
        final List<HttpObject> httpObjects = new ArrayList<>();
        
        return source.flatMap(new Func1<Object, Observable<Object>>() {
            @Override
            public Observable<Object> call(final Object input) {
                if (input instanceof HttpObject) {
                    httpObjects.add(ReferenceCountUtil.retain((HttpObject)input));
                    return Observable.empty();
                } else {
                    return Observable.just(input);
                }
            }},
            new Func1<Throwable, Observable<Object>>() {
                @Override
                public Observable<Object> call(final Throwable e) {
                    return Observable.error(e);
                }},
            new Func0<Observable<Object>>() {
                @Override
                public Observable<Object> call() {
                    final FullHttpResponse httpResp = RxNettys.retainAsFullHttpResponse(httpObjects);
                    if (null!=httpResp) {
                        try {
                            final InputStream is = new ByteBufInputStream(httpResp.content());
                            try {
                                final byte[] bytes = new byte[is.available()];
                                @SuppressWarnings("unused")
                                final int readed = is.read(bytes);
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("receive signal response: {}",
                                            new String(bytes, Charset.forName("UTF-8")));
                                }
                                final Object resp = JSON.parseObject(bytes, _respCls);
                                return Observable.just(resp);
                            } finally {
                                is.close();
                            }
                        } catch (Exception e) {
                            LOG.warn("exception when parse response {}, detail:{}",
                                    httpResp, ExceptionUtils.exception2detail(e));
                            Observable.error(e);
                        } finally {
                            httpResp.release();
                        }
                    }
                    return Observable.error(new RuntimeException("invalid response"));
                }}
            )
        .doOnTerminate(new Action0() {
            @Override
            public void call() {
                RxNettys.releaseObjects(httpObjects);
            }})
        .doOnUnsubscribe(new Action0() {
            @Override
            public void call() {
                RxNettys.releaseObjects(httpObjects);                            
            }});
    }

    private final Class<?> _respCls;
}
