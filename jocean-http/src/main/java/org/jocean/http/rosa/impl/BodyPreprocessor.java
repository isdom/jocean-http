package org.jocean.http.rosa.impl;

import io.netty.handler.codec.http.HttpRequest;
import rx.functions.Func2;

/**
 * @author isdom
 *
 */
public interface BodyPreprocessor extends Func2<Object, HttpRequest, BodyBuilder> {

}
