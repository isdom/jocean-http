/**
 * 
 */
package org.jocean.http.rosa.impl.preprocessor;

import org.jocean.http.rosa.impl.RequestChanger;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;

/**
 * @author isdom
 *
 */
class RequestMethodSetter implements RequestChanger {

    public RequestMethodSetter(final HttpMethod method) {
        this._method = method;
    }
    
    @Override
    public void call(final HttpRequest request) {
        request.setMethod(this._method);
    }

    @Override
    public int ordinal() {
        return 0;
    }
    
    private final HttpMethod _method;
}
