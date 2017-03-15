/**
 * 
 */
package org.jocean.redis;

import java.net.SocketAddress;

import org.jocean.idiom.TerminateAware;

import io.netty.handler.codec.redis.RedisMessage;
import rx.Observable;

/**
 * @author isdom
 *
 */
public interface RedisClient extends AutoCloseable {
    public void close();
    
    public interface RedisConnection 
        extends AutoCloseable, TerminateAware<RedisConnection> {
        public void close();
        
        public boolean isActive();

        public Observable<? extends RedisMessage> defineInteraction(
                final Observable<? extends RedisMessage> request);
    }
    
    public Observable<? extends RedisConnection> getConnection(final SocketAddress remoteAddress);
    
    public Observable<? extends RedisConnection> getConnection();
}
