package org.jocean.http;

import rx.Observable;
import rx.Subscription;

public interface OutboundEndpoint {
//    public OutboundEndpoint addReadCompleteHook(final Action1<OutboundEndpoint> onReadComplete);
//    public void removeReadCompleteHook(final Action1<OutboundEndpoint> onReadComplete);
    
//    public long timeToLive();
    public long outboundBytes();
    
    public Subscription message(final Observable<? extends Object> message);
}
