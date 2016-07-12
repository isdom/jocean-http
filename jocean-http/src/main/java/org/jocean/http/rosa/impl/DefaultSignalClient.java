package org.jocean.http.rosa.impl;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.ws.rs.POST;

import org.jocean.http.Feature;
import org.jocean.http.PayloadCounter;
import org.jocean.http.client.HttpClient;
import org.jocean.http.rosa.SignalClient;
import org.jocean.http.util.FeaturesBuilder;
import org.jocean.http.util.PayloadCounterAware;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.AnnotationWrapper;
import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.BeanHolderAware;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.io.FilenameUtils;
import org.jocean.idiom.rx.DoOnUnsubscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
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
        this._httpClient = httpClient;
    }
    
    @Override
    public <RESP> Observable<? extends RESP> defineInteraction(final Object request) {
        return defineInteraction(request, Feature.EMPTY_FEATURES, new Attachment[0]);
    }
    
    @Override
    public <RESP> Observable<? extends RESP> defineInteraction(final Object request, final Feature... features) {
        return defineInteraction(request, features, new Attachment[0]);
    }

    @Override
    public <RESP> Observable<? extends RESP> defineInteraction(final Object request, final Attachment... attachments) {
        return defineInteraction(request, Feature.EMPTY_FEATURES, attachments);
    }
    
    @Override
    public <RESP> Observable<? extends RESP> defineInteraction(
            final Object request, 
            final Feature[] features, 
            final Attachment[] attachments) {
        return Observable.create(new OnSubscribe<RESP>() {
            @Override
            public void call(final Subscriber<? super RESP> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    final URI uri = req2uri(request);
                    final SocketAddress remoteAddress = safeGetAddress(request, uri);
                    
                    _httpClient.defineInteraction(
                            remoteAddress, 
                            requestProviderOf(uri, request, attachments, features),
                            JOArrays.addFirst(Feature[].class, 
                                safeGetRequestFeatures(request), 
                                features))
                    .compose(new ToSignalResponse<RESP>(safeGetResponseClass(request)))
                    .subscribe(subscriber);
                }
            }
        });
    }

    private Func1<DoOnUnsubscribe, Observable<? extends Object>> requestProviderOf(
            final URI uri, final Object request, final Attachment[] attachments, final Feature[] features) {
        return new Func1<DoOnUnsubscribe, Observable<? extends Object>>() {

            @Override
            public Observable<? extends Object> call(final DoOnUnsubscribe doOnUnsubscribe) {
                try {
                    final Outgoing outgoing = buildHttpRequest(doOnUnsubscribe, uri, request, attachments);
                    hookPayloadCounter(outgoing.bytes(), features);
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
        
        final Observable<Object> _request;
        final long _bytes;
        
        Outgoing(Observable<Object> request, long bytes) {
            this._request = request;
            this._bytes = bytes;
        }
        
        Observable<Object> request() {
            return this._request;
        }
        
        long bytes() {
            return this._bytes;
        }
    }
    
    //  TODO return class with request (outgoing)/totoal size/close function
    private Outgoing buildHttpRequest(
            final DoOnUnsubscribe doOnUnsubscribe, 
            final URI uri,
            final Object request, 
            final Attachment[] attachments) throws Exception {
        
        final HttpRequest httpRequest = 
                genHttpRequest(uri, 
                        getHttpMethodAsNettyForm(request.getClass()), 
                        0 == attachments.length);
        
        if (null != uri.getHost()) {
            httpRequest.headers().set(HttpHeaders.Names.HOST, uri.getHost());
        }
        this._processorCache.get(request.getClass())
            .applyParams(request, httpRequest);
        
        if (0 == attachments.length) {
            if ( httpRequest.getMethod().equals(HttpMethod.POST)) {
                fillContentAsJSON((FullHttpRequest)httpRequest, JSON.toJSONBytes(request));
            }
            
            doOnUnsubscribe.call(Subscriptions.create(
                    new Action0() {
                        @Override
                        public void call() {
                            ReferenceCountUtil.release(httpRequest);
                        }}));
            
            return new Outgoing(Observable.<Object>just(httpRequest), -1L);
        } else {
            // multipart
            final HttpDataFactory factory = new DefaultHttpDataFactory(false);
            
            long total = 0;
            // Use the PostBody encoder
            final HttpPostRequestEncoder postRequestEncoder =
                    new HttpPostRequestEncoder(factory, httpRequest, true); // true => multipart

            final byte[] jsonBytes = JSON.toJSONBytes(request);
            final MemoryFileUpload jsonFile = 
                    new MemoryFileUpload("json", "json", "application/json", null, null, jsonBytes.length) {
                        @Override
                        public Charset getCharset() {
                            return null;
                        }
            };
                
            jsonFile.setContent(Unpooled.wrappedBuffer(jsonBytes));
            
            total += jsonBytes.length;
            postRequestEncoder.addBodyHttpData(jsonFile);
            
            for (Attachment attachment : attachments) {
                final File file = new File(attachment.filename);
                final DiskFileUpload diskFile = 
                        new DiskFileUpload(FilenameUtils.getBaseName(attachment.filename), 
                            attachment.filename, attachment.contentType, null, null, file.length()) {
                    @Override
                    public Charset getCharset() {
                        return null;
                    }
                };
                diskFile.setContent(file);
                total += file.length();
                postRequestEncoder.addBodyHttpData(diskFile);
            }
            
            // finalize request
            final HttpRequest requestToSend = postRequestEncoder.finalizeRequest();

            doOnUnsubscribe.call(Subscriptions.create(
                    new Action0() {
                        @Override
                        public void call() {
                            ReferenceCountUtil.release(requestToSend);
                            RxNettys.releaseObjects(postRequestEncoder.getBodyListAttributes());
                        }}));
            
            // test if request was chunked and if so, finish the write
            if (postRequestEncoder.isChunked()) {
                return new Outgoing(Observable.<Object>just(requestToSend, postRequestEncoder), total);
            } else {
                return new Outgoing(Observable.<Object>just(requestToSend), total);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends HttpRequest> T genHttpRequest(
            final URI uri, 
            final HttpMethod httpMethod, 
            final boolean isFull) {
        if (isFull) {
            return (T)new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, httpMethod, uri.getRawPath());
        } else {
            return (T)new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, httpMethod, uri.getRawPath());
        }
    }
    
    private static HttpMethod getHttpMethodAsNettyForm(final Class<?> reqCls) {
        final AnnotationWrapper wrapper = 
                reqCls.getAnnotation(AnnotationWrapper.class);
        if ( null != wrapper ) {
            return wrapper.value().equals(POST.class) ? HttpMethod.POST : HttpMethod.GET;
        }
        else {
            return HttpMethod.GET;
        }
    }
    
    private static void fillContentAsJSON(
            final FullHttpRequest httpRequest,
            final byte[] jsonBytes) {
        final OutputStream os = new ByteBufOutputStream(httpRequest.content());
        try {
            os.write(jsonBytes);
            HttpHeaders.setContentLength(httpRequest, jsonBytes.length);
        }
        catch (Throwable e) {
            LOG.warn("exception when write json to response, detail:{}", 
                    ExceptionUtils.exception2detail(e));
        }
        finally {
            if ( null != os ) {
                try {
                    os.close();
                } catch (Exception e) {
                }
            }
        }
        httpRequest.headers().set(HttpHeaders.Names.CONTENT_TYPE, "application/json; charset=UTF-8");
    }
    
    public Action0 registerRequestType(final Class<?> reqCls, final Class<?> respCls, final String pathPrefix, 
            final Func1<URI, SocketAddress> uri2address,
            final Feature... features) {
        return registerRequestType(reqCls, respCls, pathPrefix, 
                new Func0<Feature[]>() {
                    @Override
                    public Feature[] call() {
                        return features;
                    }},
                uri2address);
    }
    
    public Action0 registerRequestType(final Class<?> reqCls, final Class<?> respCls, final String pathPrefix, 
            final Feature... features) {
        return registerRequestType(reqCls, respCls, pathPrefix, new Func0<Feature[]>() {
            @Override
            public Feature[] call() {
                return features;
            }});
    }
    
    public Action0 registerRequestType(final Class<?> reqCls, final Class<?> respCls, final String pathPrefix, 
            final String featuresName) {
        return registerRequestType(reqCls, respCls, pathPrefix, new Func0<Feature[]>() {
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
    
    public Action0 registerRequestType(final Class<?> reqCls, 
            final Class<?> respCls, 
            final String pathPrefix, 
            final Func0<Feature[]> featuresBuilder) {
        return registerRequestType(reqCls, respCls, pathPrefix, featuresBuilder, _DEFAULT_URI2ADDR);
    }

    public Action0 registerRequestType(final Class<?> reqCls, 
            final Class<?> respCls, 
            final String pathPrefix, 
            final Func0<Feature[]> featuresBuilder,
            final Func1<URI, SocketAddress> uri2address) {
        this._req2profile.put(reqCls, new RequestProfile(respCls, pathPrefix, featuresBuilder, uri2address));
        LOG.info("register request type {} with resp type {}/path {}/features builder {}",
                reqCls, respCls, pathPrefix, featuresBuilder);
        return new Action0() {
            @Override
            public void call() {
                _req2profile.remove(reqCls);
                LOG.info("unregister request type {}", reqCls);
            }};
    }
    
    private static final Func1<URI, SocketAddress> _DEFAULT_URI2ADDR = new Func1<URI, SocketAddress>() {
        @Override
        public SocketAddress call(final URI uri) {
            final int port = -1 == uri.getPort() ? ( "https".equals(uri.getScheme()) ? 443 : 80 ) : uri.getPort();
            return new InetSocketAddress(uri.getHost(), port);
        }
    };
    
    
    public String[] getRegisteredRequests() {
        final List<String> ret = new ArrayList<>();
        for ( Map.Entry<Class<?>, RequestProfile> entry 
                : _req2profile.entrySet()) {
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
    
    public String[] getEmittedRequests() {
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
    
    private final Map<Class<?>, RequestProfile> _req2profile = 
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

    private SocketAddress safeGetAddress(final Object request, final URI uri) {
        final RequestProfile profile = this._req2profile.get(request.getClass());
        return (null != profile ? profile.buildAddress(uri) : null);
    }

    private String safeGetPathPrefix(final Class<?> reqCls) {
        final RequestProfile profile = this._req2profile.get(reqCls);
        return (null != profile ? profile.pathPrefix() : null);
    }
    
    private Feature[] safeGetRequestFeatures(final Object request) {
        final RequestProfile profile = _req2profile.get(request.getClass());
        return (null != profile ? profile.features() : Feature.EMPTY_FEATURES);
    }
    
    private Class<?> safeGetResponseClass(final Object request) {
        final RequestProfile profile = this._req2profile.get(request.getClass());
        return (null != profile ? profile.responseType() : null);
    }

    private final SimpleCache<Class<?>, RequestProcessor> _processorCache = 
        new SimpleCache<Class<?>, RequestProcessor>(
            new Func1<Class<?>, RequestProcessor>() {
                @Override
                public RequestProcessor call(final Class<?> reqCls) {
                    return new RequestProcessor(reqCls);
                }});
    
    @Override
    public void setBeanHolder(final BeanHolder beanHolder) {
        this._beanHolder = beanHolder;
    }
    
    final private HttpClient _httpClient;
    private BeanHolder _beanHolder;
}
