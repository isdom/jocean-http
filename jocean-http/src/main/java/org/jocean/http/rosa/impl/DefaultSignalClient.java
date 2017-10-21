package org.jocean.http.rosa.impl;

import java.beans.PropertyEditor;
import java.beans.PropertyEditorManager;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.BeanParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.HeaderParam;

import org.jocean.http.Feature;
import org.jocean.http.Feature.FeaturesAware;
import org.jocean.http.PayloadCounter;
import org.jocean.http.TransportException;
import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.client.Outbound;
import org.jocean.http.rosa.SignalClient;
import org.jocean.http.rosa.impl.internal.Facades.ResponseBodyTypeSource;
import org.jocean.http.rosa.impl.internal.Facades.ResponseTypeSource;
import org.jocean.http.rosa.impl.internal.Facades.UriSource;
import org.jocean.http.rosa.impl.internal.RosaProfiles;
import org.jocean.http.util.FeaturesBuilder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.PayloadCounterAware;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.BeanHolderAware;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.ReflectUtils;
import org.jocean.idiom.rx.RxObservables;
import org.jocean.idiom.rx.RxObservables.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpData;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder.EncoderMode;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder.ErrorDataEncoderException;
import io.netty.handler.codec.http.multipart.MemoryFileUpload;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

public class DefaultSignalClient implements SignalClient, BeanHolderAware {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultSignalClient.class);
    
    private static Func1<? super Observable<? extends Throwable>, ? extends Observable<?>> retryPolicy(
            final int maxRetryTimes,
            final long retryIntervalBase) {
        return RxObservables.retryWith(new RetryPolicy<Integer>() {
            @Override
            public Observable<Integer> call(final Observable<Throwable> errors) {
                return errors.compose(RxObservables.retryIfMatch(TransportException.class))
                        .compose(RxObservables.retryMaxTimes(maxRetryTimes))
                        .compose(RxObservables.retryDelayTo(retryIntervalBase))
                        ;
            }});
    }
    private final static Func1<? super Observable<? extends Throwable>, ? extends Observable<?>> _RETRY
             = retryPolicy(3, 100);
    
    private static final Func1<URI, SocketAddress> _DEFAULT_URI2ADDR = new Func1<URI, SocketAddress>() {
        @Override
        public SocketAddress call(final URI uri) {
            final int port = -1 == uri.getPort() ? ( "https".equals(uri.getScheme()) ? 443 : 80 ) : uri.getPort();
            return new InetSocketAddress(uri.getHost(), port);
        }
    };
    
    public DefaultSignalClient(final HttpClient httpClient) {
        this(null, _DEFAULT_URI2ADDR, httpClient, new DefaultAttachmentBuilder());
    }
    
    public DefaultSignalClient(final URI defaultUri, final HttpClient httpClient) {
        this(defaultUri, _DEFAULT_URI2ADDR, httpClient, new DefaultAttachmentBuilder());
    }
    
    public DefaultSignalClient(
            final Func1<URI, SocketAddress> defaultBuildAddress,
            final HttpClient httpClient) {
        this(null, defaultBuildAddress, httpClient, new DefaultAttachmentBuilder());
    }
    
    public DefaultSignalClient(final HttpClient httpClient, 
            final AttachmentBuilder attachmentBuilder) {
        this(null, _DEFAULT_URI2ADDR, httpClient, attachmentBuilder);
    }
    
    public DefaultSignalClient(final URI defaultUri, 
            final Func1<URI, SocketAddress> defaultBuildAddress,
            final HttpClient httpClient) {
        this(defaultUri, defaultBuildAddress, httpClient, new DefaultAttachmentBuilder());
    }
    
    public DefaultSignalClient(final URI defaultUri, 
            final HttpClient httpClient, 
            final AttachmentBuilder attachmentBuilder) {
        this(defaultUri, _DEFAULT_URI2ADDR, httpClient, attachmentBuilder);
    }
    
    public DefaultSignalClient(final URI defaultUri, 
            final Func1<URI, SocketAddress> defaultBuildAddress,
            final HttpClient httpClient, 
            final AttachmentBuilder attachmentBuilder) {
        this._defaultUri = defaultUri;
        this._defaultBuildAddress = defaultBuildAddress;
        this._httpClient = httpClient;
        this._attachmentBuilder = attachmentBuilder;
    }
    
    public Action0 registerRequestType(final Class<?> reqType, 
            final Class<?> respType, 
            final URI uri, 
            final Func1<URI, SocketAddress> uri2address,
            final Feature... features) {
        return registerRequestType(reqType, respType, uri, 
                new Func0<Feature[]>() {
                    @Override
                    public Feature[] call() {
                        return features;
                    }},
                uri2address);
    }
    
    public Action0 registerRequestType(final Class<?> reqType, 
            final Class<?> respType, 
            final URI uri, 
            final Feature... features) {
        return registerRequestType(reqType, respType, uri, new Func0<Feature[]>() {
            @Override
            public Feature[] call() {
                return features;
            }});
    }
    
    public Action0 registerRequestType(final Class<?> reqType, 
            final Class<?> respType, 
            final URI uri, 
            final String featuresName) {
        return registerRequestType(reqType, respType, uri, new Func0<Feature[]>() {
            @Override
            public String toString() {
                return "Features Config named(" + featuresName + ")";
            }
            
            @Override
            public Feature[] call() {
                final FeaturesBuilder builder = _beanHolder.getBean(featuresName, FeaturesBuilder.class);
                if (null==builder) {
                    LOG.warn("signal client {} require FeaturesBuilder named({}) not exist! please check application config!",
                            DefaultSignalClient.this, featuresName);
                }
                return null!=builder ? builder.call() : Feature.EMPTY_FEATURES;
            }});
    }
    
    public Action0 registerRequestType(final Class<?> reqType, 
            final Class<?> respType, 
            final URI uri, 
            final Func0<Feature[]> featuresBuilder) {
        return registerRequestType(reqType, respType, uri, featuresBuilder, _DEFAULT_URI2ADDR);
    }

    public Action0 registerRequestType(final Class<?> reqType, 
            final Class<?> respType, 
            final URI uri, 
            final Func0<Feature[]> featuresBuilder,
            final Func1<URI, SocketAddress> uri2address) {
        this._signal2profile.put(reqType, new RequestProfile(respType, uri, featuresBuilder, uri2address));
        LOG.info("register request type {} with resp type {}/uri {}/features builder {}",
                reqType, respType, uri, featuresBuilder);
        return new Action0() {
            @Override
            public void call() {
                _signal2profile.remove(reqType);
                LOG.info("unregister request type {}", reqType);
            }};
    }
    
    public String[] getRegisteredSignal() {
        final List<String> ret = new ArrayList<>();
        for ( Map.Entry<Class<?>, RequestProfile> entry 
                : _signal2profile.entrySet()) {
            final StringBuilder sb = new StringBuilder();
            sb.append(entry.getKey());
            sb.append("-->");
            sb.append(entry.getValue().uri());
            sb.append("/");
            sb.append(entry.getValue().responseType());
            ret.add(sb.toString());
        }
        
        return ret.toArray(new String[0]);
    }
    
    /*
    public String[] getEmittedSignal() {
        final List<String> ret = new ArrayList<>();
        for (Map.Entry<Class<?>, RequestProcessor> entry 
                : this._processorCache.snapshot().entrySet()) {
            final StringBuilder sb = new StringBuilder();
            sb.append(entry.getKey());
            sb.append("-->");
            sb.append(safeUriOf(entry.getKey()));
            sb.append(entry.getValue().pathSuffix());
            sb.append("/");
            sb.append(entry.getValue());
            ret.add(sb.toString());
        }
        
        return ret.toArray(new String[0]);
    }
    */
    
    public InteractionBuilder interaction() {
        final AtomicReference<Object> _request = new AtomicReference<>();
        final List<Feature> _features = new ArrayList<>();
        
        return new InteractionBuilder() {

            @Override
            public InteractionBuilder request(final Object request) {
                _request.set(request);
                return this;
            }

            @Override
            public InteractionBuilder feature(final Feature... features) {
                for (Feature f : features) {
                    if (null != f) {
                        _features.add(f);
                    }
                }
                return this;
            }

            @Override
            public <RESP> Observable<RESP> build() {
                return defineInteraction(_request.get(), 
                        _features.toArray(Feature.EMPTY_FEATURES));
            }};
    }
    
    private <RESP> Observable<RESP> defineInteraction(
            final Object signalBean, 
            final Feature... features) {
        final Feature[] fullfeatures = Feature.Util.union(RosaProfiles._DEFAULT_PROFILE, features);
        final URI uri = req2uri(signalBean, fullfeatures);
        return _httpClient.initiator()
        //  TODO delay using uri
        .remoteAddress(safeGetAddress(signalBean, uri))
        .feature(genFeatures4HttpClient(signalBean, fullfeatures))
        .build()
        .flatMap(new Func1<HttpInitiator, Observable<RESP>>() {
            @Override
            public Observable<RESP> call(final HttpInitiator initiator) {
                return initiator.defineInteraction2(
                    outboundMessageOf(signalBean, 
                            initRequestOf(uri),
                            fullfeatures,
                            initiator.onTerminate()))
                    .compose(RxNettys.message2fullresp(initiator))
                    .map(new Func1<DisposableWrapper<FullHttpResponse>, RESP>() {
                        @Override
                        public RESP call(final DisposableWrapper<FullHttpResponse> dwresp) {
                            try {
                                return toRESP(dwresp.unwrap(),
                                    safeGetResponseType(fullfeatures),
                                    safeGetResponseBodyType(signalBean, fullfeatures));
                            } finally {
                                dwresp.dispose();
                            }
                        }})
                    .doOnUnsubscribe(initiator.closer())
                    ;
            }})
        .retryWhen(_RETRY)
        ;
    }
    
    private Feature[] genFeatures4HttpClient(final Object signalBean,
            final Feature... features) {
        final Feature[] addSignalFeatures = Feature.Util.union( 
            safeGetRequestFeatures(signalBean), 
            features);
        return (hasAttachments(features))
            ? Feature.Util.union(addSignalFeatures, Outbound.ENABLE_MULTIPART)
            : addSignalFeatures;
    }

    private static boolean hasAttachments(final Feature[] features) {
        return filterAttachments(features).length > 0;
    }
    
    private HttpRequest initRequestOf(final URI uri) {
        final HttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, 
                HttpMethod.GET, 
                null != uri.getRawPath() ? uri.getRawPath() : "");
        if (null != uri.getHost()) {
            request.headers().set(HttpHeaderNames.HOST, uri.getHost());
        }
        return request;
    }

    private Observable<? extends Object> outboundMessageOf(
            final Object signalBean, 
            final HttpRequest request, 
            final Feature[] features, 
            final Action1<Action0> onTerminate) {
        //  first, apply to request
        applyRequestPreprocessors(
                signalBean, 
                request, 
                features);
        
        //  second, build body
        final BodyForm body = buildBody(
                signalBean, 
                request, 
                features);
        try {
            final Outgoing outgoing = assembleOutgoing(
                    request, 
                    body,
                    filterAttachments(features));
            onTerminate.call(outgoing.toRelease());
            hookPayloadCounter(outgoing.requestSizeInBytes(), features);
            return outgoing.request();
        } catch (Exception e) {
            return Observable.error(e);
        } finally {
            ReferenceCountUtil.release(body);
        }
    }

    private static Attachment[] filterAttachments(final Feature[] features) {
        final Attachment[] attachments = 
                InterfaceUtils.selectIncludeType(Attachment.class, (Object[])features);
        return null != attachments ? attachments : new Attachment[0];
    }

    private void hookPayloadCounter(final long uploadTotal, final Feature[] features) {
        final PayloadCounterAware payloadCounterAware = 
                InterfaceUtils.compositeIncludeType(PayloadCounterAware.class, (Object[])features);
            
        if (null!=payloadCounterAware) {
            try {
                payloadCounterAware.setPayloadCounter(buildPayloadCounter(uploadTotal));
            } catch (Exception e) {
                LOG.warn("exception when invoke setPayloadCounter, detail: {}",
                        ExceptionUtils.exception2detail(e));
            }
        }
    }

    private PayloadCounter buildPayloadCounter(final long uploadTotal) {
        return new PayloadCounter() {
            @Override
            public long totalUploadBytes() {
                return uploadTotal;
            }
            @Override
            public long totalDownloadBytes() {
                return -1;
            }};
    }
    
    final static class Outgoing {
        
        final Observable<Object> _requestObservable;
        final long _requestSizeInBytes;
        final Action0 _torelease;
        
        Outgoing(final Observable<Object> request, final long size, final Action0 torelease) {
            this._requestObservable = request;
            this._requestSizeInBytes = size;
            this._torelease = torelease;
        }
        
        Observable<Object> request() {
            return this._requestObservable;
        }
        
        long requestSizeInBytes() {
            return this._requestSizeInBytes;
        }
        
        Action0 toRelease() {
            return this._torelease;
        }
    }
    
    private Outgoing assembleOutgoing(
            final HttpRequest request, 
            final BodyForm body,
            final Attachment[] attachments) throws Exception {
        if (0 == attachments.length) {
            final LastHttpContent lastContent = buildLastContent(request, body);
            return new Outgoing(Observable.<Object>just(request, lastContent), 
                    lastContent.content().readableBytes(), 
                    new Action0() {
                        @Override
                        public void call() {
                            ReferenceCountUtil.release(request);
                            ReferenceCountUtil.release(lastContent);
                        }});
        } else {
            // Use the PostBody encoder
            final HttpPostRequestEncoder postRequestEncoder =
                    new HttpPostRequestEncoder(_DATA_FACTORY, request, true,
                            CharsetUtil.UTF_8, EncoderMode.HTML5); // true => multipart
            
            final long signalSize = addSignalToMultipart(postRequestEncoder, body);
            final long attachmentSize = addAttachmentsToMultipart(postRequestEncoder, attachments);
            final long total = signalSize + attachmentSize;
            
            // finalize request
            final HttpRequest request4send = postRequestEncoder.finalizeRequest();

            final Action0 toRelease = new Action0() {
                @Override
                public void call() {
                    ReferenceCountUtil.release(request4send);
                    RxNettys.releaseObjects(postRequestEncoder.getBodyListAttributes());
                }};
         
            return postRequestEncoder.isChunked() 
                ? new Outgoing(Observable.<Object>just(request4send, postRequestEncoder), 
                        total, toRelease)
                : new Outgoing(Observable.<Object>just(request4send), total, toRelease);
        }
    }

    private long addSignalToMultipart(
            final HttpPostRequestEncoder postRequestEncoder,
            final BodyForm body)
            throws IOException, ErrorDataEncoderException {
        if (null != body) {
            final MemoryFileUpload signalPayload = 
                    new MemoryFileUpload("__signal", "__signal", body.contentType(), 
                            null, null, body.length()) {
                        @Override
                        public Charset getCharset() {
                            return null;
                        }
            };
            
            signalPayload.setContent(body.content().retain());
                
            postRequestEncoder.addBodyHttpData(signalPayload);
            return body.length();
        } else {
            //  TODO ?
            return 0;
        }
    }

    private long addAttachmentsToMultipart(
            final HttpPostRequestEncoder postRequestEncoder,
            final Attachment[] attachments) {
        long size = 0;
        for (Attachment attachment : attachments) {
            try {
                final HttpData httpData = this._attachmentBuilder.call(attachment);
                size += httpData.length();
                postRequestEncoder.addBodyHttpData(httpData);
            } catch (Exception e) {
                LOG.warn("exception when invoke AttachmentBuilder({}).call, detail: {}",
                        this._attachmentBuilder, ExceptionUtils.exception2detail(e));
            }
        }
        return size;
    }

    private HttpRequest applyRequestPreprocessors(final Object signalBean,
            final HttpRequest request, 
            final Feature[] features) {
        final RequestPreprocessor[] requestPreprocessors = 
                RequestPreprocessor.Util.filter(features);
        if (null != requestPreprocessors) {
            //  TODO: refactor by RxJava's stream api
            final List<RequestChanger> changers = new ArrayList<>();
            for (RequestPreprocessor preprocessor : requestPreprocessors) {
                try {
                    final RequestChanger changer = preprocessor.call(signalBean);
                    if (null != changer) {
                        changers.add(changer);
                    }
                } catch (Exception e) {
                    LOG.warn("exception when RequestPreprocessor({}).call, detail: {}",
                            preprocessor, ExceptionUtils.exception2detail(e));
                }
            }
            if (!changers.isEmpty()) {
                Collections.sort(changers, Ordered.ASC);
                for (RequestChanger changer : changers) {
                    try {
                        if (changer instanceof FeaturesAware) {
                            ((FeaturesAware)changer).setFeatures(features);
                        }
                        changer.call(request);
                    } catch (Exception e) {
                        LOG.warn("exception when RequestChanger({}).call, detail: {}",
                                changer, ExceptionUtils.exception2detail(e));
                    }
                }
            }
        }
        return request;
    }

    private BodyForm buildBody(final Object signalBean, 
            final HttpRequest request,
            final Feature[] features) {
        final BodyPreprocessor[] bodyPreprocessors = 
            BodyPreprocessor.Util.filter(features);
        if (null != bodyPreprocessors) {
            final List<BodyBuilder> builders = new ArrayList<>();
            for (BodyPreprocessor bodyPreprocessor : bodyPreprocessors) {
                try {
                    final BodyBuilder builder = bodyPreprocessor.call(signalBean, request);
                    if (null != builder) {
                        builders.add(builder);
                    }
                } catch (Exception e) {
                    LOG.warn("exception when BodyPreprocessor({}).call, detail: {}",
                            bodyPreprocessor, ExceptionUtils.exception2detail(e));
                }
            }
            if (!builders.isEmpty()) {
                Collections.sort(builders, Ordered.ASC);
                for (BodyBuilder builder : builders) {
                    try {
                        if (builder instanceof FeaturesAware) {
                            ((FeaturesAware)builder).setFeatures(features);
                        }
                        final BodyForm body = builder.call();
                        if (null != body) {
                            return body;
                        }
                    } catch (Exception e) {
                        LOG.warn("exception when BodyBuilder({}).call, detail: {}",
                                builder, ExceptionUtils.exception2detail(e));
                    }
                }
            }
        }
        return null;
    }

    private LastHttpContent buildLastContent(
            final HttpRequest request,
            final BodyForm body) {
        if (null != body) {
            if (null == request.headers().get(HttpHeaderNames.CONTENT_LENGTH)) {
                //  Content-Length not set
                HttpUtil.setContentLength(request, body.length());
            }
            if (null == request.headers().get(HttpHeaderNames.CONTENT_TYPE)) {
                //  Content-Type not set
                request.headers().set(HttpHeaderNames.CONTENT_TYPE, body.contentType());
            }
            return new DefaultLastHttpContent(body.content().retain());
        } else {
            return LastHttpContent.EMPTY_LAST_CONTENT;
        }
    }
    
    private final Map<Class<?>, RequestProfile> _signal2profile = 
            new ConcurrentHashMap<>();
    
    private URI req2uri(final Object signalBean, final Feature[] features) {
        final UriSource[] uris = InterfaceUtils.selectIncludeType(
                UriSource.class, (Object[])features);
        if ( null!=uris && uris.length > 0) {
            return uris[0].uri();
        } else {
            return safeUriOf(signalBean);
        }
    }

    private RequestProfile signal2Profile(final Object signalBean) {
        return null != signalBean 
                ? this._signal2profile.get(signalBean.getClass()) 
                : null;
    }

    private SocketAddress safeGetAddress(final Object signalBean, final URI uri) {
        final RequestProfile profile = signal2Profile(signalBean);
        return (null != profile ? profile.buildAddress(uri) : this._defaultBuildAddress.call(uri));
    }

    private URI safeUriOf(final Object signalBean) {
        final RequestProfile profile = signal2Profile(signalBean);
        final URI signalUri = (null != profile ? profile.uri() : null);
        return null != signalUri ? signalUri : this._defaultUri;
    }
    
    private Feature[] safeGetRequestFeatures(final Object signalBean) {
        final RequestProfile profile = signal2Profile(signalBean);
        return (null != profile ? profile.features() : Feature.EMPTY_FEATURES);
    }
    
    private Class<?> safeGetResponseType(final Feature[] features) {
        final ResponseTypeSource[] respTypeSource = 
                InterfaceUtils.selectIncludeType(ResponseTypeSource.class, (Object[])features);
        return (null != respTypeSource && respTypeSource.length > 0) 
                ? respTypeSource[0].responseType()
                : null
                ;
    }

    private Class<?> safeGetResponseBodyType(final Object signalBean, final Feature[] features) {
        final ResponseBodyTypeSource[] respBodyTypeSource = 
                InterfaceUtils.selectIncludeType(ResponseBodyTypeSource.class, (Object[])features);
        if (null != respBodyTypeSource && respBodyTypeSource.length > 0) {
            return respBodyTypeSource[0].responseBodyType();
        } else {
            final RequestProfile profile = signal2Profile(signalBean);
            return (null != profile ? profile.responseType() : null);
        }
    }

    @Override
    public void setBeanHolder(final BeanHolder beanHolder) {
        this._beanHolder = beanHolder;
    }
    
    @SuppressWarnings("unchecked")
    private <RESP> RESP toRESP(
            final FullHttpResponse fullresp,
            final Class<?> respType,
            final Class<?> bodyType
            ) {
        if (null!=fullresp) {
            try {
                final byte[] bytes = Nettys.dumpByteBufAsBytes(fullresp.content());
                if (LOG.isTraceEnabled()) {
                    try {
                        LOG.trace("receive signal response: {}",
                                new String(bytes, CharsetUtil.UTF_8));
                    } catch (Exception e) 
                    { // decode bytes as UTF-8 error, just ignore 
                    }
                }
                if (null != respType) {
                    return DefaultSignalClient.<RESP>convertResponseTo(fullresp, bytes, respType);
                }
                if (null != bodyType) {
                    if (bodyType.equals(String.class)) {
                        return (RESP)new String(bytes, CharsetUtil.UTF_8);
                    } else {
                        return JSON.<RESP>parseObject(bytes, bodyType);
                    }
                } else {
                    final RESP respObj = (RESP)bytes;
                    return respObj;
                }
            } catch (Exception e) {
                LOG.warn("exception when parse response {}, detail:{}",
                        fullresp, ExceptionUtils.exception2detail(e));
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("invalid response");
    }

    private static <RESP> RESP convertResponseTo(
            final FullHttpResponse fullresp, 
            final byte[] bodyBytes,
            final Class<?> respType) {
        try {
            @SuppressWarnings("unchecked")
            final RESP respBean = (RESP)respType.newInstance();
            
            assignAllParams(respBean, fullresp, bodyBytes);
            
            return respBean;
        } catch (Exception e) {
            return null;
        }
    }

    private static void assignAllParams(
            final Object bean,
            final HttpMessage httpmsg, 
            final byte[] bodyBytes) {
        assignHeaderParams(bean, httpmsg);
        assignBeanParams(bean, httpmsg, bodyBytes);
    }

    private static void assignBeanParams(
            final Object bean,
            final HttpMessage httpmsg, 
            final byte[] bodyBytes) {
        final String contentType = httpmsg.headers().get(HttpHeaderNames.CONTENT_TYPE);
        final Field[] beanfields = 
                ReflectUtils.getAnnotationFieldsOf(bean.getClass(), BeanParam.class);
        if (null != beanfields && beanfields.length > 0) {
            for (Field field : beanfields) {
                try {
                    final Object value = createBeanValue(bodyBytes, contentType, field);
                    if (null != value) {
                        field.set(bean, value);
                        assignAllParams(value, httpmsg, bodyBytes);
                    }
                } catch (Exception e) {
                    LOG.warn("exception when set bean value for field({}), detail:{}",
                            field.getName(), ExceptionUtils.exception2detail(e));
                }
            }
        }
    }

    private static Object createBeanValue(final byte[] bodyBytes, final String contentType, final Field beanField) {
        if ( !isMatchMediatype(contentType, beanField) ) {
            return null;
        }
        if (null != bodyBytes) {
            if (beanField.getType().equals(byte[].class)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("assign byte array with: {}", new String(bodyBytes, CharsetUtil.UTF_8));
                }
                return bodyBytes;
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("createBeanValue: {}", new String(bodyBytes, CharsetUtil.UTF_8));
                }
                return JSON.parseObject(bodyBytes, beanField.getType());
            }
        }
        try {
            return beanField.getType().newInstance();
        } catch (Throwable e) {
            LOG.warn("exception when create instance for type:{}, detail:{}",
                    beanField.getType(), ExceptionUtils.exception2detail(e));
            return null;
        }
    }

    private static boolean isMatchMediatype(final String contentType,
            final Field beanField) {
        if (null == contentType) {
            return true;
        }
        final Consumes consumes = beanField.getType().getAnnotation(Consumes.class);
        if (null == consumes) {
            return true;
        }
        for (String mediaType : consumes.value()) {
            if ( "*/*".equals(mediaType)) {
                return true;
            }
            if (contentType.startsWith(mediaType)) {
                return true;
            }
        }
        return false;
    }
    
    private static void assignHeaderParams(final Object bean, final HttpMessage httpmsg) {
        final Field[] headerfields = 
                ReflectUtils.getAnnotationFieldsOf(bean.getClass(), HeaderParam.class);
        if (null != headerfields && headerfields.length > 0) {
            for (Field field : headerfields) {
                injectValuesToField(
                    httpmsg.headers().getAll(field.getAnnotation(HeaderParam.class).value()), 
                    bean,
                    field);
            }
        }
    }

    private static void injectValuesToField(
            final List<String> values,
            final Object obj,
            final Field field) {
        //  TODO, if field is Collection or Array type, then assign all values to field
        if (null != values && values.size() > 0) {
            injectValueToField(values.get(0), obj, field);
        }
    }

    private static void injectValueToField(
            final String value,
            final Object obj,
            final Field field) {
        if (null != value) {
            try {
                // just check String field
                if (field.getType().equals(String.class)) {
                    field.set(obj, value);
                } else {
                    final PropertyEditor editor = PropertyEditorManager.findEditor(field.getType());
                    if (null != editor) {
                        editor.setAsText(value);
                        field.set(obj, editor.getValue());
                    }
                }
            } catch (Exception e) {
                LOG.warn("exception when set obj({}).{} with value({}), detail:{} ",
                        obj, field.getName(), value, ExceptionUtils.exception2detail(e));
            }
        }
    }
    
    private BeanHolder _beanHolder;
    private final HttpClient _httpClient;
    private final AttachmentBuilder _attachmentBuilder;
    private final URI _defaultUri;
    private final Func1<URI, SocketAddress> _defaultBuildAddress;
    
    private final static HttpDataFactory _DATA_FACTORY = new DefaultHttpDataFactory(false);
}
