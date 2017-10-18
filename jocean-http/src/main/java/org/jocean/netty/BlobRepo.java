package org.jocean.netty;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.jocean.http.WritePolicy;

import io.netty.util.ReferenceCounted;
import rx.Observable;
import rx.functions.Func1;

public interface BlobRepo {
    public interface Blob extends ReferenceCounted {
        public String name();
        public String filename();
        public String contentType();
        public int contentLength();
        public InputStream inputStream();
        
        @Override
        Blob retain();

        @Override
        Blob retain(int increment);

        @Override
        Blob touch();

        @Override
        Blob touch(Object hint);
        
        public static class Util {
            public static Blob fromByteArray(
                    final byte[] content, 
                    final String contentType,
                    final String filename,
                    final String name) {
                return new Blob() {
                    @Override
                    public String toString() {
                        final StringBuilder builder = new StringBuilder();
                        builder.append("Blob [name=").append(name())
                            .append(", filename=").append(filename())
                            .append(", contentType=").append(contentType())
                            .append(", content.length=").append(content.length)
                            .append("]");
                        return builder.toString();
                    }
                    @Override
                    public String name() {
                        return name;
                    }
                    @Override
                    public String filename() {
                        return filename;
                    }
                    @Override
                    public String contentType() {
                        return contentType;
                    }
                    @Override
                    public int refCnt() {
                        return 1;
                    }
                    @Override
                    public Blob retain() {
                        return this;
                    }
                    @Override
                    public Blob retain(int increment) {
                        return this;
                    }
                    @Override
                    public Blob touch() {
                        return this;
                    }
                    @Override
                    public Blob touch(Object hint) {
                        return this;
                    }
                    @Override
                    public boolean release() {
                        return false;
                    }
                    @Override
                    public boolean release(int decrement) {
                        return false;
                    }
                    @Override
                    public InputStream inputStream() {
                        return new ByteArrayInputStream(content);
                    }
                    @Override
                    public int contentLength() {
                        return content.length;
                    }};
            }
        }
    }
    
    public interface PutResult {
        public String key();
        public Blob   blob();
    }
    
    public interface PutObjectBuilder {
        
        //  required
        public PutObjectBuilder objectName(final String objectName);
        
        //  required
        public PutObjectBuilder contentLength(final long length);
        
        //  required
        public PutObjectBuilder contentType(final String contentType);
        
        //  required
        public PutObjectBuilder content(final Observable<?> content);
        
        //  optional
        public PutObjectBuilder writePolicy(final WritePolicy writePolicy);
        
        public Observable<String> build();
    }
    
    public PutObjectBuilder putObject();
    
    public Observable<Blob> getBlob(final String key);
    
    public Observable<String> copyBlob(final String sourceKey, final String destinationKey);
    
    public Observable<String> deleteBlob(final String key);
    
    public static class Util {
        private static Func1<PutResult, Blob> _RESULT2BLOB = new Func1<PutResult, Blob>() {
            @Override
            public Blob call(final PutResult result) {
                return result.blob();
            }};
            
        public static Func1<PutResult, Blob> result2Blob() {
            return _RESULT2BLOB;
        }
        
        private static Func1<PutResult, String> _RESULT2KEY = new Func1<PutResult, String>() {
            @Override
            public String call(final PutResult result) {
                return result.key();
            }};
            
        public static Func1<PutResult, String> result2key() {
            return _RESULT2KEY;
        }
    }
}
