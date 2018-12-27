package org.jocean.http;

import java.io.InputStream;

import rx.functions.Func2;

public interface ContentDecoder {
    public String contentType();
    public Func2<InputStream, Class<?>, Object> decoder();
}
