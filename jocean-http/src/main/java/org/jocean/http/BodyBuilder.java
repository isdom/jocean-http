package org.jocean.http;

import rx.Observable;

public interface BodyBuilder {
    public Observable<? extends MessageBody> build(final Object bean, final ContentEncoder contentEncoder);
}
