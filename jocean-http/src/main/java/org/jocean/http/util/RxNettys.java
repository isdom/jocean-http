package org.jocean.http.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ProxyBuilder;
import org.jocean.idiom.Terminable;
import org.jocean.idiom.ToString;
import org.jocean.idiom.UnsafeOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import rx.Completable;
import rx.CompletableSubscriber;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subscriptions.BooleanSubscription;
import rx.subscriptions.Subscriptions;

public class RxNettys {
    private static final Logger LOG =
            LoggerFactory.getLogger(RxNettys.class);

    private RxNettys() {
        throw new IllegalStateException("No instances!");
    }

    public static <V> Completable future2Completable(final Future<V> future, final boolean cancelWhenUnsubscribe) {
        return Completable.create(new Completable.OnSubscribe() {
            @Override
            public void call(final CompletableSubscriber subscriber) {
                final Subscription subscription = cancelWhenUnsubscribe ? Subscriptions.create(new Action0() {
                    @Override
                    public void call() {
                        if (!future.isDone()) {
                            future.cancel(false);
                        }
                    }
                }) : new BooleanSubscription();

                subscriber.onSubscribe(subscription);

                future.addListener(new GenericFutureListener<Future<V>>() {
                    @Override
                    public void operationComplete(final Future<V> f) throws Exception {
                        if (!subscription.isUnsubscribed()) {
                            if (f.isSuccess()) {
                                subscriber.onCompleted();
                            } else {
                                subscriber.onError(f.cause());
                            }
                        }
                    }
                });
            }});
    }

