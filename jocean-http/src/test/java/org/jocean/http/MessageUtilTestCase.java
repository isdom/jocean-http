package org.jocean.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.netty.util.BufsOutputStream;
import org.junit.Test;

import io.netty.buffer.ByteBuf;
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
        
        final List<ByteBuf> bufs = new ArrayList<>();
        
        try (final BufsOutputStream<DisposableWrapper<ByteBuf>> out = new BufsOutputStream<>(
                MessageUtil.pooledAllocator(null, 8192), dwb->dwb.unwrap(), dwb->bufs.add(dwb.unwrap()) ) ) {
            MessageUtil.serializeToXml(request, out);
            assertTrue(bufs.size() > 0);
        } catch (Exception e) {
        }
    }

    @Test
    public final void testPostWithbody() {
        final SendRedpackRequest request = new SendRedpackRequest();
        
        request.setMchId("11111");
        request.setMchBillno("222222");
        
        final HttpClient client = new DefaultHttpClient();
            MessageUtil.interact(client)
                .method(HttpMethod.POST)
                .uri("http://www.sina.com")
                .reqbean(request)
                .body(MessageUtil.toBody(request, MediaType.APPLICATION_XML, MessageUtil::serializeToXml))
                .feature(Feature.ENABLE_LOGGING_OVER_SSL).execution()
            .compose(MessageUtil.responseAsString())
            .toBlocking().single();
    }
}
