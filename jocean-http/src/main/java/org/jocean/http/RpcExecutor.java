package org.jocean.http;

import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func1;

public class RpcExecutor {
    public RpcExecutor(final Observable<RpcRunner> runners) {
        this._runners = runners;
    }

    public <RESP> Observable<RESP> execute(final Observable<Transformer<RpcRunner, RESP>> rpc2resp) {
        return rpc2resp.flatMap(new Func1<Transformer<RpcRunner, RESP>, Observable<RESP>>() {
            @Override
            public Observable<RESP> call(final Transformer<RpcRunner, RESP> invoker) {
                return _runners.compose(invoker);
            }});
    }

    private final Observable<RpcRunner> _runners;
}
