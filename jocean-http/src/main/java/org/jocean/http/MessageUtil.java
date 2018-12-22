package org.jocean.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

import org.jocean.http.ContentEncoder.EncodeAware;
import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.client.HttpClient.InitiatorBuilder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.Beans;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ReflectUtils;
import org.jocean.idiom.Stepable;
import org.jocean.idiom.StepableUtil;
import org.jocean.idiom.Terminable;
import org.jocean.netty.util.BufsOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.PropertyFilter;
import com.alibaba.fastjson.serializer.SerializeConfig;
import com.alibaba.fastjson.serializer.SerializeFilter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.QueryStringEncoder;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;
import rx.functions.Action2;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.Func2;

public class MessageUtil {

    public static final Transformer<HttpSlice, DisposableWrapper<? extends HttpObject>> AUTOSTEP2DWH =
            StepableUtil.<HttpSlice, DisposableWrapper<? extends HttpObject>>autostep2element2();

    public static final Transformer<ByteBufSlice, DisposableWrapper<? extends ByteBuf>> AUTOSTEP2DWB =
            StepableUtil.<ByteBufSlice, DisposableWrapper<? extends ByteBuf>>autostep2element2();

    private static final Logger LOG =
            LoggerFactory.getLogger(MessageUtil.class);

    private MessageUtil() {
        throw new IllegalStateException("No instances!");
    }

    public static Func0<DisposableWrapper<ByteBuf>> pooledAllocator(final Terminable terminable, final int pageSize) {
        return new Func0<DisposableWrapper<ByteBuf>>() {
            @Override
            public DisposableWrapper<ByteBuf> call() {
                return DisposableWrapperUtil.disposeOn(terminable,
                        RxNettys.wrap4release(PooledByteBufAllocator.DEFAULT.buffer(pageSize, pageSize)));
            }};
    }

    public static Action1<HttpRequest> injectQueryParams(final Object bean) {
        return new Action1<HttpRequest>() {
            @Override
            public void call(final HttpRequest request) {
                request2QueryParams(request, bean);
            }};
    }

    public static void request2QueryParams(final HttpRequest request, final Object bean) {
        final Field[] fields = ReflectUtils.getAnnotationFieldsOf(bean.getClass(), QueryParam.class);
        if (null != fields) {
            final QueryStringDecoder decoder = new QueryStringDecoder(request.uri());

            for (final Field field : fields) {
                final String key = field.getAnnotation(QueryParam.class).value();
                if (!"".equals(key) && null != decoder.parameters()) {
                    // for case: QueryParam("demo")
                    injectParamValue(decoder.parameters().get(key), bean, field);
                }
                if ("".equals(key)) {
                    // for case: QueryParam(""), means fill with entire query string
                    injectValueToField(rawQuery(request.uri()), bean, field);
                }
            }
        }
    }

    public static String rawQuery(final String uri) {
        final int pos = uri.indexOf('?');
        if (-1 != pos) {
            return uri.substring(pos+1);
        } else {
            return null;
        }
    }

    public static Action1<HttpRequest> injectHeaderParams(final Object bean) {
        return new Action1<HttpRequest>() {
            @Override
            public void call(final HttpRequest request) {
                request2HeaderParams(request, bean);
            }};
    }

    public static void request2HeaderParams(final HttpRequest request, final Object bean) {
        final Field[] fields = ReflectUtils.getAnnotationFieldsOf(bean.getClass(), HeaderParam.class);
        if (null != fields) {
            for (final Field field : fields) {
                injectParamValue(request.headers().getAll(field.getAnnotation(HeaderParam.class).value()),
                    bean,
                    field
                );
            }
        }
    }

    private static void injectParamValue(
            final List<String> values,
            final Object obj,
            final Field field) {
        if (null != values && values.size() > 0) {
            injectValueToField(values.get(0), obj, field);
        }
    }

    public static <T> T getAsType(final List<String> list, final Class<T> type) {
        if (null != list && list.size() > 0) {
            return Beans.fromString(list.get(0), type);
        } else {
            return null;
        }
    }

