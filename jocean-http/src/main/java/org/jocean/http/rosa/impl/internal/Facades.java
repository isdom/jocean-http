package org.jocean.http.rosa.impl.internal;

import java.lang.annotation.Annotation;
import java.net.URI;

public class Facades {
    
    public interface MethodSource {
        public Class<? extends Annotation> method();
    }

    public interface UriSource {
        public URI uri();
    }
    
    public interface PathSource {
        public String path();
    }

    public interface ContentSource {
        public byte[] content();
    }
    
    public interface JSONSource extends ContentSource {
    }

    public interface ResponseBodyTypeSource {
        public Class<?> responseBodyType();
    }

    //  consider ResponseBodyTypeSource, this Facade use ResponseTypeSource first
    public interface ResponseTypeSource {
        public Class<?> responseType();
    }
}
