package org.jocean.http.util;

import static org.junit.Assert.assertEquals;
import io.netty.channel.Channel;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.util.List;

import org.junit.Test;

public class InsertHandlerTestCase {

    @Test
    public void testInsertHandlers() throws Exception {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        final SslContext sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());

        final Channel channel = new LocalChannel();
        
//        Inbound.CONTENT_COMPRESSOR.applyTo(channel);
//        Inbound.ENABLE_SSL.applyTo(channel, sslCtx);
//        Inbound.CLOSE_ON_IDLE.applyTo(channel, 180);
//        Inbound.LOGGING.applyTo(channel);
        
        final List<String> names = channel.pipeline().names();
        
//        assertEquals(Inbound.LOGGING.name(), names.get(0));
//        assertEquals(Inbound.CLOSE_ON_IDLE.name(), names.get(1));
//        assertEquals(Inbound.ENABLE_SSL.name(), names.get(2));
//        assertEquals(Inbound.CONTENT_COMPRESSOR.name(), names.get(3));
    }

    @Test
    public void testInsertHandlers2() throws Exception {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        final SslContext sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());

        final Channel channel = new LocalChannel();
        
//        Inbound.ENABLE_SSL.applyTo(channel, sslCtx);
//        Inbound.CLOSE_ON_IDLE.applyTo(channel, 180);
//        Inbound.CONTENT_COMPRESSOR.applyTo(channel);
//        Inbound.LOGGING.applyTo(channel);
        
        final List<String> names = channel.pipeline().names();
        
//        assertEquals(Inbound.LOGGING.name(), names.get(0));
//        assertEquals(Inbound.CLOSE_ON_IDLE.name(), names.get(1));
//        assertEquals(Inbound.ENABLE_SSL.name(), names.get(2));
//        assertEquals(Inbound.CONTENT_COMPRESSOR.name(), names.get(3));
    }

    @Test
    public void testInsertHandlers3() throws Exception {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        final SslContext sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());

        final Channel channel = new LocalChannel();
        
//        Inbound.CLOSE_ON_IDLE.applyTo(channel, 180);
//        Inbound.ENABLE_SSL.applyTo(channel, sslCtx);
//        Inbound.CONTENT_COMPRESSOR.applyTo(channel);
//        Inbound.LOGGING.applyTo(channel);
        
        final List<String> names = channel.pipeline().names();
        
//        assertEquals(Inbound.LOGGING.name(), names.get(0));
//        assertEquals(Inbound.CLOSE_ON_IDLE.name(), names.get(1));
//        assertEquals(Inbound.ENABLE_SSL.name(), names.get(2));
//        assertEquals(Inbound.CONTENT_COMPRESSOR.name(), names.get(3));
    }
}
