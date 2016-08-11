package org.jocean.http;

import static org.junit.Assert.*;

import org.junit.Test;

public class FeatureTestCase {

    @Test
    public void testFeatureClassNotEquals() {
        assertNotEquals(Feature.ENABLE_LOGGING.getClass(), Feature.ENABLE_COMPRESSOR.getClass());
    }

    @Test
    public void testFeaturesUnion() {
        final Feature[] unioned = Feature.Util.union(
                new Feature[]{Feature.ENABLE_LOGGING}, Feature.ENABLE_LOGGING);
        assertEquals(1, unioned.length);
        
        final Feature[] unioned2 = Feature.Util.union(
                new Feature[]{Feature.ENABLE_LOGGING}, Feature.ENABLE_COMPRESSOR);
        assertEquals(2, unioned2.length);
        
        final Feature[] unioned3 = Feature.Util.union(
                new Feature[]{Feature.ENABLE_LOGGING, Feature.ENABLE_COMPRESSOR}, Feature.ENABLE_COMPRESSOR);
        assertEquals(2, unioned3.length);
    }
}
