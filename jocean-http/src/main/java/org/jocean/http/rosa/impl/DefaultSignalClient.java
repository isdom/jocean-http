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
import org.jocean.http.PayloadCounter;
import org.jocean.http.client.HttpClient;
import org.jocean.http.rosa.SignalClient;
import org.jocean.http.rosa.impl.preprocessor.RosaFeatures;
import org.jocean.http.util.FeaturesBuilder;
import org.jocean.http.util.PayloadCounterAware;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.BeanHolderAware;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.rx.DoOnUnsubscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpData;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder.ErrorDataEncoderException;
import io.netty.handler.codec.http.multipart.MemoryFileUpload;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

public class DefaultSignalClient implements SignalClient, BeanHolderAware {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultSignalClient.class);
    
    private static Feature[] _DEFAULT_PROFILE = new Feature[]{
            RosaFeatures.ENABLE_SETMETHOD,
            RosaFeatures.ENABLE_SETURI,
            RosaFeatures.ENABLE_QUERYPARAM,
            RosaFeatures.ENABLE_HEADERPARAM,
            RosaFeatures.ENABLE_DEFAULTBODY,
    };

    public DefaultSignalClient(final HttpClient httpClient) {
        this(null, httpClient, new DefaultAttachmentBuilder());
    }
    
    public DefaultSignalClient(final HttpClient httpClient, 
            final AttachmentBuilder attachmentBuilder) {
        this(null, httpClient, attachmentBuilder);
    }
    
    public DefaultSignalClient(final String defaultUri, 
            final HttpClient httpClient, 
            final AttachmentBuilder attachmentBuilder) {
        this._defaultUri = defaultUri;
        this._httpClient = httpClient;
        this._attachmentBuilder = attachmentBuilder;
    }
    
    @Override
    public <RESP> Observable<? extends RESP> defineInteraction(final Object signalBean) {
        return defineInteraction(signalBean, _DEFAULT_PROFILE, new Attachment[0]);
    }
    
    @Override
    public <RESP> Observable<? extends RESP> defineInteraction(final Object signalBean, final Feature... features) {
        return defineInteraction(signalBean, features, new Attachment[0]);
    }

    @Override
    public <RESP> Observable<? extends RESP> defineInteraction(final Object signalBean, final Attachment... attachments) {
        return defineInteraction(signalBean, _DEFAULT_PROFILE, attachments);
    }
    
    @Override
    public <RESP> Observable<? extends RESP> defineInteraction(
            final Object signalBean, 
            final Feature[] features, 
            final Attachment[] attachments) {
        return Observable.create(new OnSubscribe<RESP>() {
            @Override
            public void call(final Subscriber<? super RESP> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    final URI uri = req2uri(signalBean);
                    final SocketAddress remoteAddress = safeGetAddress(signalBean, uri);
                    _httpClient.defineInteraction(
                            remoteAddress, 
                            requestProviderOf(signalBean, 
                                applyRequestPreprocessors(
                                    signalBean, 
                                    initRequestOf(uri), 
                                    RequestPreprocessor.Util.filter(features)), 
                                attachments, 
                                features),
                            JOArrays.addFirst(Feature[].class, 
                                safeGetRequestFeatures(signalBean), 
                                features))
                    .compose(new ToSignalResponse<RESP>(safeGetResponseClass(signalBean)))
                    .subscribe(subscriber);
                }
            }
        });
    }

    private HttpRequest initRequestOf(final URI uri) {
        final HttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        if (null != uri.getHost()) {
            request.headers().set(HttpHeaders.Names.HOST, uri.getHost());
        }
        return request;
    }

    private Func1<DoOnUnsubscribe, Observable<? extends Object>> requestProviderOf(
            final Object signalBean, 
            final HttpRequest request, 
            final Attachment[] attachments, 
            final Feature[] features) {
        return new Func1<DoOnUnsubscribe, Observable<? extends Object>>() {
            @Override
            public Observable<? extends Object> call(final DoOnUnsubscribe doOnUnsubscribe) {
                final BodyForm body = buildBody(
                        signalBean, 
                        request, 
                        BodyPreprocessor.Util.filter(features));
                try {
                    final Outgoing outgoing = assembleOutgoing(doOnUnsubscribe, 
                            request, 
                            body,
                            attachments,
                            features);
                    hookPayloadCounter(outgoing.requestSizeInBytes(), features);
                    return outgoing.request();
                } catch (Exception e) {
                    return Observable.error(e);
                } finally {
                    ReferenceCountUtil.release(body);
                }
            }};
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
        
        Outgoing(final Observable<Object> request, final long size) {
            this._requestObservable = request;
            this._requestSizeInBytes = size;
        }
        
        Observable<Object> request() {
            return this._requestObservable;
        }
        
        long requestSizeInBytes() {
            return this._requestSizeInBytes;
        }
    }
    
    private Outgoing assembleOutgoing(
            final DoOnUnsubscribe doOnUnsubscribe, 
            final HttpRequest request, 
            final BodyForm body,
            final Attachment[] attachments, 
            final Feature[] features) throws Exception {
        if (0 == attachments.length) {
            final LastHttpContent lastContent = buildLastContent(request, body);
            doOnUnsubscribe.call(Subscriptions.create(
                    new Action0() {
                        @Override
                        public void call() {
                            ReferenceCountUtil.release(request);
                            ReferenceCountUtil.release(lastContent);
                        }}));
            return new Outgoing(Observable.<Object>just(request, lastContent), 
                    lastContent.content().readableBytes());
        } else {
            return fillBodyWithAttachments(doOnUnsubscribe, request, body,
                    attachments);
        }
    }

    private Outgoing fillBodyWithAttachments(
            final DoOnUnsubscribe doOnUnsubscribe,
            final HttpRequest request,
            final BodyForm body, 
            final Attachment[] attachments)
        throws ErrorDataEncoderException, IOException {
        // multipart
        final HttpDataFactory factory = new DefaultHttpDataFactory(false);
        
        // Use the PostBody encoder
        final HttpPostRequestEncoder postRequestEncoder =
                new HttpPostRequestEncoder(factory, request, true); // true => multipart
        
        final long signalSize = addSignalToMultipart(postRequestEncoder, body);
        final long attachmentSize = addAttachmentsToMultipart(postRequestEncoder, attachments);
        final long total = signalSize + attachmentSize;
        
        // finalize request
        final HttpRequest request4send = postRequestEncoder.finalizeRequest();

        doOnUnsubscribe.call(Subscriptions.create(
                new Action0() {
                    @Override
                    public void call() {
                        ReferenceCountUtil.release(request4send);
                        RxNettys.releaseObjects(postRequestEncoder.getBodyListAttributes());
                    }}));
        
        return postRequestEncoder.isChunked() 
            ? new Outgoing(Observable.<Object>just(request4send, postRequestEncoder), total)
            : new Outgoing(Observable.<Object>just(request4send), total);
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

    private BodyForm buildBody(final Object signalBean, 
            final HttpRequest request,
            final BodyPreprocessor[] bodyPreprocessors) {
        if (null != bodyPreprocessors) {
            for (BodyPreprocessor bodyPreprocessor : bodyPreprocessors) {
                try {
                    final BodyBuilder builder = bodyPreprocessor.call(signalBean, request);
                    if (null != builder) {
                        final BodyForm body = builder.call();
                        if (null != body) {
                            return body;
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("exception when BodyPreprocessor({}).call, detail: {}",
                            bodyPreprocessor, ExceptionUtils.exception2detail(e));
                }
            }
        }
        return null;
    }

    private HttpRequest applyRequestPreprocessors(final Object signalBean,
            final HttpRequest request, 
            final RequestPreprocessor[] requestPreprocessors) {
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

    private LastHttpContent buildLastContent(
            final HttpRequest request,
            final BodyForm body) {
        if (null != body) {
            HttpHeaders.setContentLength(request, body.length());
            HttpHeaders.setHeader(request, HttpHeaders.Names.CONTENT_TYPE, body.contentType());
            return new DefaultLastHttpContent(body.content().retain());
        } else {
            return LastHttpContent.EMPTY_LAST_CONTENT;
        }
    }

    public Action0 registerRequestType(final Class<?> reqType, 
            final Class<?> respType, 
            final String uri, 
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
            final String uri, 
            final Feature... features) {
        return registerRequestType(reqType, respType, uri, new Func0<Feature[]>() {
            @Override
            public Feature[] call() {
                return features;
            }});
    }
    
    public Action0 registerRequestType(final Class<?> reqType, 
            final Class<?> respType, 
            final String uri, 
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
            final String uri, 
            final Func0<Feature[]> featuresBuilder) {
        return registerRequestType(reqType, respType, uri, featuresBuilder, _DEFAULT_URI2ADDR);
    }

    public Action0 registerRequestType(final Class<?> reqType, 
            final Class<?> respType, 
            final String uri, 
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
    
    private static final Func1<URI, SocketAddress> _DEFAULT_URI2ADDR = new Func1<URI, SocketAddress>() {
        @Override
        public SocketAddress call(final URI uri) {
            final int port = -1 == uri.getPort() ? ( "https".equals(uri.getScheme()) ? 443 : 80 ) : uri.getPort();
            return new InetSocketAddress(uri.getHost(), port);
        }
    };
    
    
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
    
    private final Map<Class<?>, RequestProfile> _signal2profile = 
            new ConcurrentHashMap<>();
    
    private URI req2uri(final Object signalBean) {
        final String uri = safeUriOf(signalBean.getClass());
        
        try {
            return ( null != uri ? new URI(uri) : null);
        } catch (Exception e) {
            LOG.error("exception when generate URI for request({}), detail:{}",
                    signalBean, ExceptionUtils.exception2detail(e));
            return null;
        }
    }

    private SocketAddress safeGetAddress(final Object signalBean, final URI uri) {
        final RequestProfile profile = this._signal2profile.get(signalBean.getClass());
        return (null != profile ? profile.buildAddress(uri) : null);
    }

    private String safeUriOf(final Class<?> reqType) {
        final RequestProfile profile = this._signal2profile.get(reqType);
        final String signalUri = (null != profile ? profile.uri() : null);
        return null != signalUri ? signalUri : this._defaultUri;
    }
    
    private Feature[] safeGetRequestFeatures(final Object signalBean) {
        final RequestProfile profile = _signal2profile.get(signalBean.getClass());
        return (null != profile ? profile.features() : Feature.EMPTY_FEATURES);
    }
    
    private Class<?> safeGetResponseClass(final Object signalBean) {
        final RequestProfile profile = this._signal2profile.get(signalBean.getClass());
        return (null != profile ? profile.responseType() : null);
    }

    @Override
    public void setBeanHolder(final BeanHolder beanHolder) {
        this._beanHolder = beanHolder;
    }
    
    private BeanHolder _beanHolder;
    private final HttpClient _httpClient;
    private final AttachmentBuilder _attachmentBuilder;
    private final String _defaultUri;
}