    public static Observable<? extends Channel> channelObservableFromFuture(final ChannelFuture future) {
        return Observable.unsafeCreate(new Observable.OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> subscriber) {
                future.addListener(new GenericFutureListener<ChannelFuture>() {
                    @Override
                    public void operationComplete(final ChannelFuture f)
                            throws Exception {
                        if (!subscriber.isUnsubscribed()) {
                            if (f.isSuccess()) {
                                subscriber.onNext(f.channel());
                                subscriber.onCompleted();
                            } else {
                                subscriber.onError(f.cause());
                            }
                        }
                    }
                });
            }});
    }

    public static Func1<Channel, Observable<? extends Channel>> asyncConnectTo(
            final SocketAddress remoteAddress) {
        return new Func1<Channel, Observable<? extends Channel>>() {
            @Override
            public Observable<? extends Channel> call(final Channel channel) {
                return Observable.unsafeCreate(new Observable.OnSubscribe<Channel>() {
                    @Override
                    public void call(final Subscriber<? super Channel> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            final ChannelFuture f = channel.connect(remoteAddress)
                                .addListener(onSuccessNotifier(subscriber))
                                .addListener(onErrorNotifier(subscriber));
                            subscriber.add(Subscriptions.from(f));
                        }
                    }});
            }};
    }

    public static Func1<Channel, Observable<? extends Channel>> asyncConnectToMaybeSSL(
            final Func0<SocketAddress> remoteAddressProvider) {
        return new Func1<Channel, Observable<? extends Channel>>() {
            @Override
            public Observable<? extends Channel> call(final Channel channel) {
                return Observable.unsafeCreate(new Observable.OnSubscribe<Channel>() {
                    @Override
                    public void call(final Subscriber<? super Channel> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                        	final boolean sslEnabled = Nettys.isHandlerApplied(channel.pipeline(), HttpHandlers.SSL);
                        	if (sslEnabled) {
                                enableSSLNotifier(channel, subscriber);
                        	}
                            final ChannelFuture f = channel.connect(remoteAddressProvider.call());
                            if (!sslEnabled) {
                        	    f.addListener(onSuccessNotifier(subscriber));
                        	}
                            f.addListener(onErrorNotifier(subscriber));
                            subscriber.add(Subscriptions.from(f));
                        }
                    }});
            }};
    }

    private static ChannelFutureListener onSuccessNotifier(final Subscriber<? super Channel> subscriber) {
        return new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    Nettys.setChannelReady(future.channel());
                    subscriber.onNext(future.channel());
                    subscriber.onCompleted();
                }
            }};
    }

    private static ChannelFutureListener onErrorNotifier(final Subscriber<? super Channel> subscriber) {
        return new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    subscriber.onError(future.cause());
                }
            }};
    }

	private static void enableSSLNotifier(final Channel channel, final Subscriber<? super Channel> subscriber) {
	    Nettys.applyHandler(channel.pipeline(), HttpHandlers.SSLNOTIFY,
		    new Action1<Channel>() {
		        @Override
		        public void call(final Channel ch) {
		            Nettys.setChannelReady(ch);
		            subscriber.onNext(ch);
		            subscriber.onCompleted();
		            if (LOG.isDebugEnabled()) {
		                LOG.debug("channel({}): userEventTriggered for ssl handshake success", ch);
		            }
		        }},
		    new Action1<Throwable>() {
		        @Override
		        public void call(final Throwable e) {
		            subscriber.onError(e);
		            LOG.warn("channel({}): userEventTriggered for ssl handshake failure:{}",
		                    channel,
		                    ExceptionUtils.exception2detail(e));
		        }});
	}

    public static Subscription subscriptionForCloseChannel(final Channel channel) {
        return Subscriptions.create(new Action0() {
            @Override
            public void call() {
                channel.close();
            }});
    }

    public static byte[] httpObjectsAsBytes(final Iterator<HttpObject> itr)
            throws IOException {
        final CompositeByteBuf composite = Unpooled.compositeBuffer();
        try {
            while (itr.hasNext()) {
                final HttpObject obj = itr.next();
                if (obj instanceof HttpContent) {
                    composite.addComponent(((HttpContent)obj).content());
                }
            }
            composite.setIndex(0, composite.capacity());

            @SuppressWarnings("resource")
            final InputStream is = new ByteBufInputStream(composite);
            final byte[] bytes = new byte[is.available()];
            is.read(bytes);
            return bytes;
        } finally {
            ReferenceCountUtil.release(composite);
        }
    }

    public static <T> void releaseObjects(final Collection<T> objs) {
        synchronized (objs) {
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("start to releaseObjects ({}).", UnsafeOp.toAddress(objs));
                }
                for (final T obj : objs) {
                    try {
                        if (ReferenceCountUtil.release(obj)) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("({}) released and deallocated success.", obj);
                            }
                        } else {
                            if (LOG.isDebugEnabled()) {
                                if ( obj instanceof ReferenceCounted) {
                                    LOG.debug("({}) released BUT refcnt == {} > 0.", obj, ((ReferenceCounted)obj).refCnt());
                                }
                            }
                        }
                    } catch (final Exception e) {
                        LOG.warn("exception when ReferenceCountUtil.release {}, detail: {}",
                                obj, ExceptionUtils.exception2detail(e));
                    }
                }
            } finally {
                objs.clear();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("end of releaseObjects ({}).", UnsafeOp.toAddress(objs));
                }
            }
        }
    }

    public static <E, T> Observable.Transformer<? super T, ? extends T> releaseAtLast(final Collection<E> objs) {
        return new Observable.Transformer<T, T>() {
            @Override
            public Observable<T> call(final Observable<T> source) {
                return source.doAfterTerminate(new Action0() {
                        @Override
                        public void call() {
                            if (LOG.isDebugEnabled() ) {
                                LOG.debug("finallyDo: releaseObjects for objs:{}", ToString.toMultiline(objs));
                            }
                            RxNettys.releaseObjects(objs);
                        }})
                    .doOnUnsubscribe(new Action0() {
                        @Override
                        public void call() {
                            if (LOG.isDebugEnabled() ) {
                                LOG.debug("doOnUnsubscribe: releaseObjects for objs:{}", ToString.toMultiline(objs));
                            }
                            RxNettys.releaseObjects(objs);
                        }});
            }};
    }

    public static Observable<HttpObject> response401Unauthorized(
            final HttpVersion version, final String vlaueOfWWWAuthenticate) {
        final HttpResponse response = new DefaultFullHttpResponse(
                version, HttpResponseStatus.UNAUTHORIZED);
        response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, vlaueOfWWWAuthenticate);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        return Observable.<HttpObject>just(response);
    }

    public static Observable<HttpObject> response200OK(
            final HttpVersion version) {
        final HttpResponse response = new DefaultFullHttpResponse(
                version, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        return Observable.<HttpObject>just(response);
    }

    public static Observable<HttpObject> response404NOTFOUND(
            final HttpVersion version) {
        final HttpResponse response = new DefaultFullHttpResponse(
                version, HttpResponseStatus.NOT_FOUND);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        return Observable.<HttpObject>just(response);
    }

    private final static Func1<DisposableWrapper<? extends HttpObject>, Observable<DisposableWrapper<ByteBuf>>>
        _MSGTOBODY = new Func1<DisposableWrapper<? extends HttpObject>, Observable<DisposableWrapper<ByteBuf>>>() {
        @Override
        public Observable<DisposableWrapper<ByteBuf>> call(final DisposableWrapper<? extends HttpObject> dwh) {
            if (dwh.unwrap() instanceof HttpContent) {
                return Observable.just(dwc2dwb(dwh));
            } else {
                return Observable.empty();
            }
        }};

    public static Func1<DisposableWrapper<? extends HttpObject>, Observable<DisposableWrapper<ByteBuf>>> message2body() {
        return _MSGTOBODY;
    }

    private static List<HttpObject> dwhs2hobjs(final Iterable<DisposableWrapper<? extends HttpObject>> dwhs) {
        final List<HttpObject> hobjs = new LinkedList<>();
        for (final DisposableWrapper<? extends HttpObject> dwh : dwhs) {
            hobjs.add(dwh.unwrap());
        }
        return hobjs;
    }

    private static void disposeAll(final Iterable<DisposableWrapper<? extends HttpObject>> dwhs) {
        for (final DisposableWrapper<? extends HttpObject> dwh : dwhs) {
            dwh.dispose();
        }
    }

    public static Observable.Transformer<? super DisposableWrapper<? extends HttpObject>, ? extends DisposableWrapper<FullHttpRequest>> message2fullreq(
            final Terminable terminable) {
        return message2fullreq(terminable, false);
    }

    public static Observable.Transformer<? super DisposableWrapper<? extends HttpObject>, ? extends DisposableWrapper<FullHttpRequest>> message2fullreq(
            final Terminable terminable, final boolean disposemsg) {
        return new Observable.Transformer<DisposableWrapper<? extends HttpObject>, DisposableWrapper<FullHttpRequest>>() {
            @Override
            public Observable<DisposableWrapper<FullHttpRequest>> call(
                    final Observable<DisposableWrapper<? extends HttpObject>> dwhs) {
                return dwhs.toList()
                        .map(new Func1<List<DisposableWrapper<? extends HttpObject>>, DisposableWrapper<FullHttpRequest>>() {
                            @Override
                            public DisposableWrapper<FullHttpRequest> call(
                                    final List<DisposableWrapper<? extends HttpObject>> dwhs) {
                                final FullHttpRequest fullreq = Nettys.httpobjs2fullreq(dwhs2hobjs(dwhs));
                                try {
                                    return DisposableWrapperUtil.disposeOn(terminable, RxNettys.wrap4release(fullreq));
                                } finally {
                                    if (disposemsg) {
                                        disposeAll(dwhs);
                                    }
                                }
                            }
                        });
            }
        };
    }

    public static Observable.Transformer<? super DisposableWrapper<? extends HttpObject>, ? extends DisposableWrapper<FullHttpResponse>> message2fullresp(
            final Terminable terminable) {
        return message2fullresp(terminable, false);
    }

    public static Observable.Transformer<? super DisposableWrapper<? extends HttpObject>, ? extends DisposableWrapper<FullHttpResponse>> message2fullresp(
            final Terminable terminable, final boolean disposemsg) {
        return new Observable.Transformer<DisposableWrapper<? extends HttpObject>, DisposableWrapper<FullHttpResponse>>() {
            @Override
            public Observable<DisposableWrapper<FullHttpResponse>> call(
                    final Observable<DisposableWrapper<? extends HttpObject>> dwhs) {
                return dwhs.toList()
                        .map(new Func1<List<DisposableWrapper<? extends HttpObject>>, DisposableWrapper<FullHttpResponse>>() {
                            @Override
                            public DisposableWrapper<FullHttpResponse> call(
                                    final List<DisposableWrapper<? extends HttpObject>> dwhs) {
                                final FullHttpResponse fullresp = Nettys.httpobjs2fullresp(dwhs2hobjs(dwhs));
                                try {
                                    return DisposableWrapperUtil.disposeOn(terminable, RxNettys.wrap4release(fullresp));
                                } finally {
                                    if (disposemsg) {
                                        disposeAll(dwhs);
                                    }
                                }
                            }
                        });
            }
        };
    }

    public static Func1<HttpObject[], FullHttpRequest> BUILD_FULL_REQUEST = new Func1<HttpObject[], FullHttpRequest>() {
        @Override
        public FullHttpRequest call(final HttpObject[] httpobjs) {
            if (LOG.isDebugEnabled()) {
                int idx = 0;
                for (final HttpObject httpobj : httpobjs) {
                    LOG.debug("BUILD_FULL_REQUEST: dump [{}] httpobj {}", ++idx, httpobj);
                }
            }

            if (httpobjs.length>0
            && (httpobjs[0] instanceof HttpRequest)
            && (httpobjs[httpobjs.length-1] instanceof LastHttpContent)) {
                if (httpobjs[0] instanceof FullHttpRequest) {
                    return ((FullHttpRequest)httpobjs[0]).retainedDuplicate();
                }

                final HttpRequest req = (HttpRequest)httpobjs[0];
                final ByteBuf[] bufs = new ByteBuf[httpobjs.length-1];
                for (int idx = 1; idx<httpobjs.length; idx++) {
                    bufs[idx-1] = ((HttpContent)httpobjs[idx]).content().retain();
                }
                final DefaultFullHttpRequest fullreq = new DefaultFullHttpRequest(
                        req.protocolVersion(),
                        req.method(),
                        req.uri(),
                        Unpooled.wrappedBuffer(bufs));
                fullreq.headers().add(req.headers());
                //  ? need update Content-Length header field ?
                return fullreq;
            } else {
                return null;
            }
        }};
    public static Func1<HttpObject[], FullHttpResponse> BUILD_FULL_RESPONSE = new Func1<HttpObject[], FullHttpResponse>() {
        @Override
        public FullHttpResponse call(final HttpObject[] httpobjs) {
            if (httpobjs.length>0
            && (httpobjs[0] instanceof HttpResponse)
            && (httpobjs[httpobjs.length-1] instanceof LastHttpContent)) {
                if (httpobjs[0] instanceof FullHttpResponse) {
                    return ((FullHttpResponse)httpobjs[0]).retainedDuplicate();
                }

                final HttpResponse resp = (HttpResponse)httpobjs[0];
                final ByteBuf[] bufs = new ByteBuf[httpobjs.length-1];
                for (int idx = 1; idx<httpobjs.length; idx++) {
                    bufs[idx-1] = ((HttpContent)httpobjs[idx]).content().retain();
                }
                final DefaultFullHttpResponse fullresp = new DefaultFullHttpResponse(
                        resp.protocolVersion(),
                        resp.status(),
                        Unpooled.wrappedBuffer(bufs));
                fullresp.headers().add(resp.headers());
                //  ? need update Content-Length header field ?
                return fullresp;
            } else {
                return null;
            }
        }};

    public static Func1<HttpObject, Observable<? extends HttpObject>> splitFullHttpMessage() {
        return SPLIT_FULLHTTPMSG;
    }

    private final static Func1<HttpObject, Observable<? extends HttpObject>> SPLIT_FULLHTTPMSG =
            new Func1<HttpObject, Observable<? extends HttpObject>>() {
            @Override
            public Observable<? extends HttpObject> call(final HttpObject obj) {
                if (obj instanceof FullHttpRequest) {
                    return Observable.just(requestOf((HttpRequest)obj), lastContentOf((FullHttpMessage)obj));
                } else if (obj instanceof FullHttpResponse) {
                    return Observable.just(responseOf((HttpResponse)obj), lastContentOf((FullHttpMessage)obj));
                } else {
                    return Observable.just(obj);
                }
            }};

    private static HttpRequest requestOf(final HttpRequest req) {
        return new ProxyBuilder<>(HttpRequest.class, req).buildProxy();
    }

    private static HttpResponse responseOf(final HttpResponse resp) {
        return new ProxyBuilder<>(HttpResponse.class, resp).buildProxy();
    }

    private static LastHttpContent lastContentOf(final FullHttpMessage msg) {
        return new ProxyBuilder<>(LastHttpContent.class, msg).buildProxy();
    }

    public static Func1<DisposableWrapper<? extends HttpObject>, Observable<? extends DisposableWrapper<? extends HttpObject>>> splitdwhs() {
        return SPLIT_DWHS;
    }

    private final static Func1<DisposableWrapper<? extends HttpObject>, Observable<? extends DisposableWrapper<? extends HttpObject>>> SPLIT_DWHS
        = new Func1<DisposableWrapper<? extends HttpObject>, Observable<? extends DisposableWrapper<? extends HttpObject>>>() {
        @Override
        public Observable<? extends DisposableWrapper<? extends HttpObject>> call(final DisposableWrapper<? extends HttpObject> dwh) {
            if (dwh.unwrap() instanceof FullHttpRequest) {
                return Observable.just(
                        DisposableWrapperUtil.<HttpObject>wrap(requestOf((HttpRequest) dwh.unwrap()), dwh),
                        DisposableWrapperUtil.<HttpObject>wrap(lastContentOf((FullHttpMessage) dwh.unwrap()), dwh));
            } else if (dwh.unwrap() instanceof FullHttpResponse) {
                return Observable.just(
                        DisposableWrapperUtil.<HttpObject>wrap(responseOf((HttpResponse) dwh.unwrap()), dwh),
                        DisposableWrapperUtil.<HttpObject>wrap(lastContentOf((FullHttpMessage) dwh.unwrap()), dwh));
            } else {
                return Observable.just(dwh);
            }
        }
    };

    //  对 HttpMessage 中的 HttpContent 产生独立的 readIndex & writeIndex
    public static Observable.Transformer<? super HttpObject, ? extends HttpObject> duplicateHttpContent() {
        return new Observable.Transformer<HttpObject, HttpObject>() {
            @Override
            public Observable<HttpObject> call(final Observable<HttpObject> source) {
                return source.map(new Func1<HttpObject, HttpObject>() {
                    @Override
                    public HttpObject call(final HttpObject httpobj) {
                        if (httpobj instanceof HttpContent) {
                            return ((HttpContent)httpobj).duplicate();
                        } else {
                            return httpobj;
                        }
                    }});
            }};
    }

    private final static Observable.Transformer<HttpObject, HttpRequest> AS_HTTPREQ =
        new Observable.Transformer<HttpObject, HttpRequest>() {
            @Override
            public Observable<HttpRequest> call(final Observable<HttpObject> httpMessage) {
                return httpMessage.first().map(new Func1<HttpObject, HttpRequest>() {
                    @Override
                    public HttpRequest call(final HttpObject httpobj) {
                        if (httpobj instanceof HttpRequest) {
                            return (HttpRequest)httpobj;
                        } else {
                            throw new RuntimeException("First HttpObject is not HttpRequest.");
                        }
                    }});
            }};

    public static Observable.Transformer<? super HttpObject, ? extends HttpRequest> asHttpRequest() {
        return AS_HTTPREQ;
    }

    private final static Observable.Transformer<HttpObject, HttpResponse> AS_HTTPRESP =
            new Observable.Transformer<HttpObject, HttpResponse>() {
                @Override
                public Observable<HttpResponse> call(final Observable<HttpObject> httpMessage) {
                    return httpMessage.first().map(new Func1<HttpObject, HttpResponse>() {
                        @Override
                        public HttpResponse call(final HttpObject httpobj) {
                            if (httpobj instanceof HttpResponse) {
                                return (HttpResponse)httpobj;
                            } else {
                                throw new RuntimeException("First HttpObject is not HttpResponse.");
                            }
                        }});
                }};

    public static Observable.Transformer<? super HttpObject, ? extends HttpResponse> asHttpResponse() {
        return AS_HTTPRESP;
    }

    private final static Func1<HttpObject, HttpMessage> _HOBJ2MSG = new Func1<HttpObject, HttpMessage>() {
        @Override
        public HttpMessage call(final HttpObject httpobj) {
            if (httpobj instanceof HttpMessage) {
                return (HttpMessage)httpobj;
            } else {
                throw new RuntimeException("First HttpObject is not HttpMessage.");
            }
        }};

    private final static Observable.Transformer<HttpObject, HttpMessage> _AS_HTTPMSG =
            new Observable.Transformer<HttpObject, HttpMessage>() {
                @Override
                public Observable<HttpMessage> call(final Observable<HttpObject> hobjs) {
                    return hobjs.first().map(_HOBJ2MSG);
                }};

    public static Observable.Transformer<? super HttpObject, ? extends HttpMessage> asHttpMessage() {
        return _AS_HTTPMSG;
    }

    private final static Observable.Transformer<ChannelFuture, Channel> CHANNELFUTURE_CHANNEL =
            new Observable.Transformer<ChannelFuture, Channel>() {
                @Override
                public Observable<Channel> call(final Observable<ChannelFuture> source) {
                    return source.flatMap(new Func1<ChannelFuture, Observable<? extends Channel>>() {
                        @Override
                        public Observable<? extends Channel> call(
                                final ChannelFuture f) {
                            return channelObservableFromFuture(f);
                        }});
                }};

    public static Observable.Transformer<ChannelFuture, Channel> channelFutureToChannel() {
        return CHANNELFUTURE_CHANNEL;
    }

    private static Action1<Object> DISPOSE_REF = new Action1<Object>() {
        @Override
        public void call(final Object obj) {
            String logmsg = null;
            if (LOG.isTraceEnabled()) {
                logmsg = obj.toString() + " disposed at " +
                    ExceptionUtils.dumpCallStack(new Throwable(), null, 3) +
                    "\r\n and release with ({})"
                ;
            }
            final boolean released = ReferenceCountUtil.release(obj);
            if (LOG.isTraceEnabled()) {
                LOG.trace(logmsg, released);
            }
        }};

    @SuppressWarnings("unchecked")
    public static <T> Action1<T> disposerOf() {
        return (Action1<T>)DISPOSE_REF;
    }

    public static <T> DisposableWrapper<T> wrap4release(final T unwrap) {
        return DisposableWrapperUtil.wrap(unwrap, RxNettys.<T>disposerOf());
    }

    public static DisposableWrapper<ByteBuf> dwc2dwb(final DisposableWrapper<? extends HttpObject> dwh) {
        if (dwh.unwrap() instanceof HttpContent) {
            return DisposableWrapperUtil.wrap(((HttpContent) dwh.unwrap()).content(), dwh);
        } else {
            return null;
        }
    }

    public static Observable.Transformer<? super DisposableWrapper<HttpObject>, ? extends DisposableWrapper<HttpObject>> assembleTo(
            final int maxBufSize, final Terminable terminable) {
        return new Observable.Transformer<DisposableWrapper<HttpObject>, DisposableWrapper<HttpObject>>() {
            @Override
            public Observable<DisposableWrapper<HttpObject>> call(
                    final Observable<DisposableWrapper<HttpObject>> obsdwh) {
                if (maxBufSize > 0) {
                    final Observable<DisposableWrapper<HttpObject>> shared = obsdwh.share();
                    return shared.buffer(shared.flatMap(limitBufferSizeTo(maxBufSize))).flatMap(assemble(terminable));
                } else {
                    return obsdwh;
                }
            }
        };
    }

    private static Func1<DisposableWrapper<HttpObject>, Observable<? extends Integer>> limitBufferSizeTo(
            final int maxBufSize) {
        final AtomicInteger size = new AtomicInteger(0);

        return new Func1<DisposableWrapper<HttpObject>, Observable<? extends Integer>>() {
            @Override
            public Observable<? extends Integer> call(final DisposableWrapper<HttpObject> wrapper) {
                if (wrapper.unwrap() instanceof HttpContent) {
                    if (size.addAndGet(((HttpContent) wrapper.unwrap()).content().readableBytes()) > maxBufSize) {
                        // reset size counter
                        size.set(0);
                        return Observable.just(0);
                    }
                }
                return Observable.empty();
            }
        };
    }

    private static Func1<List<? extends DisposableWrapper<HttpObject>>, Observable<? extends DisposableWrapper<HttpObject>>> assemble(
            final Terminable terminable) {
        return new Func1<List<? extends DisposableWrapper<HttpObject>>, Observable<? extends DisposableWrapper<HttpObject>>>() {
            @Override
            public Observable<? extends DisposableWrapper<HttpObject>> call(
                    final List<? extends DisposableWrapper<HttpObject>> dwhs) {
                final Queue<DisposableWrapper<HttpObject>> assembled = new LinkedList<>();
                final Queue<DisposableWrapper<ByteBuf>> dwbs = new LinkedList<>();
                for (final DisposableWrapper<HttpObject> dwh : dwhs) {
                    if (dwh.unwrap() instanceof HttpMessage) {
                        assembled.add(dwh);
                    } else if (dwh.unwrap() instanceof LastHttpContent) {
                        dwbs.add(RxNettys.dwc2dwb(dwh));
                        add2dwhs(dwbs2dwh(dwbs, true, terminable), assembled);
                    } else if (dwh.unwrap() instanceof HttpContent) {
                        dwbs.add(RxNettys.dwc2dwb(dwh));
                    }
                }
                add2dwhs(dwbs2dwh(dwbs, false, terminable), assembled);
                return Observable.from(assembled);
            }
        };
    }

    private static DisposableWrapper<HttpObject> dwbs2dwh(
            final Collection<DisposableWrapper<ByteBuf>> dwbs,
            final boolean islast,
            final Terminable terminable) {
        if (!dwbs.isEmpty()) {
            try {
                final HttpObject hobj = islast ? new DefaultLastHttpContent(Nettys.dwbs2buf(dwbs))
                        : new DefaultHttpContent(Nettys.dwbs2buf(dwbs));
                if (null!=terminable) {
                    return DisposableWrapperUtil.disposeOn(terminable, RxNettys.wrap4release(hobj));
                } else {
                    return RxNettys.wrap4release(hobj);
                }
            } finally {
                for (final DisposableWrapper<ByteBuf> dwb : dwbs) {
                    try {
                        dwb.dispose();
                    } catch (final Exception e) {
                    }
                }
                dwbs.clear();
            }
        } else {
            return null;
        }
    }

    private static void add2dwhs(final DisposableWrapper<HttpObject> dwh,
            final Collection<DisposableWrapper<HttpObject>> assembled) {
        if (null != dwh) {
            assembled.add(dwh);
        }
    }
}
