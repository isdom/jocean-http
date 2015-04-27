package org.jocean.http.rosa;

import rx.Observable;

public interface SignalClient {
    public <REQUEST, RESPONSE> Observable<RESPONSE> 
        start(final REQUEST request);

}
