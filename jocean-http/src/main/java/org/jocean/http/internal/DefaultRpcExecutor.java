package org.jocean.http.internal;

import org.jocean.http.Interact;
import org.jocean.http.RpcExecutor;
import org.jocean.http.RpcRunner;

import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func1;

public class DefaultRpcExecutor implements RpcExecutor {
    @Override
    public <RESP> Observable<RESP> execute(final Observable<Transformer<RpcRunner, RESP>> getrpc2resp) {
        return getrpc2resp.flatMap(rpc2resp -> _runners.compose(rpc2resp));
    }

    @Override
    public <RESP> Observable<RESP> execute(final Transformer<RpcRunner, RESP> rpc2resp) {
        return _runners.compose(rpc2resp);
    }

    @Override
    public <RESP> Observable<RESP> execute(final Func1<Interact, Observable<RESP>> interact2resp) {
        return _runners.flatMap(runner -> runner.execute(interact2resp));
    }

    @Override
    public <RESP> Observable<RESP> submit(final Transformer<Interact, RESP> inters2resp) {
        return _runners.flatMap(runner -> runner.execute(inters2resp));
    }

    @Override
    public <RESP> Observable<RESP> execute(final Transformer<RpcRunner, RpcRunner> runnertransf,
            final Observable<Transformer<RpcRunner, RESP>> getrpc2resp) {
        return getrpc2resp.flatMap(new Func1<Transformer<RpcRunner, RESP>, Observable<RESP>>() {
            @Override
            public Observable<RESP> call(final Transformer<RpcRunner, RESP> rpc2resp) {
                return _runners.compose(runnertransf).compose(rpc2resp);
            }});
    }

    @Override
    public <RESP> Observable<RESP> execute(final Transformer<RpcRunner, RpcRunner> runnertransf,
            final Transformer<RpcRunner, RESP> rpc2resp) {
        return _runners.compose(runnertransf).compose(rpc2resp);
    }

    public DefaultRpcExecutor(final Observable<RpcRunner> runners) {
        this._runners = runners;
    }

    private final Observable<RpcRunner> _runners;
}
