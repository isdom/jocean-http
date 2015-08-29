package org.jocean.http.rosa;

import org.jocean.http.Feature;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscriber;

public interface SignalClient {
    public interface Progressable {
        public long progress();
        public long total();
    }
    
    public interface UploadProgressable extends Progressable {
    }
    
    public interface DownloadProgressable extends Progressable {
    }
    
    public abstract class ProgressiveSubscriber<RESPONSE> extends Subscriber<Object> {

        private static final Logger LOG =
                LoggerFactory.getLogger(SignalClient.class);
        
        public abstract void onUploadProgress(final long progress, final long total);
        
        public abstract void onDownloadProgress(final long progress, final long total);
        
        public abstract void onResponse(final RESPONSE response);
        
        @SuppressWarnings("unchecked")
        @Override
        public void onNext(final Object obj) {
            if (obj instanceof UploadProgressable) {
                try {
                    onUploadProgress(((UploadProgressable)obj).progress(), ((UploadProgressable)obj).total());
                } catch (Exception e) {
                    LOG.warn("exception when onUploadProgress, detail: {}", 
                            ExceptionUtils.exception2detail(e));
                }
            } else if (obj instanceof DownloadProgressable) {
                try {
                    onDownloadProgress(((DownloadProgressable)obj).progress(), ((DownloadProgressable)obj).total());
                } catch (Exception e) {
                    LOG.warn("exception when onDownloadProgress, detail: {}", 
                            ExceptionUtils.exception2detail(e));
                }
            } else {
                onResponse((RESPONSE)obj);
            }
        }
    }
    
    public class Attachment {
        public Attachment(final String filename, final String contentType) {
            this.filename = filename;
            this.contentType = contentType;
        }
        
        public final String filename;
        public final String contentType;
    }
    
    public Observable<? extends Object> defineInteraction(final Object request);
    
    public Observable<? extends Object> defineInteraction(
            final Object request, final Feature... features);
    
    public Observable<? extends Object> defineInteraction(
            final Object request, final Attachment... attachments);
    
    public Observable<? extends Object> defineInteraction(
            final Object request, final Feature[] features, final Attachment[] attachments);
}
