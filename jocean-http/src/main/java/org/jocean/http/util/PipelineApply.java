package org.jocean.http.util;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;

public interface PipelineApply {
    public ChannelHandler applyTo(final ChannelPipeline pipeline, final Object ... args);
}
