package org.jocean.http;

import rx.Single;

public interface Inboundable {
    public interface ReadPolicy {
        public Single<?> whenToRead(final Inboundable inboundable);
    }
    
    public boolean isActive();
    public long unreadDurationInMs();
    public long readingDurationInMS();
    public long inboundBytes();
    
    public void setReadPolicy(final ReadPolicy readPolicy);
}
