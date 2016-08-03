package org.jocean.http.rosa.impl;

import java.io.IOException;
import java.nio.charset.Charset;

import org.jocean.http.rosa.SignalClient.Attachment;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.multipart.HttpData;
import io.netty.handler.codec.http.multipart.MemoryFileUpload;

public class MemoryAttachmentBuilder implements AttachmentBuilder {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(MemoryAttachmentBuilder.class);

    public MemoryAttachmentBuilder(final String[] names, final String[] contents) {
        this._names = names;
        this._contents = contents;
    }
    
    @Override
    public HttpData call(final Attachment attachment) {
        for (int idx=0; idx < this._names.length; idx++) {
            final String name = this._names[idx];
            if (attachment.filename.equals(name)) {
                final byte[] content = this._contents[idx].getBytes(Charsets.UTF_8);
                final MemoryFileUpload fileupload = new MemoryFileUpload(name, name,
                        attachment.contentType, null, null, content.length) {
                    @Override
                    public Charset getCharset() {
                        return null;
                    }
                };
                try {
                    fileupload.setContent(Unpooled.wrappedBuffer(content));
                } catch (IOException e) {
                    LOG.warn("exception when fileupload.setContent, detail: {}",
                            ExceptionUtils.exception2detail(e));
                }
                return fileupload;
            }
        }
        return null;
    }

    private final String[] _names;
    private final String[] _contents;
}
