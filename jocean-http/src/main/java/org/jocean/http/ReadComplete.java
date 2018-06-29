package org.jocean.http;

import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;
import rx.functions.Func1;

public interface ReadComplete {
    public void readInbound();

    public static class Util {
        final static Action1<Object> READ_ALWAYS = new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (obj instanceof ReadComplete) {
                    ((ReadComplete)obj).readInbound();
                }
            }};

        final static Func1<Object, Boolean> NOT_READCOMPLETE = new Func1<Object, Boolean>() {
            @Override
            public Boolean call(final Object obj) {
                return !(obj instanceof ReadComplete);
            }};

        final static Transformer<Object, Object> AUTO_READ = new Transformer<Object, Object>() {
            @Override
            public Observable<Object> call(final Observable<Object> org) {
                return org.doOnNext(READ_ALWAYS).filter(NOT_READCOMPLETE);
            }};

        @SuppressWarnings("unchecked")
        public static <T> Transformer<T, T> autoread() {
            return (Transformer<T, T>) AUTO_READ;
        }

    }
}
