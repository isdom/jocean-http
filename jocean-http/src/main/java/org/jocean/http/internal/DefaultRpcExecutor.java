package org.jocean.http.internal;

import org.jocean.http.RpcExecutor;
import org.jocean.http.RpcRunner;

import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func1;

public class DefaultRpcExecutor implements RpcExecutor {
    @Override
    public <RESP> Observable<RESP> execute(final Observable<Transformer<RpcRunner, RESP>> getrpc2resp) {
        return getrpc2resp.flatMap(new Func1<Transformer<RpcRunner, RESP>, Observable<RESP>>() {
            @Override
            public Observable<RESP> call(final Transformer<RpcRunner, RESP> rpc2resp) {
                return _runners.compose(rpc2resp);
            }});
    }

    @Override
    public <RESP> Observable<RESP> execute(final Transformer<RpcRunner, RESP> rpc2resp) {
        return _runners.compose(rpc2resp);
    }

    public DefaultRpcExecutor(final Observable<RpcRunner> runners) {
        this._runners = runners;
    }

    private final Observable<RpcRunner> _runners;
}