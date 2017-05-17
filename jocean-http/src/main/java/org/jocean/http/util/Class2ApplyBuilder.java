package org.jocean.http.util;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;

import java.util.HashMap;
import java.util.Map;

import org.jocean.http.Feature;
import org.jocean.http.Feature.HandlerBuilder;

public class Class2ApplyBuilder implements HandlerBuilder {

    public void register(final Class<?> cls, final APPLY apply) {
        this._cls2apply.put(cls, apply);
    }
    
    @Override
    public ChannelHandler build(final Feature feature, final ChannelPipeline pipeline,
            final Object... args) {
        final APPLY apply = this._cls2apply.get(feature.getClass());
        if (null!=apply) {
            return Nettys.applyHandler(apply, pipeline, args);
        } else {
            return null;
        }
    }
    
    private final Map<Class<?>, APPLY> _cls2apply = new HashMap<>();
}
