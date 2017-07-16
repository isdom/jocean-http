package org.jocean.http.util;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;

import java.util.HashMap;
import java.util.Map;

import org.jocean.http.Feature;
import org.jocean.http.Feature.HandlerBuilder;

public class Feature2Handler implements HandlerBuilder {

    public void register(final Class<?> cls, final HandlerPrototype prototype) {
        this._cls2prototype.put(cls, prototype);
    }
    
    @Override
    public ChannelHandler build(final Feature feature, final ChannelPipeline pipeline,
            final Object... args) {
        final HandlerPrototype prototype = this._cls2prototype.get(feature.getClass());
        if (null!=prototype) {
            return Nettys.applyHandler(pipeline, prototype, args);
        } else {
            return null;
        }
    }
    
    private final Map<Class<?>, HandlerPrototype> _cls2prototype = new HashMap<>();
}
