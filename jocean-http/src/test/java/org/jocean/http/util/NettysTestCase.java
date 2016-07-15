package org.jocean.http.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import javax.ws.rs.POST;

import org.jocean.idiom.AnnotationWrapper;
import org.junit.Test;

import io.netty.handler.codec.http.HttpMethod;

public class NettysTestCase {

    @AnnotationWrapper(POST.class)
    private final String _params = null;
    
    @Test
    public final void testIsFieldAnnotatedOfHttpMethod() throws Exception {
        
        assertTrue( Nettys.isFieldAnnotatedOfHttpMethod(
                NettysTestCase.class.getDeclaredField("_params"), HttpMethod.POST));
        
        assertFalse( Nettys.isFieldAnnotatedOfHttpMethod(
                NettysTestCase.class.getDeclaredField("_params"), HttpMethod.OPTIONS));
    }
}
