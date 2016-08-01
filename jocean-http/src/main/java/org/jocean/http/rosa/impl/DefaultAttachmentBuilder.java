package org.jocean.http.rosa.impl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

import org.jocean.http.rosa.SignalClient.Attachment;
import org.jocean.idiom.io.FilenameUtils;

import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.HttpData;

public class DefaultAttachmentBuilder implements AttachmentBuilder {

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
            // TODO Auto-generated catch block, log
            e.printStackTrace();
        }
        return filePayload;
    }

}
