package org.jocean.http;

import org.jocean.http.util.HttpMessageHolder;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.functions.Action1;

public interface InboundEndpoint {
    public boolean isActive();
    
    public void readMessage();
    public long unreadDurationInMs();
    public void setReadPolicy(final Action1<InboundEndpoint> readPolicy);
    
    public long timeToLive();
    public long inboundBytes();
    
    public Observable<? extends HttpObject> message();
    public HttpMessageHolder messageHolder();
    public int holdingMemorySize();
    
    public static class CONST {
        public static Action1<InboundEndpoint> ALWAYS = new Action1<InboundEndpoint>() {
            @Override
            public void call(final InboundEndpoint inbound) {
                if (inbound.isActive()) {
                    inbound.readMessage();
                }
            }};
    }
}
