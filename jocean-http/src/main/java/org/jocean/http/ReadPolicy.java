package org.jocean.http;

import rx.Single;

public interface ReadPolicy {
    public Single<?> whenToRead(final Intraffic intraffic);

    public interface Intraffic {
        public long durationFromRead();
        public long durationFromBegin();
        public long inboundBytes();
    }
}
