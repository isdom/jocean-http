package org.jocean.http.endpoint.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jocean.http.endpoint.Endpoint;
import org.jocean.http.endpoint.EndpointSet;

public class DefaultEndpointSet implements EndpointSet {

    private static final String[] EMPTY_STRS = new String[0];

    public DefaultEndpointSet(final Collection<Endpoint> endpoints) {
        this._endpoints = endpoints;
    }

    @Override
    public String[] uris(final String type) {
        final List<String> uris = new ArrayList<>();

        for (final Endpoint endpoint : this._endpoints) {
            if (endpoint.type().equals(type)) {
                uris.add(endpoint.uri());
            }
        }

        return uris.toArray(EMPTY_STRS);
    }

    private final Collection<Endpoint> _endpoints;
}
