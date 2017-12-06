package org.jocean.http.rosa;

import java.lang.annotation.Annotation;
import java.net.URI;

import org.jocean.http.Feature;
import org.jocean.http.MessageDecoder;
import org.jocean.http.rosa.impl.internal.Facades.JSONSource;
import org.jocean.http.rosa.impl.internal.Facades.MethodSource;
import org.jocean.http.rosa.impl.internal.Facades.PathSource;
import org.jocean.http.rosa.impl.internal.Facades.ResponseBodyTypeSource;
import org.jocean.http.rosa.impl.internal.Facades.ResponseTypeSource;
import org.jocean.http.rosa.impl.internal.Facades.UriSource;
import org.jocean.idiom.TerminateAware;

import io.netty.util.CharsetUtil;
import rx.Observable;

public interface SignalClient {
    
    public class Attachment implements Feature {
        public Attachment(final String name, final String filename, final String contentType) {
            this.name = name;
            this.filename = filename;
            this.contentType = contentType;
        }
        
        public Attachment(final String filename, final String contentType) {
            this.name = null;
            this.filename = filename;
            this.contentType = contentType;
        }
        
        public final String name;
        public final String filename;
        public final String contentType;
    }
    
    public class UsingUri implements Feature, UriSource {
        public UsingUri(final URI uri) {
            this._uri = uri;
        }
        
        public URI uri() {
            return this._uri;
        }
        
        private final URI _uri;
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
        public UsingMethod(final Class<? extends Annotation> method) {
            this._method = method;
        }
        
        public Class<? extends Annotation> method() {
            return this._method;
        }
        
        private final Class<? extends Annotation> _method;
    }
    
    public class DecodeResponseBodyAs implements Feature, ResponseBodyTypeSource {
        public DecodeResponseBodyAs(final Class<?> bodyType) {
            this._bodyType = bodyType;
        }
        
        @Override
        public Class<?> responseBodyType() {
            return this._bodyType;
        }
         
        private final Class<?> _bodyType;
    }
    
    public class ConvertResponseTo implements Feature, ResponseTypeSource {
        public ConvertResponseTo(final Class<?> respType) {
            this._respType = respType;
        }
        
        @Override
        public Class<?> responseType() {
            return this._respType;
        }
         
        private final Class<?> _respType;
    }
    
    public class JSONContent implements Feature, JSONSource {
        public JSONContent(final String jsonAsString) {
            this._content = jsonAsString.getBytes(CharsetUtil.UTF_8);
        }
        
        public byte[] content() {
            return this._content;
        }
        
        private final byte[] _content;
    }
    
    public interface InteractionBuilder {
        
        public InteractionBuilder request(final Object request);
        
        public InteractionBuilder feature(final Feature... features);
        
        public <RESP> Observable<RESP> build();
    }

    public InteractionBuilder interaction();

    public interface InteractionBuilder2 {
        
        public InteractionBuilder2 request(final Object request);
        
        public InteractionBuilder2 feature(final Feature... features);
        
        public Observable<MessageDecoder> build();
    }

    public InteractionBuilder2 interaction2(final TerminateAware<?> terminateAware);
}
