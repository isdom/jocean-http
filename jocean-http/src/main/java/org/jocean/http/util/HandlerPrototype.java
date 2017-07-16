package org.jocean.http.util;

import org.jocean.http.util.Nettys.ToOrdinal;

import io.netty.channel.ChannelHandler;
import rx.functions.FuncN;

public interface HandlerPrototype {
    
    public FuncN<ChannelHandler> factory();
    
    public ToOrdinal toOrdinal();
    
    public String name();
}
