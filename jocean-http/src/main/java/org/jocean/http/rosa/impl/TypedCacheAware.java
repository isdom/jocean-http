package org.jocean.http.rosa.impl;

import org.jocean.idiom.SimpleCache;

public interface TypedCacheAware<V> {
    public void setTypedCache(final SimpleCache<Class<?>, V> cache);
}
