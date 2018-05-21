package org.jocean.http;

import java.util.List;

import rx.Observable;
import rx.functions.Action1;

public class InteractUtil {
    public static InteractTransformer applyFeatures(final List<Feature> features) {
        final Action1<Interact> applier = new Action1<Interact>() {
            @Override
            public void call(final Interact interact) {
                interact.feature(features.toArray(Feature.EMPTY_FEATURES));
            }};
        return new InteractTransformer() {
            @Override
            public Observable<Interact> call(final Observable<Interact> interacts) {
                return interacts.doOnNext(applier);
            }};
    }

}
