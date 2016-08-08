package org.jocean.http.rosa.impl;

import org.jocean.idiom.InterfaceUtils;

import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public interface RequestPreprocessor extends Func1<Object, RequestChanger> {
    public static class Util {
        @SafeVarargs
        public static <T> RequestPreprocessor[] filter(T... sources) {
            return InterfaceUtils.selectIncludeType(RequestPreprocessor.class, (Object[])sources);
        }
    }
}
