package org.jocean.http.rosa.impl.internal;

import java.lang.reflect.Field;

import javax.ws.rs.QueryParam;

import org.jocean.http.Feature;
import org.jocean.http.rosa.impl.RequestChanger;
import org.jocean.http.rosa.impl.RequestPreprocessor;
import org.jocean.http.util.Nettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringEncoder;

class QueryParamPreprocessor implements Feature, RequestPreprocessor {

    private static final Logger LOG =
            LoggerFactory.getLogger(QueryParamPreprocessor.class);
    
    @Override
    public RequestChanger call(final Object signalBean) {
        
        if (null == signalBean) {
            return null;
        }
        
        final Field[] queryFields = 
                ReflectUtils.getAnnotationFieldsOf(signalBean.getClass(), QueryParam.class);
        if ( queryFields.length > 0 ) {
            return new RequestChanger() {
                @Override
                public void call(final HttpRequest request) {
                    //  only GET method will assemble query parameters
                    //  or the QueryParam field explicit annotated HttpMethod via AnnotationWrapper 
                    //      assemble query parameters
                    final boolean isGetMethod = request.method().equals(HttpMethod.GET);
                    final QueryStringEncoder encoder = new QueryStringEncoder(request.uri());
                    for ( Field field : queryFields ) {
                        if (isGetMethod ||
                           Nettys.isFieldAnnotatedOfHttpMethod(field, request.method())) {
                            try {
                                final Object value = field.get(signalBean);
                                if ( null != value ) {
                                    final String paramkey = 
                                            field.getAnnotation(QueryParam.class).value();
                                    encoder.addParam(paramkey, String.valueOf(value));
                                }
                            }
                            catch (Exception e) {
                                LOG.warn("exception when get field({})'s value, detail:{}", 
                                        field, ExceptionUtils.exception2detail(e));
                            }
                        }
                    }
                    
                    request.setUri(encoder.toString());
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
