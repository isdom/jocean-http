package org.jocean.http.rosa.impl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

import org.jocean.http.rosa.SignalClient.Attachment;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.HttpData;

public class DefaultAttachmentBuilder implements AttachmentBuilder {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultAttachmentBuilder.class);

    @Override
    public HttpData call(final Attachment attachment) {
        final File file = new File(attachment.filename);
        final DiskFileUpload filePayload = 
                new DiskFileUpload(FilenameUtils.getBaseName(attachment.filename), 
                    attachment.filename, attachment.contentType, null, null, file.length()) {
            @Override
            public Charset getCharset() {
                return null;
            }
        };
        try {
            filePayload.setContent(file);
        } catch (IOException e) {
            LOG.warn("exception when filePayload.setContent, detail: {}",
                    ExceptionUtils.exception2detail(e));
        }
        return filePayload;
    }

}
