package org.jocean.http;

import rx.Single;

public interface Inboundable<I extends Inboundable<?>> {
    public interface ReadPolicy<I extends Inboundable<?>> {
        public Single<?> whenToRead(final I inboundable);
    }
    
    public boolean isActive();
    public long unreadDurationInMs();
    public long readingDurationInMS();
    public long inboundBytes();
    
    public void setReadPolicy(final ReadPolicy<I> readPolicy);
}
