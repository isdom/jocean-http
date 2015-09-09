package org.jocean.http.client;

import io.netty.handler.codec.http.HttpRequest;
import rx.functions.Action1;

public interface ApplyToRequest extends Action1<HttpRequest> {
}
