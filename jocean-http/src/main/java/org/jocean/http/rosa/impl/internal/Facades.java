package org.jocean.http.rosa.impl.internal;

import javax.ws.rs.HttpMethod;

public class Facades {
    
    public interface MethodSource {
        public HttpMethod method();
    }

    public interface PathSource {
        public String path();
    }
}
