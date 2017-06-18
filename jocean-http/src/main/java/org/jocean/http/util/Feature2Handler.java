package org.jocean.http.util;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;

import java.util.HashMap;
import java.util.Map;

import org.jocean.http.Feature;
import org.jocean.http.Feature.HandlerBuilder;

public class Feature2Handler<H extends Enum<H>> implements HandlerBuilder {

    public void register(final Class<?> cls, final H handlerType) {
        this._cls2htype.put(cls, handlerType);
    }
    
    @Override
    public ChannelHandler build(final Feature feature, final ChannelPipeline pipeline,
            final Object... args) {
        final H handlerType = this._cls2htype.get(feature.getClass());
        if (null!=handlerType) {
            return Nettys.applyHandler(pipeline, handlerType, args);
        } else {
            return null;
        }
    }
    
    private final Map<Class<?>, H> _cls2htype = new HashMap<>();
}
