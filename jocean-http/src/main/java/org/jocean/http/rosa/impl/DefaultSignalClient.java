package org.jocean.http.rosa.impl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jocean.http.Feature;
import org.jocean.http.Feature.FeaturesAware;
import org.jocean.http.PayloadCounter;
import org.jocean.http.client.HttpClient;
import org.jocean.http.client.Outbound;
import org.jocean.http.rosa.SignalClient;
import org.jocean.http.rosa.impl.internal.Facades.ResponseBodyTypeSource;
import org.jocean.http.rosa.impl.internal.Facades.ResponseTypeSource;
import org.jocean.http.rosa.impl.internal.Facades.UriSource;
import org.jocean.http.rosa.impl.internal.RosaProfiles;
import org.jocean.http.util.FeaturesBuilder;
import org.jocean.http.util.PayloadCounterAware;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.BeanHolderAware;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.rx.DoOnUnsubscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
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
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

public class DefaultSignalClient implements SignalClient, BeanHolderAware {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultSignalClient.class);
    
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
    
    @Override
    public <RESP> Observable<RESP> rawDefineInteraction(final Feature... features) {
        return defineInteraction(null, features);
    }
    
    @Override
    public <RESP> Observable<RESP> defineInteraction(
            final Object signalBean, 
            final Feature... features) {
        final Feature[] fullfeatures = Feature.Util.union(RosaProfiles._DEFAULT_PROFILE, features);
        return Observable.create(new OnSubscribe<RESP>() {
            @Override
            public void call(final Subscriber<? super RESP> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    final URI uri = req2uri(signalBean, fullfeatures);
                    _httpClient.defineInteraction(
                            safeGetAddress(signalBean, uri), 
                            requestProviderOf(signalBean, 
                                initRequestOf(uri), 
                                fullfeatures),
                            genFeatures4HttpClient(signalBean, fullfeatures))
                    .compose(new ToSignalResponse<RESP>(
                            safeGetResponseType(fullfeatures),
                            safeGetResponseBodyType(signalBean, fullfeatures)))
                    .subscribe(subscriber);
                }
            }
        });
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

    private Func1<DoOnUnsubscribe, Observable<? extends Object>> requestProviderOf(
            final Object signalBean, 
            final HttpRequest request, 
            final Feature[] features) {
        return new Func1<DoOnUnsubscribe, Observable<? extends Object>>() {
            @Override
            public Observable<? extends Object> call(final DoOnUnsubscribe doOnUnsubscribe) {
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
                    doOnUnsubscribe.call(outgoing.toRelease());
                    hookPayloadCounter(outgoing.requestSizeInBytes(), features);
                    return outgoing.request();
                } catch (Exception e) {
                    return Observable.error(e);
                } finally {
                    ReferenceCountUtil.release(body);
                }
            }};
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
        final Subscription _torelease;
        
        Outgoing(final Observable<Object> request, final long size, final Subscription torelease) {
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
        
        Subscription toRelease() {
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
                    Subscriptions.create(new Action0() {
                                @Override
                                public void call() {
                                    ReferenceCountUtil.release(request);
                                    ReferenceCountUtil.release(lastContent);
                                }}));
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

            final Subscription toRelease = Subscriptions.create(
                    new Action0() {
                        @Override
                        public void call() {
                            ReferenceCountUtil.release(request4send);
                            RxNettys.releaseObjects(postRequestEncoder.getBodyListAttributes());
                        }});
         
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
    
    private BeanHolder _beanHolder;
    private final HttpClient _httpClient;
    private final AttachmentBuilder _attachmentBuilder;
    private final URI _defaultUri;
    private final Func1<URI, SocketAddress> _defaultBuildAddress;
    
    private final static HttpDataFactory _DATA_FACTORY = new DefaultHttpDataFactory(false);
}
