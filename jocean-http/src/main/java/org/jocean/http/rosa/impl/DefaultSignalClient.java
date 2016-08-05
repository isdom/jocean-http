package org.jocean.http.rosa.impl;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jocean.http.Feature;
import org.jocean.http.PayloadCounter;
import org.jocean.http.client.HttpClient;
import org.jocean.http.rosa.SignalClient;
import org.jocean.http.util.FeaturesBuilder;
import org.jocean.http.util.HttpUtil;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.PayloadCounterAware;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.AnnotationWrapper;
import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.BeanHolderAware;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.rx.DoOnUnsubscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

import io.netty.buffer.Unpooled;
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

    public DefaultSignalClient(final HttpClient httpClient) {
        this(httpClient, new DefaultAttachmentBuilder());
    }
    
    public DefaultSignalClient(final HttpClient httpClient, final AttachmentBuilder attachmentBuilder) {
        this._httpClient = httpClient;
        this._attachmentBuilder = attachmentBuilder;
    }
    
    @Override
    public <RESP> Observable<? extends RESP> defineInteraction(final Object signalBean) {
        return defineInteraction(signalBean, Feature.EMPTY_FEATURES, new Attachment[0]);
    }
    
    @Override
    public <RESP> Observable<? extends RESP> defineInteraction(final Object signalBean, final Feature... features) {
        return defineInteraction(signalBean, features, new Attachment[0]);
    }

    @Override
    public <RESP> Observable<? extends RESP> defineInteraction(final Object signalBean, final Attachment... attachments) {
        return defineInteraction(signalBean, Feature.EMPTY_FEATURES, attachments);
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
                            requestProviderOf(uri, signalBean, attachments, features),
                            JOArrays.addFirst(Feature[].class, 
                                safeGetRequestFeatures(signalBean), 
                                features))
                    .compose(new ToSignalResponse<RESP>(safeGetResponseClass(signalBean)))
                    .subscribe(subscriber);
                }
            }
        });
    }

    private Func1<DoOnUnsubscribe, Observable<? extends Object>> requestProviderOf(
            final URI uri, final Object signalBean, final Attachment[] attachments, final Feature[] features) {
        return new Func1<DoOnUnsubscribe, Observable<? extends Object>>() {

            @Override
            public Observable<? extends Object> call(final DoOnUnsubscribe doOnUnsubscribe) {
                try {
                    final Outgoing outgoing = buildHttpRequest(doOnUnsubscribe, uri, signalBean, attachments);
                    hookPayloadCounter(outgoing.requestSizeInBytes(), features);
                    return outgoing.request();
                } catch (Exception e) {
                    return Observable.error(e);
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
    
    private Outgoing buildHttpRequest(
            final DoOnUnsubscribe doOnUnsubscribe, 
            final URI uri,
            final Object signalBean, 
            final Attachment[] attachments) throws Exception {
        
        final HttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, 
                methodOf(signalBean.getClass()), 
                uri.getRawPath());
        
        if (null != uri.getHost()) {
            request.headers().set(HttpHeaders.Names.HOST, uri.getHost());
        }
        this._processorCache.get(signalBean.getClass())
            .applyParamsToRequest(signalBean, request);
        
        if (0 == attachments.length) {
            final LastHttpContent lastContent = buildLastContent(signalBean, request);
            
            doOnUnsubscribe.call(Subscriptions.create(
                    new Action0() {
                        @Override
                        public void call() {
                            ReferenceCountUtil.release(request);
                            ReferenceCountUtil.release(lastContent);
                        }}));
            
            return new Outgoing(Observable.<Object>just(request, lastContent), -1L);
        } else {
            // multipart
            final HttpDataFactory factory = new DefaultHttpDataFactory(false);
            
            long total = 0;
            // Use the PostBody encoder
            final HttpPostRequestEncoder postRequestEncoder =
                    new HttpPostRequestEncoder(factory, request, true); // true => multipart

            {
                final byte[] json = JSON.toJSONBytes(signalBean);
                final MemoryFileUpload jsonPayload = 
                        new MemoryFileUpload("json", "json", "application/json", null, null, json.length) {
                            @Override
                            public Charset getCharset() {
                                return null;
                            }
                };
                
                jsonPayload.setContent(Unpooled.wrappedBuffer(json));
                    
                total += json.length;
                postRequestEncoder.addBodyHttpData(jsonPayload);
            }
            
            for (Attachment attachment : attachments) {
                final HttpData httpData = _attachmentBuilder.call(attachment);
                total += httpData.length();
                postRequestEncoder.addBodyHttpData(httpData);
            }
            
            // finalize request
            final HttpRequest request4send = postRequestEncoder.finalizeRequest();

            doOnUnsubscribe.call(Subscriptions.create(
                    new Action0() {
                        @Override
                        public void call() {
                            ReferenceCountUtil.release(request4send);
                            RxNettys.releaseObjects(postRequestEncoder.getBodyListAttributes());
                        }}));
            
            // test if request was chunked and if so, finish the write
            if (postRequestEncoder.isChunked()) {
                return new Outgoing(Observable.<Object>just(request4send, postRequestEncoder), total);
            } else {
                return new Outgoing(Observable.<Object>just(request4send), total);
            }
        }
    }

    private LastHttpContent buildLastContent(
            final Object signalBean,
            final HttpRequest request) {
        if ( request.getMethod().equals(HttpMethod.POST)) {
            final byte[] jsonBytes = JSON.toJSONBytes(signalBean);
            HttpHeaders.setContentLength(request, jsonBytes.length);
            HttpHeaders.setHeader(request, HttpHeaders.Names.CONTENT_TYPE, "application/json; charset=UTF-8");
            final LastHttpContent lastContent = new DefaultLastHttpContent();
            Nettys.fillByteBufHolderUsingBytes(lastContent, jsonBytes);
            return lastContent;
        } else {
            return LastHttpContent.EMPTY_LAST_CONTENT;
        }
    }

    static HttpMethod methodOf(final Class<?> signalType) {
        final AnnotationWrapper wrapper = 
                signalType.getAnnotation(AnnotationWrapper.class);
        final HttpMethod method = null != wrapper ? HttpUtil.fromJSR331Type(wrapper.value()) : null;
        return null != method ? method : HttpMethod.GET;
    }
    
    public Action0 registerRequestType(final Class<?> reqType, final Class<?> respType, final String pathPrefix, 
            final Func1<URI, SocketAddress> uri2address,
            final Feature... features) {
        return registerRequestType(reqType, respType, pathPrefix, 
                new Func0<Feature[]>() {
                    @Override
                    public Feature[] call() {
                        return features;
                    }},
                uri2address);
    }
    
    public Action0 registerRequestType(final Class<?> reqType, final Class<?> respType, final String pathPrefix, 
            final Feature... features) {
        return registerRequestType(reqType, respType, pathPrefix, new Func0<Feature[]>() {
            @Override
            public Feature[] call() {
                return features;
            }});
    }
    
    public Action0 registerRequestType(final Class<?> reqType, final Class<?> respType, final String pathPrefix, 
            final String featuresName) {
        return registerRequestType(reqType, respType, pathPrefix, new Func0<Feature[]>() {
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
            final String pathPrefix, 
            final Func0<Feature[]> featuresBuilder) {
        return registerRequestType(reqType, respType, pathPrefix, featuresBuilder, _DEFAULT_URI2ADDR);
    }

    public Action0 registerRequestType(final Class<?> reqType, 
            final Class<?> respType, 
            final String pathPrefix, 
            final Func0<Feature[]> featuresBuilder,
            final Func1<URI, SocketAddress> uri2address) {
        this._signal2profile.put(reqType, new RequestProfile(respType, pathPrefix, featuresBuilder, uri2address));
        LOG.info("register request type {} with resp type {}/path {}/features builder {}",
                reqType, respType, pathPrefix, featuresBuilder);
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
            sb.append(entry.getValue().pathPrefix());
            sb.append("/");
            sb.append(entry.getValue().responseType());
            ret.add(sb.toString());
        }
        
        return ret.toArray(new String[0]);
    }
    
    public String[] getEmittedSignal() {
        final List<String> ret = new ArrayList<>();
        for (Map.Entry<Class<?>, RequestProcessor> entry 
                : this._processorCache.snapshot().entrySet()) {
            final StringBuilder sb = new StringBuilder();
            sb.append(entry.getKey());
            sb.append("-->");
            sb.append(safeGetPathPrefix(entry.getKey()));
            sb.append(entry.getValue().pathSuffix());
            sb.append("/");
            sb.append(entry.getValue());
            ret.add(sb.toString());
        }
        
        return ret.toArray(new String[0]);
    }
    
    private final Map<Class<?>, RequestProfile> _signal2profile = 
            new ConcurrentHashMap<>();
    
    private URI req2uri(final Object request) {
        final String uri = this._processorCache.get(request.getClass())
            .req2path(request, safeGetPathPrefix(request.getClass()));
        
        try {
            return ( null != uri ? new URI(uri) : null);
        } catch (Exception e) {
            LOG.error("exception when generate URI for request({}), detail:{}",
                    request, ExceptionUtils.exception2detail(e));
            return null;
        }
    }

    private SocketAddress safeGetAddress(final Object signalBean, final URI uri) {
        final RequestProfile profile = this._signal2profile.get(signalBean.getClass());
        return (null != profile ? profile.buildAddress(uri) : null);
    }

    private String safeGetPathPrefix(final Class<?> reqType) {
        final RequestProfile profile = this._signal2profile.get(reqType);
        return (null != profile ? profile.pathPrefix() : null);
    }
    
    private Feature[] safeGetRequestFeatures(final Object signalBean) {
        final RequestProfile profile = _signal2profile.get(signalBean.getClass());
        return (null != profile ? profile.features() : Feature.EMPTY_FEATURES);
    }
    
    private Class<?> safeGetResponseClass(final Object signalBean) {
        final RequestProfile profile = this._signal2profile.get(signalBean.getClass());
        return (null != profile ? profile.responseType() : null);
    }

    private final SimpleCache<Class<?>, RequestProcessor> _processorCache = 
        new SimpleCache<Class<?>, RequestProcessor>(
            new Func1<Class<?>, RequestProcessor>() {
                @Override
                public RequestProcessor call(final Class<?> signalType) {
                    return new RequestProcessor(signalType);
                }});
    
    @Override
    public void setBeanHolder(final BeanHolder beanHolder) {
        this._beanHolder = beanHolder;
    }
    
    private BeanHolder _beanHolder;
    final private HttpClient _httpClient;
    final private AttachmentBuilder _attachmentBuilder;
}
