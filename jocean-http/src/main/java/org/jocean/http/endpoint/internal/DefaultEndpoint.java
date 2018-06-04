package org.jocean.http.endpoint.internal;

import org.jocean.http.endpoint.Endpoint;

public class DefaultEndpoint implements Endpoint {

    public DefaultEndpoint(final String type, final String uri) {
        this._type = type;
        this._uri = uri;
    }

    @Override
    public String type() {
        return this._type;
    }

    @Override
    public String uri() {
        return this._uri;
    }

    private final String _type;
    private final String _uri;
}
