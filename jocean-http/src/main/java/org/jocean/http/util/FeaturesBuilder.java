package org.jocean.http.util;

import java.util.List;

import org.jocean.http.Feature;

import rx.functions.Func0;

public class FeaturesBuilder implements Func0<Feature[]> {
    
    public FeaturesBuilder(final List<Feature> features) {
        this._features = features;
    }

    @Override
    public Feature[] call() {
        return this._features.toArray(Feature.EMPTY_FEATURES);
    }

    @Override
    public String toString() {
        return "FeaturesBuilder [features=" + _features + "]";
    }

    private final List<Feature> _features;
}
