package org.jocean.http;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

import org.jocean.http.util.Nettys;
import org.jocean.http.util.ParamUtil;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;

public class MessageUtil {
    private MessageUtil() {
        throw new IllegalStateException("No instances!");
    }

    public static SocketAddress uri2addr(final URI uri) {
        final int port = -1 == uri.getPort() ? ( "https".equals(uri.getScheme()) ? 443 : 80 ) : uri.getPort();
        return new InetSocketAddress(uri.getHost(), port);
    }

    public static Action1<Object> method(final HttpMethod method) {
        return new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (obj instanceof HttpRequest) {
                    ((HttpRequest)obj).setMethod(method);
                }
            }};
    }
    
    public static Action1<Object> path(final String path) {
        return new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (obj instanceof HttpRequest) {
                    ((HttpRequest)obj).setUri(path);
                }
            }};
    }
    
    public static Action1<Object> host(final URI uri) {
        return new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (obj instanceof HttpRequest) {
                    ((HttpRequest)obj).headers().set(HttpHeaderNames.HOST, uri.getHost());
                }
            }};
    }
    
    public static Observable<Object> fullRequestWithoutBody(final HttpVersion version, final HttpMethod method) {
        return Observable.just(new DefaultHttpRequest(version, method, ""), LastHttpContent.EMPTY_LAST_CONTENT);
    }
    
    private final static Transformer<DisposableWrapper<HttpObject>, MessageBody> _AS_BODY = new Transformer<DisposableWrapper<HttpObject>, MessageBody>() {
        @Override
        public Observable<MessageBody> call(final Observable<DisposableWrapper<HttpObject>> dwhs) {
            final Observable<? extends DisposableWrapper<HttpObject>> cached = dwhs.cache();
            return cached.map(DisposableWrapperUtil.<HttpObject>unwrap()).compose(RxNettys.asHttpMessage())
                    .map(new Func1<HttpMessage, MessageBody>() {
                        @Override
                        public MessageBody call(final HttpMessage msg) {
                            return new MessageBody() {
                                @Override
                                public String contentType() {
                                    return msg.headers().get(HttpHeaderNames.CONTENT_TYPE);
                                }

                                @Override
                                public int contentLength() {
                                    return HttpUtil.getContentLength(msg, -1);
                                }

                                @Override
                                public Observable<? extends DisposableWrapper<ByteBuf>> content() {
                                    return cached.flatMap(RxNettys.message2body());
                                }
                            };
                        }
                    });
        }
    };
        
    public static Transformer<DisposableWrapper<HttpObject>, MessageBody> asMessageBody() {
        return _AS_BODY;
    }
    
    public static <T> Observable<? extends T> decodeAs(final MessageBody body, final Class<T> type) {
        if (null != body.contentType()) {
            if (body.contentType().startsWith(HttpHeaderValues.APPLICATION_JSON.toString())) {
                return decodeJsonAs(body, type);
            } else if (body.contentType().startsWith("application/xml") || body.contentType().startsWith("text/xml")) {
                return decodeXmlAs(body, type);
            }
        }
        return Observable.error(new RuntimeException("can't decodeAs type:" + type));
    }

    public static <T> Observable<? extends T> decodeJsonAs(final MessageBody body, final Class<T> type) {
        return decodeContentAs(body.content(), new Func2<ByteBuf, Class<T>, T>() {
            @Override
            public T call(final ByteBuf buf, Class<T> clazz) {
                return ParamUtil.parseContentAsJson(buf, clazz);
            }
        }, type);
    }

    public static <T> Observable<? extends T> decodeXmlAs(final MessageBody body, final Class<T> type) {
        return decodeContentAs(body.content(), new Func2<ByteBuf, Class<T>, T>() {
            @Override
            public T call(final ByteBuf buf, Class<T> clazz) {
                return ParamUtil.parseContentAsXml(buf, clazz);
            }
        }, type);
    }

    // @Override
    // public <T> Observable<? extends T> decodeFormAs(final MessageUnit mu,
    // final Class<T> type) {
    // return Observable.error(new UnsupportedOperationException());
    // }
    private static <T> Observable<? extends T> decodeContentAs(
            final Observable<? extends DisposableWrapper<ByteBuf>> content, final Func2<ByteBuf, Class<T>, T> func,
            final Class<T> type) {
        return content.map(DisposableWrapperUtil.<ByteBuf>unwrap()).toList().map(new Func1<List<ByteBuf>, T>() {
            @Override
            public T call(final List<ByteBuf> bufs) {
                final ByteBuf buf = Nettys.composite(bufs);
                try {
                    return func.call(buf, type);
                } finally {
                    ReferenceCountUtil.release(buf);
                }
            }
        });
    }
}
