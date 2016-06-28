package org.jocean.http;

import rx.Subscription;
import rx.functions.Action1;

public interface DoOnUnsubscribe extends Action1<Subscription> {
}

