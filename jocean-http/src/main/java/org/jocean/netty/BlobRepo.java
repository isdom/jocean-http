package org.jocean.netty;

import java.io.InputStream;

import io.netty.util.ReferenceCounted;
import rx.Observable;

public interface BlobRepo {
    public interface Blob extends ReferenceCounted {
        public String name();
        public String filename();
        public String contentType();
        public int contentLength();
//        public byte[] content();
        public InputStream inputStream();
        
        @Override
        Blob retain();

        @Override
        Blob retain(int increment);

        @Override
        Blob touch();

        @Override
        Blob touch(Object hint);
    }
    
    public Observable<String> putBlob(
            final String key,
            final Blob blob);
    
    public Observable<Blob> getBlob(final String key);
}
