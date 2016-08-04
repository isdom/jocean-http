package org.jocean.http.rosa.impl;

import org.jocean.http.rosa.SignalClient.Attachment;

public class AttachmentInMemory extends Attachment {

    public AttachmentInMemory(
            final String filename, 
            final String contentType,
            final byte[] content) {
        super(filename, contentType);
        this._content = content;
    }
    
    public byte[] content() {
        return this._content;
    }
    
    private final byte[] _content;
}
