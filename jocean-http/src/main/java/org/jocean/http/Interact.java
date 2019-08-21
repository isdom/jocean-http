package org.jocean.http;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;
import rx.functions.Action1;

public interface Interact {

    public Interact name(final String name);

    public Interact method(final HttpMethod method);

    public Interact uri(final String uri);

    public Interact path(final String path);

    public Interact paramAsQuery(final String key, final String value);

    public Interact reqbean(final Object... reqbeans);

    public Interact body(final Observable<? extends MessageBody> body);

    public Interact body(final Object bean, final ContentEncoder contentEncoder);

    public Interact onrequest(final Action1<Object> action);

    public Interact onsending(final Action1<Object> action);

    public Interact feature(final Feature... features);

    public <T> Observable<T> responseAs(final ContentDecoder decoder, final Class<T> type);

    public <T> Observable<T> responseAs(final Class<T> type);

    public Observable<FullMessage<HttpResponse>> response();
}
