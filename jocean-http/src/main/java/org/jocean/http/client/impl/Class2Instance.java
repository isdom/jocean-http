package org.jocean.http.client.impl;

import java.util.HashMap;
import java.util.Map;

import rx.functions.Func1;

public class Class2Instance<T,R> implements Func1<T,R> {

    @Override
    public R call(final T t) {
        return this._cls2obj.get(t.getClass());
    }
    
    public void register(final Class<?> cls, final R r) {
        this._cls2obj.put(cls, r);
    }
    
    private final Map<Class<?>, R> _cls2obj = new HashMap<>();
}
