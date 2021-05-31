package org.jocean.http;

import java.util.List;

import rx.functions.Action1;

public class InteractUtil {
    public static InteractPipe applyFeatures(final List<Feature> features) {
        final Action1<Interact> applier = interact -> interact.feature(features.toArray(Feature.EMPTY_FEATURES));
        return interacts -> interacts.doOnNext(applier);
    }

}
