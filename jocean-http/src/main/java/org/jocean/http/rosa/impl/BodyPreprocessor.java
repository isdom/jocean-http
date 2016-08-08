package org.jocean.http.rosa.impl;

import org.jocean.idiom.InterfaceUtils;

import io.netty.handler.codec.http.HttpRequest;
import rx.functions.Func2;

/**
 * @author isdom
 *
 */
public interface BodyPreprocessor extends Func2<Object, HttpRequest, BodyBuilder> {
    public static class Util {
        @SafeVarargs
        public static <T> BodyPreprocessor[] filter(T... sources) {
            return InterfaceUtils.selectIncludeType(BodyPreprocessor.class, (Object[])sources);
        }
    }
}
