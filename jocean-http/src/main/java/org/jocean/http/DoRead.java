package org.jocean.http;

import org.jocean.idiom.DisposableWrapperUtil;

import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;
import rx.functions.Func1;

public interface DoRead {
    public void read();

    public static class Util {
        final static Action1<Object> READ_ALWAYS = new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                final Object o = DisposableWrapperUtil.unwrap(obj);
                if ( o instanceof DoRead) {
                    ((DoRead)o).read();
                }
            }};

        final static Func1<Object, Boolean> NOT_DOREAD = new Func1<Object, Boolean>() {
            @Override
            public Boolean call(final Object obj) {
                return !(DisposableWrapperUtil.unwrap(obj) instanceof DoRead);
            }};

        final static Transformer<Object, Object> AUTO_READ = new Transformer<Object, Object>() {
            @Override
            public Observable<Object> call(final Observable<Object> org) {
                return org.doOnNext(READ_ALWAYS).filter(NOT_DOREAD);
            }};

        @SuppressWarnings("unchecked")
        public static <T> Transformer<T, T> autoread() {
            return (Transformer<T, T>) AUTO_READ;
        }

    }
}
