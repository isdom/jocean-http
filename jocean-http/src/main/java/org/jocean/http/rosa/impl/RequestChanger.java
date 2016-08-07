package org.jocean.http.rosa.impl;

import org.jocean.idiom.Ordered;

import io.netty.handler.codec.http.HttpRequest;
import rx.functions.Action1;

public interface RequestChanger extends Action1<HttpRequest>, Ordered {

}
