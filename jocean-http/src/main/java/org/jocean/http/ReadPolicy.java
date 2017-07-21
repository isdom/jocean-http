package org.jocean.http;

import rx.Single;

public interface ReadPolicy {
    public Single<?> whenToRead(final Inboundable inboundable);

    public interface Inboundable {
        public long durationFromRead();
        public long durationFromBegin();
        public long inboundBytes();
    }
}
