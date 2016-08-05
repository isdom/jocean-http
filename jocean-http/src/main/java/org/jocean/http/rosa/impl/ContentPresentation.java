package org.jocean.http.rosa.impl;

import io.netty.buffer.ByteBufHolder;

public interface ContentPresentation extends ByteBufHolder {
    public String contentType();
    public int  length();
}
