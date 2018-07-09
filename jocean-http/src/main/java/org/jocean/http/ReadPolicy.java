package org.jocean.http;

import org.jocean.http.Inbound.Intraffic;

import rx.Single;

public interface ReadPolicy {
    public Single<?> whenToRead(final Intraffic intraffic);
}
