package org.jocean.http.rosa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.List;

import org.junit.Test;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.MemoryFileUpload;

public class HttpPostRequestEncoderTestCase {

    @Test
    public final void testMemoryFileUploadEquals() {
        final MemoryFileUpload f1 = 
                new MemoryFileUpload("m1", "m1", "application/json", null, null, 100);
        
        assertEquals(f1, f1);
    }
    
    @Test
    public final void testDiskFileUploadEquals() {
        final DiskFileUpload f2 = 
                new DiskFileUpload("d1", "d1", "application/json", null, null, 100);
        
        assertEquals(f2, f2);
    }
    
    @Test
    public final void testGetBodyListAttributes() throws Exception {
        final HttpDataFactory factory = new DefaultHttpDataFactory(false);
        final HttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        
        // Use the PostBody encoder
        final HttpPostRequestEncoder postRequestEncoder =
                new HttpPostRequestEncoder(factory, request, true); // true => multipart
        
        final MemoryFileUpload f1 = 
                new MemoryFileUpload("m1", "m1", "application/json", null, null, 100);
        final DiskFileUpload f2 = 
                new DiskFileUpload("d1", "d1", "application/json", null, null, 100);
        final DiskFileUpload f3 = 
                new DiskFileUpload("d2", "d2", "application/json", null, null, 100);
        
        postRequestEncoder.addBodyHttpData(f1);
        postRequestEncoder.addBodyHttpData(f2);
        postRequestEncoder.addBodyHttpData(f3);
        
        final List<InterfaceHttpData> attrs = postRequestEncoder.getBodyListAttributes();
        final InterfaceHttpData[] datas = new InterfaceHttpData[]{f1,f2,f3};
        for (int idx = 0; idx < datas.length; idx++) {
            assertSame( datas[idx], attrs.toArray(new InterfaceHttpData[0])[idx]);
        }
    }

}
