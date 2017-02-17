package org.jocean.http;

import org.jocean.http.util.HttpMessageHolder;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.functions.Action1;

public interface InboundEndpoint {
    public void setAutoRead(final boolean autoRead);
    public void readMessage();
    public InboundEndpoint addReadCompleteHook(final Action1<InboundEndpoint> onReadComplete);
    public void removeReadCompleteHook(final Action1<InboundEndpoint> onReadComplete);
    
    public long timeToLive();
    public long inboundBytes();
    
    public Observable<? extends HttpObject> message();
    public HttpMessageHolder messageHolder();
    public int holdingMemorySize();
}
