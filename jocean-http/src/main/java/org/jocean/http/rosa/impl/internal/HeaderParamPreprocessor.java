package org.jocean.http.rosa.impl.internal;

import java.lang.reflect.Field;

import javax.ws.rs.HeaderParam;

import org.jocean.http.Feature;
import org.jocean.http.rosa.impl.RequestChanger;
import org.jocean.http.rosa.impl.RequestPreprocessor;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpRequest;

class HeaderParamPreprocessor implements Feature, RequestPreprocessor {

    private static final Logger LOG =
            LoggerFactory.getLogger(HeaderParamPreprocessor.class);
    
    @Override
    public RequestChanger call(final Object signalBean) {
        
        if (null == signalBean) {
            return null;
        }
        
        final Field[] headerFields = 
                ReflectUtils.getAnnotationFieldsOf(signalBean.getClass(), HeaderParam.class);
        if ( headerFields.length > 0 ) {
            return new RequestChanger() {
                @Override
                public void call(final HttpRequest request) {
                    for ( Field field : headerFields ) {
                        try {
                            final Object value = field.get(signalBean);
                            if ( null != value ) {
                                final String headername = 
                                    field.getAnnotation(HeaderParam.class).value();
                                request.headers().set(headername, value);
                            }
                        } catch (Exception e) {
                            LOG.warn("exception when get value from field:[{}], detail:{}",
                                    field, ExceptionUtils.exception2detail(e));
                        }
                    }
                }

                @Override
                public int ordinal() {
                    return 100;
                }};
        } else {
            return null;
        }
    }

}
