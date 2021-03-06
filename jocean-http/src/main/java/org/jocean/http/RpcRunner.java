package org.jocean.http;

import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func1;

public interface RpcRunner {
    public RpcRunner spi(final TypedSPI spi);
    public RpcRunner name(final String name);
    public <T> Observable<T> execute(final Func1<Interact, Observable<T>> invoker);
    public <T> Observable<T> submit(final Transformer<Interact, T> invoker);
}
