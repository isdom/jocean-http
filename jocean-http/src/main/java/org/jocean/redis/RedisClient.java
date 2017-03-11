/**
 * 
 */
package org.jocean.redis;

import java.net.SocketAddress;

import org.jocean.idiom.rx.DoOnUnsubscribe;

import io.netty.handler.codec.redis.RedisMessage;
import rx.Observable;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public interface RedisClient extends AutoCloseable {
    public void close();
    
    public interface RedisConnection extends AutoCloseable {
        public void close();
        /**
         * 定义一次与redis server的交互, 指定了远端地址，通过 requestProvider 创建 request
         * 当返回的Observable<? extends RedisMessage> 实例被subscribe时,才基于上述指定的参数真正发起交互动作
         * @param remoteAddress 远端地址
         * @param requestProvider 要发送的HttpRequest (Object)* 的发生器，创建的 request 可使用传入的 DoOnUnsubscribe
         *          实例，控制 request 相关资源的释放，该 DoOnUnsubscribe 实例确保和特定的一次交互生命周期一致
         * @return Observable<Object> response: Observable of HttpObject, 
         * 推送内容为 HttpResponse + 0~N (HttpContent)
         */
        public Observable<? extends RedisMessage> defineInteraction(
                final Func1<DoOnUnsubscribe,Observable<? extends RedisMessage>> requestProvider);
        
        public Observable<? extends RedisMessage> defineInteraction(
                final Observable<? extends RedisMessage> request);
    }
    
    public Observable<? extends RedisConnection> getConnection(final SocketAddress remoteAddress);
    
    public Observable<? extends RedisConnection> getConnection();
}
