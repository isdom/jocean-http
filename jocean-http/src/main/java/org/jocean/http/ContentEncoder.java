package org.jocean.http;

import java.io.OutputStream;

import rx.functions.Action2;

public interface ContentEncoder {
    public interface EncodeAware {
        public void onPropertyEncode(Object object, String name, Object value);
    }

    public String contentType();
    public Action2<Object, OutputStream> encoder();
    public Action2<Object, OutputStream> encoder(final EncodeAware encodeAware);
}