    /**
     * @param value
     * @param obj
     * @param field
     */
    private static void injectValueToField(
            final String value,
            final Object obj,
            final Field field) {
        if (null != value) {
            try {
                field.set(obj, Beans.fromString(value, field.getType()));
            } catch (final Exception e) {
                LOG.warn("exception when set obj({}).{} with value({}), detail:{} ",
                        obj, field.getName(), value, ExceptionUtils.exception2detail(e));
            }
        }
    }

    public static <T> T unserializeAsXml(final InputStream is, final Class<T> type) {
        final XmlMapper mapper = new XmlMapper();
        mapper.addHandler(new DeserializationProblemHandler() {
            @Override
            public boolean handleUnknownProperty(final DeserializationContext ctxt, final JsonParser p,
                    final JsonDeserializer<?> deserializer, final Object beanOrClass, final String propertyName)
                    throws IOException {
                LOG.warn("UnknownProperty [{}], just skip", propertyName);
                p.skipChildren();
                return true;
            }});
        try {
            return mapper.readValue(is, type);
        } catch (final Exception e) {
            LOG.warn("exception when parse as xml, detail: {}", ExceptionUtils.exception2detail(e));
            return null;
        }
    }

    public static <T> T unserializeAsJson(final InputStream is, final Class<T> type) {
        try {
            return JSON.parseObject(is, type);
        } catch (final IOException e) {
            LOG.warn("exception when parse as json, detail: {}", ExceptionUtils.exception2detail(e));
            return null;
        }
    }

    public static <RESP> Func2<InputStream, Class<RESP>, RESP> unserializeAsXml() {
        return new Func2<InputStream, Class<RESP>, RESP>() {
            @Override
            public RESP call(final InputStream is, final Class<RESP> type) {
                return unserializeAsXml(is, type);
            }};
    }

    public static <RESP> Func2<InputStream, Class<RESP>, RESP> unserializeAsJson() {
        return new Func2<InputStream, Class<RESP>, RESP>() {
            @Override
            public RESP call(final InputStream is, final Class<RESP> type) {
                return unserializeAsJson(is, type);
            }};
    }

    public static <T> T unserializeAsX_WWW_FORM_URLENCODED(final InputStream is, final Class<T> type) {
        final String kvs = parseContentAsString(is);
        if (null != kvs) {
            final T bean = ReflectUtils.newInstance(type);
            if (null != bean) {
                final QueryStringDecoder decoder = new QueryStringDecoder(kvs, CharsetUtil.UTF_8, false);

                final Field[] fields = ReflectUtils.getAnnotationFieldsOf(type, QueryParam.class);
                if (null != fields) {
                    for (final Field field : fields) {
                        final String key = field.getAnnotation(QueryParam.class).value();
                        injectParamValue(decoder.parameters().get(key), bean, field);
                    }
                }

                return bean;
            }
        }
        return null;
    }

    public static String parseContentAsString(final InputStream is) {
        try {
            final byte[] bytes = new byte[is.available()];
            is.read(bytes);
            return new String(bytes, CharsetUtil.UTF_8);
        } catch (final IOException e) {
            LOG.warn("exception when parse {} as string, detail: {}",
                    is, ExceptionUtils.exception2detail(e));
            return null;
        }
    }

    public static InputStream contentAsInputStream(final ByteBuf buf) {
        return new ByteBufInputStream(buf.slice());
    }

