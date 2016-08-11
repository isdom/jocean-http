package org.jocean.http.rosa.impl.internal;

import java.lang.annotation.Annotation;

public class Facades {
    
    public interface MethodSource {
        public Class<? extends Annotation> method();
    }

    public interface PathSource {
        public String path();
    }
}
