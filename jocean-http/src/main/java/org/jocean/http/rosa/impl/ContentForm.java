package org.jocean.http.rosa.impl;

import io.netty.buffer.ByteBufHolder;

public interface ContentForm extends ByteBufHolder {
    public String contentType();
    public int  length();
}
