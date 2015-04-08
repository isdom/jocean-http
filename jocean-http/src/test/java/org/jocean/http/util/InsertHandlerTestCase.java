package org.jocean.http.util;

import static org.junit.Assert.assertEquals;
import io.netty.channel.Channel;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.util.List;

import org.jocean.http.server.InboundFeature;
import org.junit.Test;

public class InsertHandlerTestCase {

    @Test
    public void testInsertHandlers() throws Exception {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        final SslContext sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());

        final Channel channel = new LocalChannel();
        
        InboundFeature.CONTENT_COMPRESSOR.applyTo(channel);
        InboundFeature.ENABLE_SSL.applyTo(channel, sslCtx);
        InboundFeature.CLOSE_ON_IDLE.applyTo(channel, 180);
        InboundFeature.LOGGING.applyTo(channel);
        
        final List<String> names = channel.pipeline().names();
        
        assertEquals(InboundFeature.LOGGING.name(), names.get(0));
        assertEquals(InboundFeature.CLOSE_ON_IDLE.name(), names.get(1));
        assertEquals(InboundFeature.ENABLE_SSL.name(), names.get(2));
        assertEquals(InboundFeature.CONTENT_COMPRESSOR.name(), names.get(3));
    }

    @Test
    public void testInsertHandlers2() throws Exception {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        final SslContext sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());

        final Channel channel = new LocalChannel();
        
        InboundFeature.ENABLE_SSL.applyTo(channel, sslCtx);
        InboundFeature.CLOSE_ON_IDLE.applyTo(channel, 180);
        InboundFeature.CONTENT_COMPRESSOR.applyTo(channel);
        InboundFeature.LOGGING.applyTo(channel);
        
        final List<String> names = channel.pipeline().names();
        
        assertEquals(InboundFeature.LOGGING.name(), names.get(0));
        assertEquals(InboundFeature.CLOSE_ON_IDLE.name(), names.get(1));
        assertEquals(InboundFeature.ENABLE_SSL.name(), names.get(2));
        assertEquals(InboundFeature.CONTENT_COMPRESSOR.name(), names.get(3));
    }

    @Test
    public void testInsertHandlers3() throws Exception {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        final SslContext sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());

        final Channel channel = new LocalChannel();
        
        InboundFeature.CLOSE_ON_IDLE.applyTo(channel, 180);
        InboundFeature.ENABLE_SSL.applyTo(channel, sslCtx);
        InboundFeature.CONTENT_COMPRESSOR.applyTo(channel);
        InboundFeature.LOGGING.applyTo(channel);
        
        final List<String> names = channel.pipeline().names();
        
        assertEquals(InboundFeature.LOGGING.name(), names.get(0));
        assertEquals(InboundFeature.CLOSE_ON_IDLE.name(), names.get(1));
        assertEquals(InboundFeature.ENABLE_SSL.name(), names.get(2));
        assertEquals(InboundFeature.CONTENT_COMPRESSOR.name(), names.get(3));
    }
}
