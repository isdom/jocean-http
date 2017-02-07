package org.jocean.netty;

import rx.Observable;

public interface BlobRepo {
    public interface Blob {
        public String name();
        public String filename();
        public String contentType();
        public byte[] content();
    }
    
    public Observable<String> putBlob(
            final String key,
            final Blob blob);
    
    public Observable<Blob> getBlob(final String key);
}
