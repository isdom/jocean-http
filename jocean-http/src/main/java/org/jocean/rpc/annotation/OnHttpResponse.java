package org.jocean.rpc.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface OnHttpResponse {
    /**
     * indicate static constant instance to Transformer<FullMessage<HttpResponse>, FullMessage<HttpResponse>>
     */
    public abstract String[] value();
}
