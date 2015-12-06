package org.jocean.http.rosa.impl;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;

import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import rx.Subscriber;
import rx.functions.Action0;

class CachedResponse implements Action0 {
    private static final Logger LOG =
            LoggerFactory.getLogger(CachedResponse.class);

    private final Subscriber<? super Object> _subscriber;
    private final List<HttpObject> _httpObjects;
    private final Class<?> _respCls;

    CachedResponse(
            final Class<?> respCls,
            final Subscriber<? super Object> subscriber,
            final List<HttpObject> httpObjects) {
        this._respCls = respCls;
        this._subscriber = subscriber;
        this._httpObjects = httpObjects;
    }

    @Override
    public void call() {
        final FullHttpResponse httpResp = RxNettys.retainAsFullHttpResponse(this._httpObjects);
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
                    _subscriber.onNext(resp);
                } finally {
                    is.close();
                }
            } catch (Exception e) {
                LOG.warn("exception when parse response {}, detail:{}",
                        httpResp, ExceptionUtils.exception2detail(e));
                _subscriber.onError(e);
            } finally {
                httpResp.release();
            }
        }
    }
}
