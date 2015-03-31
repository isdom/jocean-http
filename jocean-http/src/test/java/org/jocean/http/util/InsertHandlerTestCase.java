package org.jocean.http.util;

import static org.junit.Assert.*;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LoggingHandler;

import java.util.Comparator;

import org.junit.Test;

public class InsertHandlerTestCase {

    enum HandlerNames {
        LOGGING,
        CODEC,
    }
    
    @Test
    public void testInsert2Handler() {
        final Channel channel = new LocalChannel();
        final ChannelPipeline pipeline = channel.pipeline();
        final Comparator<String> comparator = Nettys.comparator(HandlerNames.class);
        
        Nettys.insertHandler(pipeline, new HttpServerCodec(), HandlerNames.CODEC.name(), comparator);
        Nettys.insertHandler(pipeline, new LoggingHandler(), HandlerNames.LOGGING.name(), comparator);
        
        assertEquals(HandlerNames.LOGGING.name(), pipeline.names().get(0));
        assertEquals(HandlerNames.CODEC.name(), pipeline.names().get(1));
    }

}
