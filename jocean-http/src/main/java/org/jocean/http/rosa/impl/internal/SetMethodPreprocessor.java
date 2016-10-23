package org.jocean.http.rosa.impl.internal;

import org.jocean.http.Feature;
import org.jocean.http.rosa.impl.RequestChanger;
import org.jocean.http.rosa.impl.RequestPreprocessor;
import org.jocean.http.rosa.impl.internal.Facades.MethodSource;
import org.jocean.http.util.HttpUtil;
import org.jocean.idiom.AnnotationWrapper;
import org.jocean.idiom.InterfaceUtils;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;

class SetMethodPreprocessor implements Feature, RequestPreprocessor {

    private static final class RequestMethodSetter implements RequestChanger, FeaturesAware {

        public RequestMethodSetter(final HttpMethod method) {
            this._method = method;
        }
        
        @Override
        public void setFeatures(final Feature[] features) {
            final MethodSource[] methods = InterfaceUtils.selectIncludeType(
                    MethodSource.class, (Object[])features);
            if ( null!=methods && methods.length > 0) {
                this._method = HttpUtil.fromJSR331Type(methods[0].method(), this._method);
            }
        }
        
        @Override
        public void call(final HttpRequest request) {
            request.setMethod(this._method);
        }

        @Override
        public int ordinal() {
            return 0;
        }
        
        private HttpMethod _method;
    }
    
    @Override
    public RequestChanger call(final Object signalBean) {
        return new RequestMethodSetter(null != signalBean 
                ? methodOf(signalBean.getClass()) 
                : HttpMethod.GET);
    }

    private static HttpMethod methodOf(final Class<?> signalType) {
        final AnnotationWrapper wrapper = 
                signalType.getAnnotation(AnnotationWrapper.class);
        return null != wrapper 
                ? HttpUtil.fromJSR331Type(wrapper.value(), HttpMethod.GET) 
                : HttpMethod.GET;
    }
}
