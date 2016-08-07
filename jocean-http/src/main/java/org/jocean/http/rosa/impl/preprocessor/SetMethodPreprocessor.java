package org.jocean.http.rosa.impl.preprocessor;

import org.jocean.http.Feature;
import org.jocean.http.rosa.impl.RequestChanger;
import org.jocean.http.rosa.impl.RequestPreprocessor;
import org.jocean.http.util.HttpUtil;
import org.jocean.idiom.AnnotationWrapper;

import io.netty.handler.codec.http.HttpMethod;

class SetMethodPreprocessor implements Feature, RequestPreprocessor {

    @Override
    public RequestChanger call(final Object signalBean) {
        return new RequestMethodSetter(methodOf(signalBean.getClass()));
    }

    private static HttpMethod methodOf(final Class<?> signalType) {
        final AnnotationWrapper wrapper = 
                signalType.getAnnotation(AnnotationWrapper.class);
        final HttpMethod method = null != wrapper ? HttpUtil.fromJSR331Type(wrapper.value()) : null;
        return null != method ? method : HttpMethod.GET;
    }
}
