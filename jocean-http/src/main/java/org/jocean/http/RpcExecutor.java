package org.jocean.http;

import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;

public interface RpcExecutor {
    public <RESP> Observable<RESP> execute(final Observable<Transformer<RpcRunner, RESP>> getrpc2resp);
    public <RESP> Observable<RESP> execute(final Transformer<RpcRunner, RESP> rpc2resp);

    public <RESP> Observable<RESP> execute(final Action1<RpcRunner> onrunner, final Observable<Transformer<RpcRunner, RESP>> getrpc2resp);
    public <RESP> Observable<RESP> execute(final Action1<RpcRunner> onrunner, final Transformer<RpcRunner, RESP> rpc2resp);
}
