package org.jocean.http.rosa.impl;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;

import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import rx.Subscriber;
import rx.functions.Action0;

class CachedResponse implements Action0 {
    private static final Logger LOG =
            LoggerFactory.getLogger(CachedResponse.class);

    private final Subscriber<? super Object> subscriber;
    private final List<HttpObject> httpObjects;
    private final Class<?> respCls;

    CachedResponse(
            final Class<?> respCls,
            final Subscriber<? super Object> subscriber,
            final List<HttpObject> httpObjects) {
        this.respCls = respCls;
        this.subscriber = subscriber;
        this.httpObjects = httpObjects;
    }

    @Override
    public void call() {
        final FullHttpResponse httpResp = retainFullHttpResponse(httpObjects);
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
                    final Object resp = JSON.parseObject(bytes, respCls);
                    subscriber.onNext(resp);
                } finally {
                    is.close();
                }
            } catch (Exception e) {
                LOG.warn("exception when parse response {}, detail:{}",
                        httpResp, ExceptionUtils.exception2detail(e));
                subscriber.onError(e);
            } finally {
                httpResp.release();
            }
        }
    }

    private FullHttpResponse retainFullHttpResponse(final List<HttpObject> httpObjs) {
        if (httpObjs.size()>0) {
            if (httpObjs.get(0) instanceof FullHttpResponse) {
                return ((FullHttpResponse)httpObjs.get(0)).retain();
            }
            
            final HttpResponse resp = (HttpResponse)httpObjs.get(0);
            final ByteBuf[] bufs = new ByteBuf[httpObjs.size()-1];
            for (int idx = 1; idx<httpObjs.size(); idx++) {
                bufs[idx-1] = ((HttpContent)httpObjs.get(idx)).content().retain();
            }
            return new DefaultFullHttpResponse(
                    resp.getProtocolVersion(), 
                    resp.getStatus(),
                    Unpooled.wrappedBuffer(bufs));
        } else {
            return null;
        }
    }
}
