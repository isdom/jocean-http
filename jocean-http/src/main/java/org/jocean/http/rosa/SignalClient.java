package org.jocean.http.rosa;

import org.jocean.http.Feature;

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
    
    public <RESP> Observable<? extends RESP> defineInteraction(
            final Object request, final Feature... features);
}
