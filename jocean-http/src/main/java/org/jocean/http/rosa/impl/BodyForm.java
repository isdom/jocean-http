package org.jocean.http.rosa.impl;

import io.netty.buffer.ByteBufHolder;

/**
 * @author isdom
 *
 */
public interface BodyForm extends ByteBufHolder {
    
    /**
     * @return
     */
    public String contentType();
    
    /**
     * @return
     */
    public int  length();
}
