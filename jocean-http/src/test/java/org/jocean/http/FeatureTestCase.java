package org.jocean.http;

import static org.junit.Assert.*;

import org.junit.Test;

public class FeatureTestCase {

    @Test
    public void testFeatureClassNotEquals() {
        assertNotEquals(Feature.ENABLE_LOGGING.getClass(), Feature.ENABLE_COMPRESSOR.getClass());
    }

}