    public static void serializeToXml(final Object bean, final OutputStream out) {
        final XmlMapper mapper = new XmlMapper();
//        mapper.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        try {
            mapper.writeValue(out, bean);
        } catch (final Exception e) {
            LOG.warn("exception when serialize {} to xml, detail: {}",
                    bean, ExceptionUtils.exception2detail(e));
        }
    }

    public static void serializeToJson(final Object bean, final OutputStream out) {
        try {
            JSON.writeJSONString(out, CharsetUtil.UTF_8, bean);
            out.flush();
        } catch (final IOException e) {
            LOG.warn("exception when serialize {} to json, detail: {}",
                    bean, ExceptionUtils.exception2detail(e));
        }
    }

    public static void serializeToJsonWithEncodeAware(final Object bean, final OutputStream out, final EncodeAware encodeAware) {
        try {
            JSON.writeJSONString(out, CharsetUtil.UTF_8, bean,
                    SerializeConfig.globalInstance,
                    new SerializeFilter[]{new PropertyFilter() {
                        @Override
                        public boolean apply(final Object object, final String name, final Object value) {
                            try {
                                encodeAware.onPropertyEncode(object, name, value);
                            } catch (final Exception e) {
                                LOG.warn("exception when call {}.onPropertyEncode, detail:{}",
                                        encodeAware, ExceptionUtils.exception2detail(e));
                            }
                            return false;
                        }}},
                    null,
                    JSON.DEFAULT_GENERATE_FEATURE);
            out.flush();
        } catch (final IOException e) {
            LOG.warn("exception when serialize {} to json, detail: {}",
                    bean, ExceptionUtils.exception2detail(e));
        }
    }

    private static final Feature F_SSL;
    static {
        F_SSL = defaultSslFeature();
    }

    private static Feature defaultSslFeature() {
        try {
            return new Feature.ENABLE_SSL(SslContextBuilder.forClient().build());
        } catch (final Exception e) {
            LOG.error("exception init default ssl feature, detail: {}", ExceptionUtils.exception2detail(e));
            return null;
        }
    }

    public static <RESP> Transformer<Interaction, RESP> responseAs(final Class<RESP> resptype,
            final Func2<InputStream, Class<RESP>, RESP> decoder) {
        return new Transformer<Interaction, RESP>() {
            @Override
            public Observable<RESP> call(final Observable<Interaction> obsinteraction) {
                return obsinteraction.flatMap(new Func1<Interaction, Observable<RESP>>() {
                    @Override
                    public Observable<RESP> call(final Interaction interaction) {
                        return interaction.execute()
                                .flatMap(new Func1<FullMessage<HttpResponse>, Observable<DisposableWrapper<? extends ByteBuf>>>() {
                                    @Override
                                    public Observable<DisposableWrapper<? extends ByteBuf>> call(final FullMessage<HttpResponse> fullresp) {
                                        return fullresp.body().flatMap(new Func1<MessageBody, Observable<DisposableWrapper<? extends ByteBuf>>>() {
                                            @Override
                                            public Observable<DisposableWrapper<? extends ByteBuf>> call(final MessageBody body) {
                                                return body.content().compose(AUTOSTEP2DWB);
                                            }});
                                    }})
                                .doOnUnsubscribe(interaction.initiator().closer())
                                .toList()
                                .map(new Func1<List<DisposableWrapper<? extends ByteBuf>>, RESP>() {
                                    @Override
                                    public RESP call(final List<DisposableWrapper<? extends ByteBuf>> dwbs) {
                                        final ByteBuf content = Nettys.dwbs2buf(dwbs);
                                        try {
                                            return decoder.call(contentAsInputStream(content), resptype);
                                        } finally {
                                            content.release();
                                        }
                                    }
                                });
                    }
                });
            }
        };
    }

