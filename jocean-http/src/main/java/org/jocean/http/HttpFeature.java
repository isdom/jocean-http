package org.jocean.http;

import org.jocean.idiom.Features;

public enum HttpFeature {
    EnableSSL,
    EnableLOG,
    DisableCompress;
    
    public static boolean isCompressEnabled(final int featuresAsInt) {
        return !Features.isEnabled(featuresAsInt, HttpFeature.DisableCompress);
    }
}
