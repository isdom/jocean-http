package org.jocean.http;

import java.io.OutputStream;

import rx.functions.Action2;

public interface ContentEncoder {
    public String contentType();
    public Action2<Object, OutputStream> encoder();
}
