package org.jocean.http;

import java.io.InputStream;

import rx.functions.Func2;

public interface ContentDecoder extends WithContentType {
    @Override
    public String contentType();
    public Func2<InputStream, Class<?>, Object> decoder();
}
