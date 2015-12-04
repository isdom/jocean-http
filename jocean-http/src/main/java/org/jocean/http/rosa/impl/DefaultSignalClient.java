package org.jocean.http.rosa.impl;

import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jocean.http.Feature;
import org.jocean.http.client.HttpClient;
import org.jocean.http.rosa.SignalClient;
import org.jocean.http.util.FeaturesBuilder;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.BeanHolderAware;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.Pair;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Triple;
import org.jocean.idiom.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.MemoryFileUpload;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Func0;
import rx.functions.Func1;

public class DefaultSignalClient implements SignalClient, BeanHolderAware {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultSignalClient.class);

    private static Func1<Object, Object> convertProgressable(final long uploadTotal) {
        final AtomicLong uploadProgress = new AtomicLong(0);
        final AtomicLong downloadProgress = new AtomicLong(0);
        
        return new Func1<Object,Object>() {
            @Override
            public Object call(final Object input) {
                if (input instanceof HttpClient.UploadProgressable) {
                    final long progress =
                            uploadProgress.addAndGet(((HttpClient.UploadProgressable)input).progress());
                    return new UploadProgressable() {
                        @Override
                        public long progress() {
                            return progress;
                        }
                        @Override
                        public long total() {
                            return uploadTotal;
                        }};
                } else if (input instanceof HttpClient.DownloadProgressable) {
                    final long progress = 
                            downloadProgress.addAndGet(((HttpClient.DownloadProgressable)input).progress());
                    return new DownloadProgressable() {
                        @Override
                        public long progress() {
                            return progress;
                        }
                        @Override
                        public long total() {
                            return -1;
                        }};
                } else {
                    return input;
                }
        }};
    }
    
    public DefaultSignalClient(final HttpClient httpClient) {
        this._httpClient = httpClient;
    }
    
    @Override
    public Observable<? extends Object> defineInteraction(final Object request) {
        return defineInteraction(request, Feature.EMPTY_FEATURES, new Attachment[0]);
    }
    
    @Override
    public Observable<? extends Object> defineInteraction(final Object request, final Feature... features) {
        return defineInteraction(request, features, new Attachment[0]);
    }

    @Override
    public Observable<? extends Object> defineInteraction(final Object request, final Attachment... attachments) {
        return defineInteraction(request, Feature.EMPTY_FEATURES, attachments);
    }
    
    @Override
    public Observable<? extends Object> defineInteraction(final Object request, final Feature[] features, final Attachment[] attachments) {
        return Observable.create(new OnSubscribe<Object>() {

            @Override
            public void call(final Subscriber<? super Object> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    final URI uri = req2uri(request);
                    
                    final int port = -1 == uri.getPort() ? ( "https".equals(uri.getScheme()) ? 443 : 80 ) : uri.getPort();
                    final InetSocketAddress remoteAddress = new InetSocketAddress(uri.getHost(), port);
                    
                    Observable<? extends Object> response = null;
                    Pair<List<Object>,Long> pair;
                    
                    try {
                        pair = buildHttpRequest(uri, request, attachments);
                    } catch (Exception e) {
                        subscriber.onError(e);
                        return;
                    }
                    
                    final long uploadTotal = pair.second;
                    final List<Object> httpRequest = pair.first;
                    
                    response = _httpClient.defineInteraction(
                            remoteAddress, 
                            Observable.from(httpRequest),
                            JOArrays.addFirst(Feature[].class, safeGetRequestFeatures(request), features));
                    
                    final List<HttpObject> httpObjects = new ArrayList<>();
                    
                    response.map(convertProgressable(uploadTotal))
                    .doOnNext(RxNettys.httpObjectsRetainer(httpObjects))
                    .filter(RxNettys.NOT_HTTPOBJECT)
                    .doOnCompleted(new Action0() {
                        @Override
                        public void call() {
                            final FullHttpResponse httpResp = retainFullHttpResponse(httpObjects);
                            if (null!=httpResp) {
                                try {
                                    final InputStream is = new ByteBufInputStream(httpResp.content());
                                    try {
                                        final byte[] bytes = new byte[is.available()];
                                        @SuppressWarnings("unused")
                                        final int readed = is.read(bytes);
                                        if (LOG.isDebugEnabled()) {
                                            LOG.debug("receive signal response: {}",
                                                    new String(bytes, Charset.forName("UTF-8")));
                                        }
                                        final Object resp = JSON.parseObject(bytes, safeGetResponseClass(request));
                                        subscriber.onNext(resp);
                                    } finally {
                                        is.close();
                                    }
                                } catch (Exception e) {
                                    LOG.warn("exception when parse response {}, detail:{}",
                                            httpResp, ExceptionUtils.exception2detail(e));
                                    subscriber.onError(e);
                                } finally {
                                    httpResp.release();
                                }
                            }
                        }
                        private FullHttpResponse retainFullHttpResponse(final List<HttpObject> httpObjs) {
                            if (httpObjs.size()>0) {
                                if (httpObjs.get(0) instanceof FullHttpResponse) {
                                    return ((FullHttpResponse)httpObjs.get(0)).retain();
                                }
                                
                                final HttpResponse resp = (HttpResponse)httpObjs.get(0);
                                final ByteBuf[] bufs = new ByteBuf[httpObjs.size()-1];
                                for (int idx = 1; idx<httpObjs.size(); idx++) {
                                    bufs[idx-1] = ((HttpContent)httpObjs.get(idx)).content().retain();
                                }
                                return new DefaultFullHttpResponse(
                                        resp.getProtocolVersion(), 
                                        resp.getStatus(),
                                        Unpooled.wrappedBuffer(bufs));
                            } else {
                                return null;
                            }
                        }})
                    .doOnTerminate(new Action0() {
                        @Override
                        public void call() {
                            RxNettys.releaseObjects(httpObjects);
                            RxNettys.releaseObjects(httpRequest);
                        }})
                    .doOnUnsubscribe(new Action0() {
                        @Override
                        public void call() {
                            RxNettys.releaseObjects(httpObjects);                            
                            RxNettys.releaseObjects(httpRequest);
                        }})
                    .subscribe(subscriber);
                }
            }
        });
    }

    private Pair<List<Object>,Long> buildHttpRequest(
            final URI uri,
            final Object request, 
            final Attachment[] attachments) throws Exception {
        
        final List<Object> ret = new ArrayList<>();
        if (0 == attachments.length) {
            final HttpRequest httpRequest = this._processorCache.get(request.getClass())
                    .genFullHttpRequest(uri, request);
            ret.addAll(Arrays.asList(new Object[]{httpRequest}));
            return Pair.of(ret, -1L);
        } else {
            // multipart
            final HttpRequest httpRequest = this._processorCache.get(request.getClass())
                    .genHttpRequest(uri, request, HttpMethod.POST);
            
            final HttpDataFactory factory = new DefaultHttpDataFactory(false);
            
            long total = 0;
            // Use the PostBody encoder
            final HttpPostRequestEncoder bodyRequestEncoder =
                    new HttpPostRequestEncoder(factory, httpRequest, true); // true => multipart

            final List<InterfaceHttpData> datas = new ArrayList<>();
            
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
            datas.add(jsonFile);
            
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
                datas.add(diskFile);
            }
            
            // add Form attribute from previous request in formpost()
            bodyRequestEncoder.setBodyHttpDatas(datas);

            // finalize request
            final HttpRequest requestToSend = bodyRequestEncoder.finalizeRequest();

            // test if request was chunked and if so, finish the write
            if (bodyRequestEncoder.isChunked()) {
                ret.addAll(Arrays.asList(new Object[]{requestToSend, bodyRequestEncoder}));
            } else {
                ret.addAll(Arrays.asList(new Object[]{requestToSend}));
            }
            return Pair.of(ret, total);
        }
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
    
    @SuppressWarnings("rawtypes")
    public Action0 registerRequestType(final Class<?> reqCls, 
            final Class<?> respCls, 
            final String pathPrefix, 
            final Func0<Feature[]> featuresBuilder) {
        this._req2pathPrefix.put(reqCls, Triple.of((Class)respCls, pathPrefix, featuresBuilder));
        LOG.info("register request type {} with resp type {}/path {}/features builder {}",
                reqCls, respCls, pathPrefix, featuresBuilder);
        return new Action0() {
            @Override
            public void call() {
                _req2pathPrefix.remove(reqCls);
                LOG.info("unregister request type {}", reqCls);
            }};
    }
    
    private Feature[] safeGetRequestFeatures(final Object request) {
        @SuppressWarnings("rawtypes")
        final Triple<Class,String, Func0<Feature[]>> triple = _req2pathPrefix.get(request.getClass());
        return (null != triple ? triple.third.call() : Feature.EMPTY_FEATURES);
    }
    
    private Class<?> safeGetResponseClass(final Object request) {
        @SuppressWarnings("rawtypes")
        final Triple<Class,String, ?> triple = _req2pathPrefix.get(request.getClass());
        return (null != triple ? triple.first : null);
    }

    public String[] getRegisteredRequests() {
        final List<String> ret = new ArrayList<>();
        for ( @SuppressWarnings("rawtypes") Map.Entry<Class<?>, Triple<Class, String, Func0<Feature[]>>> entry 
                : _req2pathPrefix.entrySet()) {
            final StringBuilder sb = new StringBuilder();
            sb.append(entry.getKey());
            sb.append("-->");
            sb.append(entry.getValue().second);
            sb.append("/");
            sb.append(entry.getValue().first);
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
    
    @SuppressWarnings("rawtypes")
    private final Map<Class<?>, Triple<Class, String, Func0<Feature[]>>> _req2pathPrefix = 
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

    private final SimpleCache<Class<?>, RequestProcessor> _processorCache = 
        new SimpleCache<Class<?>, RequestProcessor>(
            new Func1<Class<?>, RequestProcessor>() {
                @Override
                public RequestProcessor call(final Class<?> reqCls) {
                    return new RequestProcessor(reqCls);
                }});
    
    private String safeGetPathPrefix(final Class<?> reqCls) {
        @SuppressWarnings("rawtypes")
        final Triple<Class, String, ?> triple = this._req2pathPrefix.get(reqCls);
        return (null != triple ? triple.second : null);
    }
    
    @Override
    public void setBeanHolder(final BeanHolder beanHolder) {
        this._beanHolder = beanHolder;
    }
    
    final private HttpClient _httpClient;
    private BeanHolder _beanHolder;
}
