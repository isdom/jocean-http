package org.jocean.http.rosa;

import rx.Observable;

public interface SignalClient {
    public <RESPONSE> Observable<RESPONSE> interaction(final Object request);
}
