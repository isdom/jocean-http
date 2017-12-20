package org.jocean.http;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.client.HttpClient.InitiatorBuilder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.ParamUtil;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ReflectUtils;
import org.jocean.idiom.Terminable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringEncoder;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observable.Transformer;
import rx.Subscriber;
import rx.functions.Action1;
import rx.functions.Action2;
import rx.functions.Func1;
import rx.functions.Func2;

public class MessageUtil {
    private static final Logger LOG =
            LoggerFactory.getLogger(MessageUtil.class);
    
    private MessageUtil() {
        throw new IllegalStateException("No instances!");
    }

    private static final Feature F_SSL;
    static {
        F_SSL = defaultSslFeature();
    }

    private static Feature defaultSslFeature() {
        try {
            return new Feature.ENABLE_SSL(SslContextBuilder.forClient().build());
        } catch (Exception e) {
            LOG.error("exception init default ssl feature, detail: {}", ExceptionUtils.exception2detail(e));
            return null;
        }
    }

    public interface InteractionBuilder {
        
        public InteractionBuilder method(final HttpMethod method);
        
        public InteractionBuilder uri(final String uri);
        
        public InteractionBuilder path(final String path);
        
        public InteractionBuilder paramAsQuery(final String key, final String value);
        
        public InteractionBuilder reqbean(final Object... reqbeans);
        
        public InteractionBuilder body(final Func1<Terminable, Observable<MessageBody>> tobody);
        
        public InteractionBuilder onrequest(final Action1<Object> action);
        
        public InteractionBuilder feature(final Feature... features);
        
        public <RESP> Observable<? extends RESP> responseAs(final Class<RESP> resptype, Func2<ByteBuf, Class<RESP>, RESP> decoder);
        public Observable<? extends DisposableWrapper<HttpObject>> responseAs(final Terminable terminable);
    }
    
