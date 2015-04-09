/**
 * 
 */
package org.jocean.http.server;

import io.netty.channel.Channel;

import java.io.Closeable;
import java.net.SocketAddress;

import rx.Observable;
import rx.functions.Action1;

/**
 * @author isdom
 *
 */
public interface HttpServer extends Closeable {
    public Observable<HttpTrade> create(
            final SocketAddress localAddress, 
            @SuppressWarnings("unchecked") 
            final Action1<Channel> ... features);
}
