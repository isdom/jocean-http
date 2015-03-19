/**
 * 
 */
package org.jocean.http.util;

import io.netty.channel.ChannelHandler;

import java.io.Closeable;

import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public interface HandlersClosure extends Func1<ChannelHandler, ChannelHandler>, Closeable {
}
