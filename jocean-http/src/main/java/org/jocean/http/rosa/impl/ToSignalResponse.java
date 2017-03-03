package org.jocean.http.rosa.impl;

import java.beans.PropertyEditor;
import java.beans.PropertyEditorManager;
import java.lang.reflect.Field;
import java.util.List;

import javax.ws.rs.BeanParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.HeaderParam;

import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.util.CharsetUtil;
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

    ToSignalResponse(final Class<?> respType, final Class<?> bodyType) {
        this._respType = respType;
        this._bodyType = bodyType;
    }
    
    @Override
    public Observable<RESP> call(final Observable<HttpObject> source) {
        final HttpMessageHolder holder = new HttpMessageHolder();
        
        return source.compose(holder.<HttpObject>assembleAndHold())
                .flatMap(buildOnNext(), 
                        buildOnError(), 
                        buildOnCompleted(holder.httpMessageBuilder(RxNettys.BUILD_FULL_RESPONSE)))
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
                        final byte[] bytes = Nettys.dumpByteBufAsBytes(fullresp.content());
                        if (LOG.isTraceEnabled()) {
                            try {
                                LOG.trace("receive signal response: {}",
                                        new String(bytes, CharsetUtil.UTF_8));
                            } catch (Exception e) 
                            { // decode bytes as UTF-8 error, just ignore 
                            }
                        }
                        if (null != _respType) {
                            return Observable.just((RESP)convertResponseTo(fullresp, bytes, _respType));
                        }
                        if (null != _bodyType) {
                            final Object resp = JSON.parseObject(bytes, _bodyType);
                            return Observable.just((RESP)resp);
                        } else {
                            return Observable.just((RESP)bytes);
                        }
                    } catch (Exception e) {
                        LOG.warn("exception when parse response {}, detail:{}",
                                fullresp, ExceptionUtils.exception2detail(e));
                        return Observable.error(e);
                    } finally {
                        final boolean released = fullresp.release();
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("HttpResponse({}) released({}) from {}'s onCompleted", 
                                    fullresp, released, ToSignalResponse.this);
                        }
                    }
                }
                return Observable.error(new RuntimeException("invalid response"));
            }};
    }

    private static <RESP> RESP convertResponseTo(
            final FullHttpResponse fullresp, 
            final byte[] bodyBytes,
            final Class<?> respType) {
        try {
            @SuppressWarnings("unchecked")
            final RESP respBean = (RESP)respType.newInstance();
            
            assignAllParams(respBean, fullresp, bodyBytes);
            
            return respBean;
        } catch (Exception e) {
            return null;
        }
    }

    private static void assignAllParams(
            final Object bean,
            final HttpMessage httpmsg, 
            final byte[] bodyBytes) {
        assignHeaderParams(bean, httpmsg);
        assignBeanParams(bean, httpmsg, bodyBytes);
    }

    private static void assignBeanParams(
            final Object bean,
            final HttpMessage httpmsg, 
            final byte[] bodyBytes) {
        final String contentType = httpmsg.headers().get(HttpHeaderNames.CONTENT_TYPE);
        final Field[] beanfields = 
                ReflectUtils.getAnnotationFieldsOf(bean.getClass(), BeanParam.class);
        if (null != beanfields && beanfields.length > 0) {
            for (Field field : beanfields) {
                try {
                    final Object value = createBeanValue(bodyBytes, contentType, field);
                    if (null != value) {
                        field.set(bean, value);
                        assignAllParams(value, httpmsg, bodyBytes);
                    }
                } catch (Exception e) {
                    LOG.warn("exception when set bean value for field({}), detail:{}",
                            field.getName(), ExceptionUtils.exception2detail(e));
                }
            }
        }
    }

    private static Object createBeanValue(final byte[] bodyBytes, final String contentType, final Field beanField) {
        if ( !isMatchMediatype(contentType, beanField) ) {
            return null;
        }
        if (null != bodyBytes) {
            if (beanField.getType().equals(byte[].class)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("assign byte array with: {}", new String(bodyBytes, CharsetUtil.UTF_8));
                }
                return bodyBytes;
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("createBeanValue: {}", new String(bodyBytes, CharsetUtil.UTF_8));
                }
                return JSON.parseObject(bodyBytes, beanField.getType());
            }
        }
        try {
            return beanField.getType().newInstance();
        } catch (Throwable e) {
            LOG.warn("exception when create instance for type:{}, detail:{}",
                    beanField.getType(), ExceptionUtils.exception2detail(e));
            return null;
        }
    }

    private static boolean isMatchMediatype(final String contentType,
            final Field beanField) {
        if (null == contentType) {
            return true;
        }
        final Consumes consumes = beanField.getType().getAnnotation(Consumes.class);
        if (null == consumes) {
            return true;
        }
        for (String mediaType : consumes.value()) {
            if ( "*/*".equals(mediaType)) {
                return true;
            }
            if (contentType.startsWith(mediaType)) {
                return true;
            }
        }
        return false;
    }
    
    private static void assignHeaderParams(final Object bean, final HttpMessage httpmsg) {
        final Field[] headerfields = 
                ReflectUtils.getAnnotationFieldsOf(bean.getClass(), HeaderParam.class);
        if (null != headerfields && headerfields.length > 0) {
            for (Field field : headerfields) {
                injectValuesToField(
                    httpmsg.headers().getAll(field.getAnnotation(HeaderParam.class).value()), 
                    bean,
                    field);
            }
        }
    }

    private static void injectValuesToField(
            final List<String> values,
            final Object obj,
            final Field field) {
        //  TODO, if field is Collection or Array type, then assign all values to field
        if (null != values && values.size() > 0) {
            injectValueToField(values.get(0), obj, field);
        }
    }

    private static void injectValueToField(
            final String value,
            final Object obj,
            final Field field) {
        if (null != value) {
            try {
                // just check String field
                if (field.getType().equals(String.class)) {
                    field.set(obj, value);
                } else {
                    final PropertyEditor editor = PropertyEditorManager.findEditor(field.getType());
                    if (null != editor) {
                        editor.setAsText(value);
                        field.set(obj, editor.getValue());
                    }
                }
            } catch (Exception e) {
                LOG.warn("exception when set obj({}).{} with value({}), detail:{} ",
                        obj, field.getName(), value, ExceptionUtils.exception2detail(e));
            }
        }
    }
    
    private final Class<?> _respType;
    private final Class<?> _bodyType;
}
