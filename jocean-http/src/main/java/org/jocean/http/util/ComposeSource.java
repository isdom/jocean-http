package org.jocean.http.util;

import rx.Observable;

public interface ComposeSource {
    public <T> Observable.Transformer<T, T> transformer();
}
