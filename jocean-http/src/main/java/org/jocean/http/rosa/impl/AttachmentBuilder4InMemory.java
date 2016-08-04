package org.jocean.http.rosa.impl;

import java.io.IOException;
import java.nio.charset.Charset;

import org.jocean.http.rosa.SignalClient.Attachment;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.multipart.HttpData;
import io.netty.handler.codec.http.multipart.MemoryFileUpload;

public class AttachmentBuilder4InMemory implements AttachmentBuilder {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(AttachmentBuilder4InMemory.class);

    @Override
    public HttpData call(final Attachment attachment) {
        if ( attachment instanceof AttachmentInMemory ) {
            final AttachmentInMemory inmemory = (AttachmentInMemory)attachment;
            final MemoryFileUpload fileupload = new MemoryFileUpload(inmemory.filename, inmemory.filename,
                    inmemory.contentType, null, null, inmemory.content().length) {
                @Override
                public Charset getCharset() {
                    return null;
                }
            };
            try {
                fileupload.setContent(Unpooled.wrappedBuffer(inmemory.content()));
            } catch (IOException e) {
                LOG.warn("exception when fileupload.setContent, detail: {}",
                        ExceptionUtils.exception2detail(e));
            }
            return fileupload;
        } else {
            throw new RuntimeException("attachment " + attachment +" is not AttachmentInMemory instance.");
        }
    }
}
