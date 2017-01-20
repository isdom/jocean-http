package org.jocean.http;

import static org.junit.Assert.*;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.util.CharsetUtil;

public class HttpContentTestCase {

    @Test
    public final void testHttpContentEquals() {
        final ByteBuf buf1 = Unpooled.buffer(100);
        
        buf1.writeBytes("hello".getBytes(CharsetUtil.UTF_8));
        
        final HttpContent c1 = new DefaultHttpContent(buf1);
        
        final ByteBuf buf2 = Unpooled.buffer(100);
        
        buf2.writeBytes("world".getBytes(CharsetUtil.UTF_8));
        
        final HttpContent c2 = new DefaultHttpContent(buf2);
        
        assertEquals(c1, c2);
        assertNotEquals(c1.content(), c2.content());
        
        final HttpContent c3 = c1.duplicate();
        assertEquals(c1.content(), c3.content());
        assertTrue(c1.content() == c3.content().unwrap());
    }

}
