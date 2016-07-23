package org.jocean.http;

import static org.junit.Assert.*;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.POST;

import org.junit.Test;

public class WSRSHttpMethodTestCase {

    @Test
    public final void testHttpMethodValue() {
        final HttpMethod method = POST.class.getAnnotation(HttpMethod.class);
        assertEquals( "POST", method.value());
    }

}
