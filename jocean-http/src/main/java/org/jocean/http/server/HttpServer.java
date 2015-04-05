/**
 * 
 */
package org.jocean.http.server;

import io.netty.channel.Channel;

import java.io.Closeable;
import java.net.SocketAddress;

import rx.Observable;

/**
 * @author isdom
 *
 */
public interface HttpServer extends Closeable {
    public Observable<Channel> create(final SocketAddress localAddress);
}
