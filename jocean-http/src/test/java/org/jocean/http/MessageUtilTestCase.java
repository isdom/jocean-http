package org.jocean.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import javax.ws.rs.Path;

import org.jocean.netty.util.ByteBufArrayOutputStream;
import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;

public class MessageUtilTestCase {

    @Path("/rawpath")
    public static class Req {}
    
    @Path("http://127.0.0.1:80/rawpath")
    public static class WithFullUri {}
    
    @Test
    public final void testSetUriToRequest() {
        final DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "");
        MessageUtil.setUriToRequest(request, new Req());
        assertEquals("/rawpath", request.uri());
        
        final DefaultHttpRequest request2 = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/pathexist");
        MessageUtil.setUriToRequest(request2, new Req());
        assertEquals("/pathexist", request2.uri());
        
        final DefaultHttpRequest request3 = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "");
        MessageUtil.setUriToRequest(request3, new WithFullUri());
        assertEquals("127.0.0.1", request3.headers().get(HttpHeaderNames.HOST));
        assertEquals("/rawpath", request3.uri());
        
        final DefaultHttpRequest request4 = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/pathexist");
        MessageUtil.setUriToRequest(request4, new WithFullUri());
        assertEquals("127.0.0.1", request4.headers().get(HttpHeaderNames.HOST));
        assertEquals("/pathexist", request4.uri());
    }

    @Test
    public final void testSendRedpackRequestToXml() {
        final SendRedpackRequest request = new SendRedpackRequest();
        
        request.setMchId("11111");
        request.setMchBillno("222222");
        
        try (final ByteBufArrayOutputStream out = new ByteBufArrayOutputStream()) {
            MessageUtil.serializeToXml(request, out);
            final ByteBuf[] bufs = out.buffers();
            assertTrue(bufs.length > 0);
        } catch (Exception e) {
        }
        
    }
}
