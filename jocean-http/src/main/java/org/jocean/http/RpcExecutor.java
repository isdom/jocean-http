package org.jocean.http;

import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func1;

public interface RpcExecutor {
    public <RESP> Observable<RESP> execute(final Observable<Transformer<RpcRunner, RESP>> getrpc2resp);
    public <RESP> Observable<RESP> execute(final Transformer<RpcRunner, RESP> rpc2resp);
    public <RESP> Observable<RESP> execute(final Func1<Interact, Observable<RESP>> interact2resp);
    public <RESP> Observable<RESP> submit(final Transformer<Interact, RESP> inters2resp);

    public <RESP> Observable<RESP> execute(final Transformer<RpcRunner, RpcRunner> runnertransf,
            final Observable<Transformer<RpcRunner, RESP>> getrpc2resp);

    public <RESP> Observable<RESP> execute(final Transformer<RpcRunner, RpcRunner> runnertransf,
            final Transformer<RpcRunner, RESP> rpc2resp);
}
