/**
 * 
 */
package org.jocean.http.client;

import io.netty.handler.codec.http.HttpObject;

import java.io.Closeable;
import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscriber;

/**
 * @author isdom
 *
 */
public interface HttpClient extends Closeable {
    
    public interface Progressable {
        public long progress();
    }
    
    public interface UploadProgressable extends Progressable {
    }
    
    public interface DownloadProgressable extends Progressable {
    }
    
    public abstract class ProgressiveSubscriber extends Subscriber<Object> {

        private static final Logger LOG =
                LoggerFactory.getLogger(HttpClient.class);
        
        public abstract void onUploadProgress(final long progress);
        
        public abstract void onDownloadProgress(final long progress);
        
        public abstract void onHttpObject(final HttpObject httpObject);
        
        @Override
        public void onNext(final Object obj) {
            if (obj instanceof UploadProgressable) {
                try {
                    onUploadProgress(((UploadProgressable)obj).progress());
                } catch (Exception e) {
                    LOG.warn("exception when onUploadProgress, detail: {}", 
                            ExceptionUtils.exception2detail(e));
                }
            } else if (obj instanceof DownloadProgressable) {
                try {
                    onDownloadProgress(((DownloadProgressable)obj).progress());
                } catch (Exception e) {
                    LOG.warn("exception when onDownloadProgress, detail: {}", 
                            ExceptionUtils.exception2detail(e));
                }
            } else if (obj instanceof HttpObject) {
                onHttpObject((HttpObject)obj);
            } else {
                LOG.warn("unknown object({}), just ignore", obj);
            }
        }
    }
    
    /**
     * 定义一次与http server的交互, 指定了远端地址和要发送的request和可选特性
     * 当返回的Observable<? extends Object> 实例被subscribe时,才基于上述指定的参数真正发起交互动作
     * @param remoteAddress 远端地址
     * @param request 要发送的HttpRequest (Object)*
     * @return Observable<Object> response: Observable of HttpObject, 
     * 推送内容为 HttpResponse + 0~N (HttpContent)
     */
    public Observable<? extends Object> defineInteraction(
            final SocketAddress remoteAddress, 
            final Observable<? extends Object> request,
            final Feature... features);
}
