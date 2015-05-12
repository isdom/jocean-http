package org.jocean.http.rosa.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.MemoryFileUpload;
import io.netty.util.ReferenceCountUtil;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.OutboundFeature;
import org.jocean.http.rosa.SignalClient;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.AnnotationWrapper;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.idiom.PropertyPlaceholderHelper;
import org.jocean.idiom.PropertyPlaceholderHelper.PlaceholderResolver;
import org.jocean.idiom.ReflectUtils;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Triple;
import org.jocean.idiom.Visitor2;
import org.jocean.idiom.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;

import com.alibaba.fastjson.JSON;

public class DefaultSignalClient implements SignalClient {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultSignalClient.class);

    public interface SignalConverter {

        public URI req2uri(final Object request);

        public HttpRequest processHttpRequest(final Object request,
                final DefaultFullHttpRequest httpRequest);
    }
    
    public DefaultSignalClient(final HttpClient httpClient) {
        this._httpClient = httpClient;
    }
    
    @Override
    public Observable<? extends Object> defineInteraction(final Object request, final Attachment... attachments) {
        return Observable.create(new OnSubscribe<Object>() {

            @Override
            public void call(final Subscriber<? super Object> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    final URI uri = _converter.req2uri(request);
                    
                    final int port = -1 == uri.getPort() ? ( "https".equals(uri.getScheme()) ? 443 : 80 ) : uri.getPort();
                    final InetSocketAddress remoteAddress = new InetSocketAddress(uri.getHost(), port);
                    
                    Observable<? extends Object> response = null;
                    long uploadsize = -1;
                    List<Object> httpRequest = null;
                    
                    try {
                        final Pair<List<Object>,Long> ret = buildHttpRequest(uri, request, attachments);
                        httpRequest = ret.first;
                        response = _httpClient.defineInteraction(
                            remoteAddress, 
                            Observable.from(httpRequest),
                            safeGetRequestFeatures(request));
                        uploadsize = ret.second;
                    } catch (Exception e) {
                        subscriber.onError(e);
                        return;
                    }
                    
                    final long uploadTotal = uploadsize;
                    final List<Object> requestRaw = httpRequest;
                    
                    final AtomicLong uploadProgress = new AtomicLong(0);
                    final AtomicLong downloadProgress = new AtomicLong(0);
                    final List<HttpObject> httpObjects = new ArrayList<>();
                    
                    response.map(new Func1<Object,Object>() {
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
                    }})
                    .doOnNext(new Action1<Object>() {
                        @Override
                        public void call(final Object obj) {
                            if (obj instanceof HttpObject) {
                                httpObjects.add(ReferenceCountUtil.retain((HttpObject)obj));
                            }
                        }})
                    .filter(new Func1<Object, Boolean>() {
                        @Override
                        public Boolean call(final Object obj) {
                            return !(obj instanceof HttpObject);
                        }})
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
                            RxNettys.releaseObjects(requestRaw);
                        }})
                    .doOnUnsubscribe(new Action0() {
                        @Override
                        public void call() {
                            RxNettys.releaseObjects(httpObjects);                            
                            RxNettys.releaseObjects(requestRaw);
                        }})
                    .subscribe(subscriber);
                    
                    /*
                    final Subscription subscription = response.subscribe(
                            new Subscriber<Object>() {
                        private final List<HttpObject> _respObjects = new ArrayList<>();
                        private final AtomicLong _uploadProgress = new AtomicLong(0);
                        private final AtomicLong _downloadProgress = new AtomicLong(0);
                        
                        private FullHttpResponse retainFullHttpResponse() {
                            if (this._respObjects.size()>0) {
                                if (this._respObjects.get(0) instanceof FullHttpResponse) {
                                    return ((FullHttpResponse)this._respObjects.get(0)).retain();
                                }
                                
                                final HttpResponse resp = (HttpResponse)this._respObjects.get(0);
                                final ByteBuf[] bufs = new ByteBuf[this._respObjects.size()-1];
                                for (int idx = 1; idx<this._respObjects.size(); idx++) {
                                    bufs[idx-1] = ((HttpContent)this._respObjects.get(idx)).content().retain();
                                }
                                return new DefaultFullHttpResponse(
                                        resp.getProtocolVersion(), 
                                        resp.getStatus(),
                                        Unpooled.wrappedBuffer(bufs));
                            } else {
                                return null;
                            }
                        }
                        
                        @Override
                        public void onCompleted() {
                            final FullHttpResponse httpResp = retainFullHttpResponse();
                            if (null!=httpResp) {
                                try {
                                    final InputStream is = new ByteBufInputStream(httpResp.content());
                                    try {
                                        final byte[] bytes = new byte[is.available()];
                                        @SuppressWarnings("unused")
                                        final int readed = is.read(bytes);
                                        final Object resp = JSON.parseObject(bytes, safeGetResponseClass(request));
                                        subscriber.onNext(resp);
                                        subscriber.onCompleted();
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

                        @Override
                        public void onError(final Throwable e) {
                            subscriber.onError(e);
                        }

                        @Override
                        public void onNext(final Object object) {
                            if (object instanceof HttpClient.UploadProgressable) {
                                final long progress = 
                                        _uploadProgress.addAndGet(((HttpClient.UploadProgressable)object).progress());
                                subscriber.onNext(new UploadProgressable() {
                                    @Override
                                    public long progress() {
                                        return progress;
                                    }
                                    @Override
                                    public long total() {
                                        return uploadTotal;
                                    }});
                            } else if (object instanceof HttpClient.DownloadProgressable) {
                                final long progress = 
                                        _downloadProgress.addAndGet(((HttpClient.DownloadProgressable)object).progress());
                                subscriber.onNext(new DownloadProgressable() {
                                    @Override
                                    public long progress() {
                                        return progress;
                                    }
                                    @Override
                                    public long total() {
                                        return -1;
                                    }});
                            } else if (object instanceof HttpObject) {
                                this._respObjects.add(ReferenceCountUtil.retain((HttpObject)object));
                            }
                        }});
                    subscriber.add(subscription);
                    */
                }
            }
            
        });
    }

    protected Pair<List<Object>,Long> buildHttpRequest(
            final URI uri,
            final Object request, 
            final Attachment[] attachments) throws Exception {
        
        final List<Object> ret = new ArrayList<>();
        if (0 == attachments.length) {
            final HttpRequest httpRequest =
                    _converter.processHttpRequest(request,
                            genFullHttpRequest(uri));
            ret.addAll(Arrays.asList(new Object[]{httpRequest}));
            return Pair.of(ret, -1L);
        } else {
            // multipart
            final HttpRequest httpRequest = genPostHttpRequest(uri);
            
            final HttpDataFactory factory = new DefaultHttpDataFactory(false);
            
            try {
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
            } catch (Exception e) {
                throw e;
            }
        }
    }

    @SuppressWarnings("rawtypes")
    public void registerRequestType(final Class<?> reqCls, final Class<?> respCls, final String pathPrefix, 
            final OutboundFeature.Applicable... features) {
        this._req2pathPrefix.put(reqCls, Triple.of((Class)respCls, pathPrefix, features));
    }
    
    private OutboundFeature.Applicable[] safeGetRequestFeatures(final Object request) {
        @SuppressWarnings("rawtypes")
        final Triple<Class,String, OutboundFeature.Applicable[]> triple = _req2pathPrefix.get(request.getClass());
        return (null != triple ? triple.third : OutboundFeature.EMPTY_APPLICABLES);
    }
    
    private Class<?> safeGetResponseClass(final Object request) {
        @SuppressWarnings("rawtypes")
        final Triple<Class,String, OutboundFeature.Applicable[]> triple = _req2pathPrefix.get(request.getClass());
        return (null != triple ? triple.first : null);
    }
    
    private static DefaultFullHttpRequest genFullHttpRequest(final URI uri) {
        // Prepare the HTTP request.
        final String host = uri.getHost() == null ? "localhost" : uri.getHost();

        final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getRawPath());
        request.headers().set(HttpHeaders.Names.HOST, host);

        return request;
    }
    
    private static DefaultHttpRequest genPostHttpRequest(final URI uri) {
        // Prepare the HTTP request.
        final String host = uri.getHost() == null ? "localhost" : uri.getHost();

        final DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, uri.getRawPath());
        request.headers().set(HttpHeaders.Names.HOST, host);

        return request;
    }
    
    @SuppressWarnings("rawtypes")
    private final Map<Class<?>, Triple<Class, String, OutboundFeature.Applicable[]>> _req2pathPrefix = 
            new ConcurrentHashMap<>();
    
    private final SignalConverter _converter = new SignalConverter() {

        @Override
        public URI req2uri(final Object request) {
            final String uri = 
                _processorCache.get(request.getClass())
                .apply(request);
            
            try {
                return ( null != uri ? new URI(uri) : null);
            } catch (Exception e) {
                LOG.error("exception when generate URI for request({}), detail:{}",
                        request, ExceptionUtils.exception2detail(e));
                return null;
            }
        }

        @Override
        public HttpRequest processHttpRequest(
                final Object request, 
                final DefaultFullHttpRequest httpRequest) {
            try {
                _processorCache.get(request.getClass())
                    .visit(request, httpRequest);
            }
            catch (Exception e) {
                LOG.error("exception when process httpRequest ({}) with request bean({})",
                        httpRequest, request);
            }
            
            return httpRequest;
        }};
        
    private final SimpleCache<Class<?>, RequestProcessor> _processorCache = 
        new SimpleCache<Class<?>, RequestProcessor>(
            new Function<Class<?>, RequestProcessor>() {
                @Override
                public RequestProcessor apply(final Class<?> reqCls) {
                    return new RequestProcessor(reqCls);
                }});
    
    private final class RequestProcessor 
        implements Function<Object, String>, Visitor2<Object, DefaultFullHttpRequest> {

        RequestProcessor(final Class<?> reqCls) {
            this._queryFields = ReflectUtils.getAnnotationFieldsOf(reqCls, QueryParam.class);
            
            this._pathSuffix = getPathValueOf(reqCls);
            
            this._pathparamResolver = genPlaceholderResolverOf(reqCls, PathParam.class);

            this._pathparamReplacer = 
                    ( null != this._pathparamResolver ? new PropertyPlaceholderHelper("{", "}") : null);
        }

        @Override
        public String apply(final Object request) {
            final String pathPrefix = safeGetPathPrefix(request);
            if ( null == pathPrefix && null == this._pathSuffix ) {
                // class not registered, return null
                return null;
            }
            
            final String fullPath = safeConcatPath(pathPrefix, this._pathSuffix );
            if ( null != this._pathparamReplacer ) {
                return this._pathparamReplacer.replacePlaceholders(
                        request,
                        fullPath, 
                        this._pathparamResolver, 
                        null);
            }
            else {
                return fullPath;
            }
        }

        private String safeGetPathPrefix(final Object request) {
            @SuppressWarnings("rawtypes")
            final Triple<Class, String, OutboundFeature.Applicable[]> triple = _req2pathPrefix.get(request.getClass());
            return (null != triple ? triple.second : null);
        }
        
        @Override
        public void visit(final Object request, final DefaultFullHttpRequest httpRequest) 
                throws Exception {
            final Class<?> httpMethod = getHttpMethod(request);
            if ( null == httpMethod 
                || GET.class.equals(httpMethod)) {
                genGetRequest(request, httpRequest);
            }
            else if (POST.class.equals(httpMethod)) {
                genPostRequest(request, httpRequest);
            }
        }

        private void genPostRequest(
                final Object request,
                final DefaultFullHttpRequest httpRequest) {
            final byte[] jsonBytes = JSON.toJSONBytes(request);
            
//            if ( RequestFeature.isEnabled(features, RequestFeature.EnableJsonCompress)) {
//                genContentAsCJSON(httpRequest, jsonBytes);
//            }
//            else {
                genContentAsJSON(httpRequest, jsonBytes);
//            }
            
            httpRequest.setMethod(HttpMethod.POST);
//            httpRequest.content().writeBytes(jsonBytes);
        }

        /**
         * @param httpRequest
         * @param jsonBytes
         */
//        private void genContentAsCJSON(
//                final DefaultFullHttpRequest httpRequest,
//                final byte[] jsonBytes) {
//            final OutputStream os = new ByteBufOutputStream(httpRequest.content());
//            DeflaterOutputStream zos = null;
//            
//            try {
//                zos = new DeflaterOutputStream(os, new Deflater(JZlib.Z_BEST_COMPRESSION));
//                zos.write(jsonBytes);
//                zos.finish();
//                HttpHeaders.setContentLength(httpRequest, zos.getTotalOut());
//            }
//            catch (Throwable e) {
//                LOG.warn("exception when compress json, detail:{}", 
//                        ExceptionUtils.exception2detail(e));
//            }
//            finally {
//                if ( null != zos ) {
//                    try {
//                        zos.close();
//                    } catch (Exception e) {
//                    }
//                }
//            }
//            httpRequest.headers().set(HttpHeaders.Names.CONTENT_TYPE, "application/cjson");
//        }

        private void genContentAsJSON(
                final DefaultFullHttpRequest httpRequest,
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
            httpRequest.headers().set(HttpHeaders.Names.CONTENT_TYPE, "application/json");
        }

        private void genGetRequest(
                final Object request, 
                final DefaultFullHttpRequest httpRequest) {
            if ( null != this._queryFields ) {
                final StringBuilder sb = new StringBuilder();
                char link = '?';
                for ( Field field : this._queryFields ) {
                    try {
                        final Object value = field.get(request);
                        if ( null != value ) {
                            final String paramkey = 
                                    field.getAnnotation(QueryParam.class).value();
                            final String paramvalue = 
                                    URLEncoder.encode(String.valueOf(value), "UTF-8");
                            sb.append(link);
                            sb.append(paramkey);
                            sb.append("=");
                            sb.append(paramvalue);
                            link = '&';
                        }
                    }
                    catch (Exception e) {
                        LOG.warn("exception when get field({})'s value, detail:{}", 
                                field, ExceptionUtils.exception2detail(e));
                    }
                }
                
                if ( sb.length() > 0 ) {
                    httpRequest.setUri( httpRequest.getUri() + sb.toString() );
                }
            }
        }
        
        
        private final Field[] _queryFields;
        
        private final String _pathSuffix;
        
        private final PropertyPlaceholderHelper _pathparamReplacer;
        
        private final PlaceholderResolver _pathparamResolver;
    };
    
    /**
     * @param cls
     */
    private static String getPathValueOf(final Class<?> cls) {
        final Path path = cls.getAnnotation(Path.class);
        return (null != path ? path.value() : null);
    }

    private static String safeConcatPath(final String pathPrefix, final String pathSuffix) {
        if ( null == pathSuffix ) {
            return pathPrefix;
        }
        if ( null == pathPrefix ) {
            return pathSuffix;
        }
        return pathPrefix + pathSuffix;
    }

    /**
     * @return
     */
    private static PlaceholderResolver genPlaceholderResolverOf(
            Class<?> cls, Class<? extends Annotation> annotationCls) {
        final Map<String, Field> pathparam2fields = 
            genPath2FieldMapping(
                ReflectUtils.getAnnotationFieldsOf(cls, annotationCls));
        
        final Map<String, Method> pathparam2methods = 
            genPath2MethodMapping(cls, 
                ReflectUtils.getAnnotationMethodsOf(cls, annotationCls));
        
        if ( null == pathparam2fields 
           && null ==  pathparam2methods) {
            return null;
        }
        
        return new PropertyPlaceholderHelper.PlaceholderResolver() {
            @Override
            public String resolvePlaceholder(final Object request,
                    final String placeholderName) {
                if (null != pathparam2fields) {
                    final Field field = pathparam2fields
                            .get(placeholderName);
                    if (null != field) {
                        try {
                            return String.valueOf(field.get(request));
                        } catch (Exception e) {
                            LOG.error("exception when get value for ({}).{}, detail: {}",
                                    request, field.getName(),
                                    ExceptionUtils.exception2detail(e));
                        }
                    }
                }

                if ( null != pathparam2methods ) {
                    final Method method = pathparam2methods
                            .get(placeholderName);
                    if (null != method) {
                        try {
                            return String.valueOf(method.invoke(request));
                        } catch (Exception e) {
                            LOG.error("exception when invoke ({}).{}, detail: {}",
                                    request, method.getName(),
                                    ExceptionUtils.exception2detail(e));
                        }
                    }
                }

                // default by empty string, so placeholder will be erased
                // from uri
                return "";
            }
        };
    }
    
    private static Map<String, Field> genPath2FieldMapping(
            final Field[] pathparamFields) {
        if ( null != pathparamFields ) {
            final Map<String, Field> ret = new HashMap<String, Field>();
            for ( Field field : pathparamFields ) {
                ret.put(field.getAnnotation(PathParam.class).value(), field);
            }
            return ret;
        }
        else {
            return null;
        }
    }
    
    private static Map<String, Method> genPath2MethodMapping(
            final Class<?> cls, 
            final Method[] pathparamMethods) {
        if ( null != pathparamMethods ) {
            final Map<String, Method> ret = new HashMap<String, Method>();
            for ( Method method : pathparamMethods ) {
                if ( method.getParameterTypes().length == 0 
                    && !method.getReturnType().equals(void.class)) {
                    ret.put(method.getAnnotation(PathParam.class).value(), method);
                }
                else {
                    LOG.warn("class({}).{} can't be invoke as PathParam, just ignore",
                            cls, method.getName());
                }
            }
            return ( !ret.isEmpty() ?  ret : null);
        }
        else {
            return null;
        }
    }
    
    /**
     * @param request
     */
    private static Class<?> getHttpMethod(final Object request) {
        final AnnotationWrapper wrapper = 
                request.getClass().getAnnotation(AnnotationWrapper.class);
        if ( null != wrapper ) {
            return wrapper.value();
        }
        else {
            return null;
        }
    }

    final private HttpClient _httpClient;
}