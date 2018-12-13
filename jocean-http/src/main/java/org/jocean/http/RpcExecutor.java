package org.jocean.http;

import rx.Observable;
import rx.Observable.Transformer;

public interface RpcExecutor {
    public <RESP> Observable<RESP> execute(final Observable<Transformer<RpcRunner, RESP>> rpc2resp);
}