    public static InteractionBuilder interaction(final HttpClient client) {
        final InitiatorBuilder _initiatorBuilder = client.initiator();
        final AtomicBoolean _isSSLEnabled = new AtomicBoolean(false);
        final AtomicReference<Observable<Object>> _obsreqRef = new AtomicReference<>(
                fullRequestWithoutBody(HttpVersion.HTTP_1_1, HttpMethod.GET));
        final List<String> _nvs = new ArrayList<>();
        final AtomicReference<URI> _uriRef = new AtomicReference<>();
        final AtomicReference<Func1<Terminable, Observable<MessageBody>>> _asbodyRef = new AtomicReference<>(null);
        
        return new InteractionBuilder() {
            private void updateObsRequest(final Action1<Object> action) {
                _obsreqRef.set(_obsreqRef.get().doOnNext(action));
            }

            private void addQueryParams() {
                if (!_nvs.isEmpty()) {
                    updateObsRequest(MessageUtil.addQueryParam(_nvs.toArray(new String[0])));
                }
            }
            
            private void extractUriWithHost(final Object...reqbeans) {
                if (null == _uriRef.get()) {
                    for (Object bean : reqbeans) {
                        try {
                            final Path path = bean.getClass().getAnnotation(Path.class);
                            if (null != path) {
                                final URI uri = new URI(path.value());
                                if (null != uri.getHost()) {
                                    uri(path.value());
                                    return;
                                }
                            }
                        } catch (Exception e) {
                            LOG.warn("exception when extract uri from bean {}, detail: {}", 
                                    bean, ExceptionUtils.exception2detail(e));
                        }
                    }
                }
            }

            private void checkAddr() {
                if (null == _uriRef.get()) {
                    throw new RuntimeException("remote address not set.");
                }
            }
            
            private InitiatorBuilder addSSLFeatureIfNeed(final InitiatorBuilder builder) {
                if (_isSSLEnabled.get()) {
                    return builder;
                } else if ("https".equals(_uriRef.get().getScheme())) {
                    return builder.feature(F_SSL);
                } else {
                    return builder;
                }
            }
            
            @Override
            public InteractionBuilder method(final HttpMethod method) {
                updateObsRequest(MessageUtil.setMethod(method));
                return this;
            }

            @Override
            public InteractionBuilder uri(final String uriAsString) {
                try {
                    final URI uri = new URI(uriAsString);
                    _uriRef.set(uri);
                    _initiatorBuilder.remoteAddress(uri2addr(uri));
                    updateObsRequest(MessageUtil.setHost(uri));
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
                return this;
            }

            @Override
            public InteractionBuilder path(final String path) {
                updateObsRequest(MessageUtil.setPath(path));
                return this;
            }

            @Override
            public InteractionBuilder paramAsQuery(final String name, final String value) {
                _nvs.add(name);
                _nvs.add(value);
                return this;
            }

            @Override
            public InteractionBuilder reqbean(final Object... reqbeans) {
                updateObsRequest(MessageUtil.toRequest(reqbeans));
                extractUriWithHost(reqbeans);
                return this;
            }

            @Override
            public InteractionBuilder body(final Func1<Terminable, Observable<MessageBody>> asbody) {
                _asbodyRef.set(asbody);
                return this;
            }
            
            @Override
            public InteractionBuilder onrequest(final Action1<Object> action) {
                updateObsRequest(action);
                return this;
            }
            
            @Override
            public InteractionBuilder feature(final Feature... features) {
                _initiatorBuilder.feature(features);
                if (isSSLEnabled(features)) {
                    _isSSLEnabled.set(true);
                }
                return this;
            }

            private boolean isSSLEnabled(final Feature... features) {
                for (Feature f : features) {
                    if (f instanceof Feature.ENABLE_SSL) {
                        return true;
                    }
                }
                return false;
            }

            private Observable<? extends Object> addBody(final Observable<Object> obsreq,
                    final HttpInitiator initiator) {
                return null != _asbodyRef.get() ? obsreq.compose(MessageUtil.addBody(_asbodyRef.get().call(initiator)))
                        : obsreq;
            }
            
            @Override
            public <RESP> Observable<? extends RESP> responseAs(final Class<RESP> resptype,
                    final Func2<ByteBuf, Class<RESP>, RESP> decoder) {
                checkAddr();
                addQueryParams();
                return addSSLFeatureIfNeed(_initiatorBuilder).build()
                        .flatMap(new Func1<HttpInitiator, Observable<? extends RESP>>() {
                            @Override
                            public Observable<? extends RESP> call(final HttpInitiator initiator) {
                                return initiator.defineInteraction(addBody(_obsreqRef.get(), initiator))
                                        .compose(RxNettys.message2fullresp(initiator, true))
                                        .map(new Func1<DisposableWrapper<FullHttpResponse>, RESP>() {
                                            @Override
                                            public RESP call(final DisposableWrapper<FullHttpResponse> dwresp) {
                                                try {
                                                    return decoder.call(dwresp.unwrap().content(), resptype);
                                                } finally {
                                                    dwresp.dispose();
                                                }
                                            }
                                        }).doOnUnsubscribe(initiator.closer());
                            }

                        });
            }

            @Override
            public Observable<? extends DisposableWrapper<HttpObject>> responseAs(final Terminable terminable) {
                checkAddr();
                addQueryParams();
                return addSSLFeatureIfNeed(_initiatorBuilder).build()
                        .flatMap(new Func1<HttpInitiator, Observable<? extends DisposableWrapper<HttpObject>>>() {
                            @Override
                            public Observable<? extends DisposableWrapper<HttpObject>> call(
                                    final HttpInitiator initiator) {
                                terminable.doOnTerminate(initiator.closer());
                                return initiator.defineInteraction(addBody(_obsreqRef.get(), initiator));
                            }
                        });
            }
        };
    }
    
    public static SocketAddress uri2addr(final URI uri) {
        final int port = -1 == uri.getPort() ? ( "https".equals(uri.getScheme()) ? 443 : 80 ) : uri.getPort();
        return new InetSocketAddress(uri.getHost(), port);
    }

    public static SocketAddress bean2addr(final Object bean) {
        final Path path = bean.getClass().getAnnotation(Path.class);
        if (null!=path) {
            try {
                return uri2addr(new URI(path.value()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("bean class ("+ bean.getClass() +") without @Path annotation");
    }
    
    public static Action1<Object> setMethod(final HttpMethod method) {
        return new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (obj instanceof HttpRequest) {
                    ((HttpRequest)obj).setMethod(method);
                }
            }};
    }
    
    public static Action1<Object> setHost(final URI uri) {
        return new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (null != uri && null != uri.getHost() && obj instanceof HttpRequest) {
                    ((HttpRequest)obj).headers().set(HttpHeaderNames.HOST, uri.getHost());
                }
            }};
    }
    
    public static Action1<Object> setPath(final String path) {
        return new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (null != path && !path.isEmpty() && obj instanceof HttpRequest) {
                    ((HttpRequest)obj).setUri(path);
                }
            }};
    }
    
    public static Action1<Object> addQueryParam(final String... nvs) {
        return new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (obj instanceof HttpRequest) {
                    final HttpRequest request = (HttpRequest)obj;
                    if (nvs.length > 0) {
                        final QueryStringEncoder encoder = new QueryStringEncoder(request.uri());
                        int idx = 0;
                        while (idx+1 < nvs.length) {
                            encoder.addParam(nvs[idx], nvs[idx+1]);
                        }
                        request.setUri(encoder.toString());
                    }
                }
            }};
    }
    
    public static Action1<Object> toRequest(final Object... beans) {
        return new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (obj instanceof HttpRequest) {
                    final HttpRequest request = (HttpRequest)obj;
                    for (Object bean : beans) {
                        setUriToRequest(request, bean);
                        addQueryParams(request, bean);
                        addHeaderParams(request, bean);
                    }
                }
            }};
    }
    
    static void setUriToRequest(final HttpRequest request, final Object bean) {
        final Path apath = bean.getClass().getAnnotation(Path.class);
        if (null!=apath) {
            try {
                final URI uri = new URI(apath.value());
                if (null != uri.getHost() && null == request.headers().get(HttpHeaderNames.HOST)) {
                    request.headers().set(HttpHeaderNames.HOST, uri.getHost());
                }
                
                if (null != uri.getRawPath() && request.uri().isEmpty()) {
                    request.setUri(uri.getRawPath());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void addHeaderParams(final HttpRequest request, final Object bean) {
        final Field[] headerFields = ReflectUtils.getAnnotationFieldsOf(bean.getClass(), HeaderParam.class);
        if ( headerFields.length > 0 ) {
            for ( Field field : headerFields ) {
                try {
                    final Object value = field.get(bean);
                    if ( null != value ) {
                        final String headername = field.getAnnotation(HeaderParam.class).value();
                        request.headers().set(headername, value);
                    }
                } catch (Exception e) {
                    LOG.warn("exception when get value from field:[{}], detail:{}",
                            field, ExceptionUtils.exception2detail(e));
                }
            }
        }
    }

    private static void addQueryParams(final HttpRequest request, final Object bean) {
        final Field[] queryFields = ReflectUtils.getAnnotationFieldsOf(bean.getClass(), QueryParam.class);
        if ( queryFields.length > 0 ) {
            final QueryStringEncoder encoder = new QueryStringEncoder(request.uri());
            for (Field field : queryFields) {
                try {
                    final Object value = field.get(bean);
                    if ( null != value ) {
                        final String paramkey = field.getAnnotation(QueryParam.class).value();
                        encoder.addParam(paramkey, String.valueOf(value));
                    }
                }
                catch (Exception e) {
                    LOG.warn("exception when get field({})'s value, detail:{}", 
                            field, ExceptionUtils.exception2detail(e));
                }
            }
            
            request.setUri(encoder.toString());
        }
    }

    // TODO: support multipart/...
    public static Transformer<Object, Object> addBody(final Observable<MessageBody> body) {
        return new Transformer<Object, Object>() {
            @Override
            public Observable<Object> call(final Observable<Object> msg) {
                return msg.concatMap(new Func1<Object, Observable<Object>>() {
                    @Override
                    public Observable<Object> call(final Object obj) {
                        if (obj instanceof HttpRequest) {
                            final HttpRequest request = (HttpRequest)obj;
                            return body.flatMap(new Func1<MessageBody, Observable<Object>>() {
                                @Override
                                public Observable<Object> call(final MessageBody body) {
                                    request.headers().set(HttpHeaderNames.CONTENT_TYPE, body.contentType());
                                    // set content-length
                                    request.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.contentLength());
                                    return Observable.concat(Observable.just(request), body.content());
                                }});
                        } else {
                            return Observable.just(obj);
                        }
                    }});
            }
        };
    }
    
    public static Func1<Terminable, Observable<MessageBody>> beanToBody(final Object bean,
            final String contentType,
            final Action2<Object, ByteBuf> encoder) {
        return new Func1<Terminable, Observable<MessageBody>>() {
            @Override
            public Observable<MessageBody> call(final Terminable terminable) {
                return Observable.unsafeCreate(new OnSubscribe<MessageBody>() {
                    @Override
                    public void call(final Subscriber<? super MessageBody> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            final DisposableWrapper<ByteBuf> dwb = bean2dwb(bean, encoder, terminable);
                            final int contentLength = dwb.unwrap().readableBytes();
                            subscriber.onNext(tobody(contentType, contentLength, dwb));
                            subscriber.onCompleted();
                        }
                    }});
            }};
    }
    
    private static DisposableWrapper<ByteBuf> bean2dwb(final Object bean,
            final Action2<Object, ByteBuf> encoder, final Terminable terminable) {
        final ByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
        final DisposableWrapper<ByteBuf> dwbuf = DisposableWrapperUtil.disposeOn(terminable,
                RxNettys.wrap4release(allocator.buffer()));
        encoder.call(bean, dwbuf.unwrap());
        return dwbuf;
    }

    private static MessageBody tobody(final String contentType, final int contentLength,
            final DisposableWrapper<ByteBuf> dwb) {
        return new MessageBody() {
            @Override
            public String contentType() {
                return contentType;
            }

            @Override
            public int contentLength() {
                return contentLength;
            }

            @Override
            public Observable<? extends DisposableWrapper<ByteBuf>> content() {
                return Observable.just(dwb);
            }};
    }
    
    public static Observable<Object> fullRequestWithoutBody(final HttpVersion version, final HttpMethod method) {
        return Observable.<Object>just(new DefaultHttpRequest(version, method, ""), LastHttpContent.EMPTY_LAST_CONTENT);
    }
    
    public static Observable<Object> fullRequest(final Object... beans) {
        return fullRequestWithoutBody(HttpVersion.HTTP_1_1, HttpMethod.GET).doOnNext(MessageUtil.toRequest(beans));
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
        
    public static Transformer<DisposableWrapper<HttpObject>, MessageBody> asBody() {
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
