package org.jocean.http;

import rx.Observable;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;

public interface OutboundEndpoint {
    
    public void setFlushPerWrite(final boolean isFlushPerWrite);
    public Action0 doOnWritabilityChanged(final Action1<OutboundEndpoint> onWritabilityChanged);
    public Action0 doOnSended(final Action1<Object> onSended);
    
    public long outboundBytes();
    
    public Subscription message(final Observable<? extends Object> message);
}
