package org.jocean.http.util;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;

import java.util.Map;

import org.jocean.http.Feature;
import org.jocean.http.Feature.HandlerBuilder;

public class Class2ApplyBuilder implements HandlerBuilder {
    public Class2ApplyBuilder(final Map<Class<?>, PipelineApply> cls2apply) {
        this._cls2apply = cls2apply;
    }
    
    @Override
    public ChannelHandler build(final Feature feature, final ChannelPipeline pipeline,
            final Object... args) {
        final PipelineApply apply = this._cls2apply.get(feature.getClass());
        if (null!=apply) {
            return apply.applyTo(pipeline, args);
        } else {
            return null;
        }
    }
    
    private final Map<Class<?>, PipelineApply> _cls2apply;
}
