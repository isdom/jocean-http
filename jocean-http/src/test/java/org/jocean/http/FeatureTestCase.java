package org.jocean.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import org.jocean.idiom.InterfaceUtils;
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

    @Test
    public void testFeaturesClass() {
        System.out.println(Feature.FLUSH_PER_WRITE.getClass());
        
        assertNotEquals(Feature.ENABLE_LOGGING.getClass(), Feature.ENABLE_LOGGING_OVER_SSL.getClass());
        assertNotEquals(Feature.ENABLE_LOGGING.getClass(), Feature.FLUSH_PER_WRITE.getClass());
    }

    @Test
    public void testFeaturesSelectIncludeType() {
        final Feature[] features = new Feature[]{Feature.ENABLE_LOGGING, Feature.FLUSH_PER_WRITE, Feature.ENABLE_LOGGING_OVER_SSL};
                
        final Object[] flushPerWriteFeature = 
                InterfaceUtils.selectIncludeType(
                        Feature.FLUSH_PER_WRITE.getClass(), (Object[])features);
        
        assertEquals(1, flushPerWriteFeature.length);
        assertSame(flushPerWriteFeature[0], Feature.FLUSH_PER_WRITE);
    }
}
