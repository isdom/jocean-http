package org.jocean.http.util;

import static org.junit.Assert.*;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;

import org.junit.Test;

import io.netty.handler.codec.http.HttpMethod;

public class TestHttpUtil {

    @Test
    public void testToJSR331Type() {
        assertEquals(HttpUtil.toJSR331Type(HttpMethod.GET), GET.class);
        assertEquals(HttpUtil.toJSR331Type(HttpMethod.PUT), PUT.class);
        assertEquals(HttpUtil.toJSR331Type(HttpMethod.POST), POST.class);
        assertEquals(HttpUtil.toJSR331Type(HttpMethod.OPTIONS), OPTIONS.class);
        assertEquals(HttpUtil.toJSR331Type(HttpMethod.HEAD), HEAD.class);
        assertEquals(HttpUtil.toJSR331Type(HttpMethod.DELETE), DELETE.class);
        
        assertNull(HttpUtil.toJSR331Type(HttpMethod.PATCH));
        assertNull(HttpUtil.toJSR331Type(HttpMethod.CONNECT));
        assertNull(HttpUtil.toJSR331Type(HttpMethod.TRACE));
    }

}
