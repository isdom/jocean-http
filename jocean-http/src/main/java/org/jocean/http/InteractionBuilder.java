package org.jocean.http;

import org.jocean.http.client.HttpClient;

import io.netty.handler.codec.http.HttpMethod;
import rx.Observable;
import rx.functions.Action1;

public interface InteractionBuilder {
    
    public InteractionBuilder client(final HttpClient client);
    
    public InteractionBuilder method(final HttpMethod method);
    
    public InteractionBuilder uri(final String uri);
    
    public InteractionBuilder path(final String path);
    
    public InteractionBuilder paramAsQuery(final String key, final String value);
    
    public InteractionBuilder reqbean(final Object... reqbeans);
    
    public InteractionBuilder body(final Observable<? extends MessageBody> body);
    
    public InteractionBuilder body(final Object bean, final ContentEncoder contentEncoder);
    
    public InteractionBuilder onrequest(final Action1<Object> action);
    
    public InteractionBuilder feature(final Feature... features);
    
    public Observable<? extends Interaction> execution();
}
