package org.jocean.netty;

import java.util.Date;

import org.jocean.http.Interact;
import org.jocean.http.MessageBody;

import rx.Observable;
import rx.functions.Func1;

public interface BlobRepo {
    interface PutObjectResult {
        public String objectName();
        public String etag();
    }

    public interface PutObjectBuilder {

        //  required
        public PutObjectBuilder objectName(final String objectName);

        //  required
        public PutObjectBuilder content(final MessageBody body);

        public Func1<Interact, Observable<PutObjectResult>> build();
    }

    /**
     * put object to OSS's Bucket
     * @return
     */
    public PutObjectBuilder putObject();

    public interface SimplifiedObjectMeta {
        public String getETag();
        public long getSize();
        public Date getLastModified();
    }

    public Func1<Interact, Observable<SimplifiedObjectMeta>> getSimplifiedObjectMeta(final String objectName);

    public Func1<Interact, Observable<MessageBody>> getObject(final String objname);

    public Func1<Interact, Observable<CopyObjectResult>> copyObject(final String sourceKey, final String destinationKey);

    public Func1<Interact, Observable<String>> deleteObject(final String key);
}
