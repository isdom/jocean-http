package org.jocean.http.rosa;

import javax.ws.rs.HttpMethod;

import org.jocean.http.Feature;
import org.jocean.http.rosa.impl.internal.Facades.MethodSource;
import org.jocean.http.rosa.impl.internal.Facades.PathSource;

import rx.Observable;

public interface SignalClient {
    
    public class Attachment implements Feature {
        public Attachment(final String filename, final String contentType) {
            this.filename = filename;
            this.contentType = contentType;
        }
        
        public final String filename;
        public final String contentType;
        //  add direct content for test
    }
    
    public class UsingPath implements Feature, PathSource {
        public UsingPath(final String path) {
            this._path = path;
        }
        
        public String path() {
            return this._path;
        }
        
        private final String _path;
    }
    
    public class UsingMethod implements Feature, MethodSource {
        public UsingMethod(final HttpMethod method) {
            this._method = method;
        }
        
        public HttpMethod method() {
            return this._method;
        }
        
        private final HttpMethod _method;
    }
    
    public <RESP> Observable<? extends RESP> defineInteraction(
            final Object request, final Feature... features);
}
