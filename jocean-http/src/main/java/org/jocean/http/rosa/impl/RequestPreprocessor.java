package org.jocean.http.rosa.impl;

import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public interface RequestPreprocessor extends Func1<Object, RequestChanger> {

}
