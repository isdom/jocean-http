package org.jocean.http;

import rx.Observable;
import rx.Observable.Transformer;

public class RpcExecutor {
    public RpcExecutor(final Observable<RpcRunner> runners) {
        this._runners = runners;
    }

    public <RESP> Observable<RESP> execute(final Observable<Transformer<RpcRunner, RESP>> rpc2resp) {
        return rpc2resp.flatMap(invoker -> this._runners.compose(invoker));
    }

    private final Observable<RpcRunner> _runners;
}
