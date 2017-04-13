package org.jocean.http.client;

import org.jocean.http.Feature;

public class Outbound {
    private Outbound() {
        throw new IllegalStateException("No instances!");
    }

    public static final Feature ENABLE_MULTIPART = new Feature.AbstractFeature0() {
        @Override
        public String toString() {
            return "ENABLE_MULTIPART";
        }
    };
}