//    private static final Func1<DisposableWrapper<? extends FullHttpMessage>, String> _FULLMSG_TO_STRING = new Func1<DisposableWrapper<? extends FullHttpMessage>, String>() {
//        @Override
//        public String call(final DisposableWrapper<? extends FullHttpMessage> dwfullmsg) {
//            try {
//                return parseContentAsString(contentAsInputStream(dwfullmsg.unwrap().content()));
//            } finally {
//                dwfullmsg.dispose();
//            }
//        }
//    };

    private static final Func1<Interaction, Observable<String>> _INTERACTION_TO_OBS_STRING = new Func1<Interaction, Observable<String>>() {
        @Override
        public Observable<String> call(final Interaction interaction) {
            return interaction.execute()
                    .flatMap(new Func1<FullMessage<HttpResponse>, Observable<DisposableWrapper<? extends ByteBuf>>>() {
                        @Override
                        public Observable<DisposableWrapper<? extends ByteBuf>> call(final FullMessage<HttpResponse> fullresp) {
                            return fullresp.body().flatMap(new Func1<MessageBody, Observable<DisposableWrapper<? extends ByteBuf>>>() {
                                @Override
                                public Observable<DisposableWrapper<? extends ByteBuf>> call(final MessageBody body) {
                                    return body.content().compose(AUTOSTEP2DWB);
                                }});
                        }})
                    .doOnUnsubscribe(interaction.initiator().closer())
                    .toList()
                    .map(new Func1<List<DisposableWrapper<? extends ByteBuf>>, String>() {
                        @Override
                        public String call(final List<DisposableWrapper<? extends ByteBuf>> dwbs) {
                            final ByteBuf content = Nettys.dwbs2buf(dwbs);
                            try {
                                return parseContentAsString(contentAsInputStream(content));
                            } finally {
                                content.release();
                            }
                        }
                    });
        }
    };

    private static final Transformer<Interaction, String> _AS_STRING = new Transformer<Interaction, String>() {
        @Override
        public Observable<String> call(final Observable<Interaction> obsinteraction) {
            return obsinteraction.flatMap(_INTERACTION_TO_OBS_STRING);
        }
    };

    public static Transformer<Interaction, String> responseAsString() {
        return _AS_STRING;
    }

    public static Interact interact(final HttpClient client) {
        final InitiatorBuilder _initiatorBuilder = client.initiator();
        final AtomicBoolean _isSSLEnabled = new AtomicBoolean(false);
        final AtomicReference<Observable<Object>> _obsreqRef = new AtomicReference<>(
                fullRequestWithoutBody(HttpVersion.HTTP_1_1, HttpMethod.GET));
        final List<String> _nvs = new ArrayList<>();
        final AtomicReference<URI> _uriRef = new AtomicReference<>();

        return new Interact() {
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
                    for (final Object bean : reqbeans) {
                        try {
                            final Path path = bean.getClass().getAnnotation(Path.class);
                            if (null != path) {
                                final URI uri = new URI(path.value());
                                if (null != uri.getHost()) {
                                    uri(path.value());
                                    return;
                                }
                            }
                        } catch (final Exception e) {
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
            public Interact method(final HttpMethod method) {
                updateObsRequest(MessageUtil.setMethod(method));
                return this;
            }

            @Override
            public Interact uri(final String uriAsString) {
                try {
                    final URI uri = new URI(uriAsString);
                    _uriRef.set(uri);
                    _initiatorBuilder.remoteAddress(new Func0<SocketAddress>() {
                        @Override
                        public SocketAddress call() {
                            return uri2addr(uri);
                        }
                    });
                    updateObsRequest(MessageUtil.setHost(uri));
                } catch (final URISyntaxException e) {
                    throw new RuntimeException(e);
                }
                return this;
            }

            @Override
            public Interact path(final String path) {
                updateObsRequest(MessageUtil.setPath(path));
                return this;
            }

            @Override
            public Interact paramAsQuery(final String name, final String value) {
                _nvs.add(name);
                _nvs.add(value);
                return this;
            }

            @Override
            public Interact reqbean(final Object... reqbeans) {
                updateObsRequest(MessageUtil.toRequest(reqbeans));
                extractUriWithHost(reqbeans);
                return this;
            }

            @Override
            public Interact body(final Observable<? extends MessageBody> body) {
                _obsreqRef.set(_obsreqRef.get().compose(addBody(body)));
                return this;
            }

            @Override
            public Interact body(final Object bean, final ContentEncoder contentEncoder) {
                _obsreqRef.set(_obsreqRef.get().compose(
                        addBodyWithContentLength(toBody(bean, contentEncoder.contentType(), contentEncoder.encoder()))));
                return this;
            }

//            @Override
//            public Interact disposeBodyOnTerminate(final boolean doDispose) {
//                _doDisposeBody.set(doDispose);
//                return this;
//            }

            @Override
            public Interact onrequest(final Action1<Object> action) {
                updateObsRequest(action);
                return this;
            }

            @Override
            public Interact feature(final Feature... features) {
                _initiatorBuilder.feature(features);
                if (isSSLEnabled(features)) {
                    _isSSLEnabled.set(true);
                }
                return this;
            }

            private boolean isSSLEnabled(final Feature... features) {
                for (final Feature f : features) {
                    if (f instanceof Feature.ENABLE_SSL) {
                        return true;
                    }
                }
                return false;
            }

            private Observable<? extends Object> hookDisposeBody(final Observable<Object> obsreq, final HttpInitiator initiator) {
                return obsreq.map(new Func1<Object, Object>() {
                    @SuppressWarnings("unchecked")
                    @Override
                    public Object call(final Object obj) {
                        if (obj instanceof Stepable) {
                            final Stepable<Object> org = (Stepable<Object>)obj;
                            return new Stepable<Object>() {
                                @Override
                                public String toString() {
                                    return new StringBuilder("[hookDisposeBody for").append(org).append("]").toString();
                                }
                                @Override
                                public void step() {
                                    org.step();
                                }
                                @Override
                                public Object element() {
                                    final Object element = org.element();
                                    if (element instanceof Observable) {
                                        return ((Observable<Object>)element).doOnNext(DisposableWrapperUtil.disposeOnForAny(initiator));
                                    } else if (element instanceof DisposableWrapper) {
                                        return DisposableWrapperUtil.disposeOn(initiator, (DisposableWrapper<?>)element);
                                    } else {
                                        return element;
                                    }
                                }};
                        } else if (obj instanceof DisposableWrapper) {
                            return DisposableWrapperUtil.disposeOn(initiator, (DisposableWrapper<?>)obj);
                        } else {
                            // neither Stepable nor DisposableWrapper
                            return obj;
                        }
                    }});

            }

            private Observable<FullMessage<HttpResponse>> defineInteraction(final HttpInitiator initiator) {
                return initiator.defineInteraction(hookDisposeBody(_obsreqRef.get(), initiator));
            }

            @Override
            public Observable<? extends Interaction> execution() {
                checkAddr();
                addQueryParams();
                return addSSLFeatureIfNeed(_initiatorBuilder).build()
                        .map(new Func1<HttpInitiator, Interaction>() {
                            @Override
                            public Interaction call(final HttpInitiator initiator) {
                                final Observable<FullMessage<HttpResponse>> interaction = defineInteraction(initiator);
                                return new Interaction() {
                                    @Override
                                    public HttpInitiator initiator() {
                                        return initiator;
                                    }

                                    @Override
                                    public Observable<? extends FullMessage<HttpResponse>> execute() {
                                        return interaction;
                                    }};
                            }
                        });
            }
        };
    }

    public static SocketAddress uri2addr(final URI uri) {
        final int port = -1 == uri.getPort() ? ( "https".equals(uri.getScheme()) ? 443 : 80 ) : uri.getPort();
        return new InetSocketAddress(uri.getHost(), port);
    }

    public static Func0<SocketAddress> bean2addr(final Object bean) {
        final Path path = bean.getClass().getAnnotation(Path.class);
        if (null!=path) {
            return new Func0<SocketAddress>() {
                @Override
                public SocketAddress call() {
                    try {
                        return uri2addr(new URI(path.value()));
                    } catch (final Exception e) {
                        throw new RuntimeException(e);
                    }
                }};
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
                            idx+=2;
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
                    for (final Object bean : beans) {
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
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void addHeaderParams(final HttpRequest request, final Object bean) {
        final Field[] headerFields = ReflectUtils.getAnnotationFieldsOf(bean.getClass(), HeaderParam.class);
        if ( headerFields.length > 0 ) {
            for ( final Field field : headerFields ) {
                try {
                    final Object value = field.get(bean);
                    if ( null != value ) {
                        final String headername = field.getAnnotation(HeaderParam.class).value();
                        request.headers().set(headername, value);
                    }
                } catch (final Exception e) {
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
            for (final Field field : queryFields) {
                try {
                    final Object value = field.get(bean);
                    if ( null != value ) {
                        final String paramkey = field.getAnnotation(QueryParam.class).value();
                        encoder.addParam(paramkey, String.valueOf(value));
                    }
                }
                catch (final Exception e) {
                    LOG.warn("exception when get field({})'s value, detail:{}",
                            field, ExceptionUtils.exception2detail(e));
                }
            }

            request.setUri(encoder.toString());
        }
    }

    // TODO: support multipart/...
    public static Transformer<Object, Object> addBody(final Observable<? extends MessageBody> body) {
        return new Transformer<Object, Object>() {
            @Override
            public Observable<Object> call(final Observable<Object> msg) {
                return msg.concatMap(new Func1<Object, Observable<Object>>() {
                    @Override
                    public Observable<Object> call(final Object obj) {
                        if (obj instanceof HttpMessage) {
                            final HttpMessage httpmsg = (HttpMessage)obj;
                            return body.flatMap(new Func1<MessageBody, Observable<Object>>() {
                                @Override
                                public Observable<Object> call(final MessageBody body) {
                                    httpmsg.headers().set(HttpHeaderNames.CONTENT_TYPE, body.contentType());
                                    // set content-length
                                    if (body.contentLength() > 0) {
                                        httpmsg.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.contentLength());
                                    } else {
                                        HttpUtil.setTransferEncodingChunked(httpmsg, true);
                                    }
                                    return Observable.concat(Observable.just(httpmsg), body.content());
                                }});
                        } else {
                            return Observable.just(obj);
                        }
                    }});
            }
        };
    }

    private static Transformer<Object, Object> addBodyWithContentLength(final Observable<? extends MessageBody> obsbody) {
        return new Transformer<Object, Object>() {
            @Override
            public Observable<Object> call(final Observable<Object> msg) {
                return msg.concatMap(new Func1<Object, Observable<Object>>() {
                    @Override
                    public Observable<Object> call(final Object obj) {
                        if (obj instanceof HttpMessage) {
                            final HttpMessage httpmsg = (HttpMessage)obj;
                            return obsbody.flatMap(new Func1<MessageBody, Observable<Object>>() {
                                @Override
                                public Observable<Object> call(final MessageBody body) {
                                    return body.content().compose(AUTOSTEP2DWB).toList().flatMap(new Func1<List<DisposableWrapper<? extends ByteBuf>>, Observable<Object>>() {
                                        @Override
                                        public Observable<Object> call(final List<DisposableWrapper<? extends ByteBuf>> dwbs) {
                                            int length = 0;
                                            for (final DisposableWrapper<? extends ByteBuf> dwb : dwbs) {
                                                length +=dwb.unwrap().readableBytes();
                                            }
                                            httpmsg.headers().set(HttpHeaderNames.CONTENT_TYPE, body.contentType());
                                            // set content-length
                                            httpmsg.headers().set(HttpHeaderNames.CONTENT_LENGTH, length);
                                            return Observable.concat(Observable.just(httpmsg), Observable.from(dwbs));
                                        }});
                                }});
                        } else {
                            return Observable.just(obj);
                        }
                    }});
            }
        };
    }

    public static Observable<? extends MessageBody> toBody(
            final Object bean,
            final String contentType,
            final Action2<Object, OutputStream> encoder) {
        return Observable.just(new MessageBody() {

            @Override
            public String contentType() {
                return contentType;
            }
            @Override
            public int contentLength() {
                return -1;
            }
            @Override
            public Observable<? extends ByteBufSlice> content() {
                return bean2bbs(bean, encoder);
            }});
    }

    final private static Func1<DisposableWrapper<ByteBuf>, ByteBuf> _UNWRAP_DWB = new Func1<DisposableWrapper<ByteBuf>, ByteBuf>() {
        @Override
        public ByteBuf call(final DisposableWrapper<ByteBuf> dwb) {
            return dwb.unwrap();
        }};

    private static Observable<ByteBufSlice> bean2bbs(final Object bean, final Action2<Object, OutputStream> encoder) {
        return Observable.<ByteBufSlice>just(new ByteBufSlice() {

            @Override
            public void step() {}

            @Override
            public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return out2dwbs(new BufsOutputStream<>(pooledAllocator(null, 8192), _UNWRAP_DWB),
                        new Action1<OutputStream>() {
                            @Override
                            public void call(final OutputStream out) {
                                encoder.call(bean, out);
                            }
                        });
            }});
    }

    public static Observable<Object> fullRequestWithoutBody(final HttpVersion version, final HttpMethod method) {
        return Observable.<Object>just(new DefaultHttpRequest(version, method, ""), LastHttpContent.EMPTY_LAST_CONTENT);
    }

    public static Observable<Object> fullRequest(final Object... beans) {
        return fullRequestWithoutBody(HttpVersion.HTTP_1_1, HttpMethod.GET).doOnNext(MessageUtil.toRequest(beans));
    }

    public static <T> Observable<? extends T> decodeAs(final MessageBody body, final Class<T> type) {
        if (null != body.contentType()) {
            if (body.contentType().startsWith(HttpHeaderValues.APPLICATION_JSON.toString())) {
                return decodeJsonAs(body, type);
            } else if (body.contentType().startsWith("application/xml") || body.contentType().startsWith("text/xml")) {
                return decodeXmlAs(body, type);
            }
        }
        try {
            LOG.warn("contentType is {}, can't decode from body, just return empty {} instance", body.contentType(), type);
            return Observable.just(type.newInstance());
        } catch (final Exception e) {
            return Observable.error(e);
        }
    }

    public static <T> Observable<? extends T> decodeJsonAs(final MessageBody body, final Class<T> type) {
        return decodeContentAs(body.content(), new Func2<InputStream, Class<T>, T>() {
            @Override
            public T call(final InputStream is, final Class<T> clazz) {
                return unserializeAsJson(is, clazz);
            }
        }, type);
    }

    public static <T> Observable<? extends T> decodeXmlAs(final MessageBody body, final Class<T> type) {
        return decodeContentAs(body.content(), new Func2<InputStream, Class<T>, T>() {
            @Override
            public T call(final InputStream is, final Class<T> clazz) {
                return unserializeAsXml(is, clazz);
            }
        }, type);
    }

    public static <T> Observable<? extends T> decodeContentAs(
            final Observable<? extends ByteBufSlice> content, final Func2<InputStream, Class<T>, T> func,
            final Class<T> type) {
        return content.compose(AUTOSTEP2DWB)
                .map(DisposableWrapperUtil.<ByteBuf>unwrap()).toList().map(new Func1<List<ByteBuf>, T>() {
            @Override
            public T call(final List<ByteBuf> bufs) {
                final ByteBuf buf = Nettys.composite(bufs);
                try {
                    return func.call(contentAsInputStream(buf), type);
                } finally {
                    ReferenceCountUtil.release(buf);
                }
            }
        });
    }

    public static <T> Iterable<T> out2dwbs(final BufsOutputStream<T> bufout, final Action1<OutputStream> fillout) {
        final List<T> bufs = new ArrayList<>();
        bufout.setOutput(new Action1<T>() {
            @Override
            public void call(final T buf) {
                bufs.add(buf);
            }});

        try {
            fillout.call(bufout);
            bufout.flush();
            return bufs;
        } catch (final Exception e) {
            throw new RuntimeException(e);
        } finally {
            bufout.setOutput(null);
        }
    }
}
