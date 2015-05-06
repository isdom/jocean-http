package org.jocean.http.rosa;

import rx.Observable;

public interface SignalClient {
    public class Attachment {
        public Attachment(final String filename, final String contentType) {
            this.filename = filename;
            this.contentType = contentType;
        }
        
        public final String filename;
        public final String contentType;
    }
    
    public <RESPONSE> Observable<? extends RESPONSE> defineInteraction(final Object request, final Attachment... attachments);
}
