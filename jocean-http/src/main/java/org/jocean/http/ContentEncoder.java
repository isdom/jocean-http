package org.jocean.http;

import java.io.OutputStream;

import rx.functions.Action2;

public interface ContentEncoder extends WithContentType {
    @Override
    public String contentType();

    public Action2<Object, OutputStream> encoder();
}
