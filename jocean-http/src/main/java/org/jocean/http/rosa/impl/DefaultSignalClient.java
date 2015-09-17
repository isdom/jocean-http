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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

import org.jocean.http.Feature;
import org.jocean.http.client.HttpClient;
import org.jocean.http.rosa.SignalClient;
import org.jocean.http.util.FeaturesBuilder;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.AnnotationWrapper;
import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.BeanHolderAware;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Function;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.Pair;
import org.jocean.idiom.PropertyPlaceholderHelper;
import org.jocean.idiom.PropertyPlaceholderHelper.PlaceholderResolver;
import org.jocean.idiom.ReflectUtils;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Triple;
import org.jocean.idiom.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

import com.alibaba.fastjson.JSON;

public class DefaultSignalClient implements SignalClient, BeanHolderAware {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultSignalClient.class);

    private static final Func1<Object, Boolean> NOT_HTTPOBJECT = new Func1<Object, Boolean>() {
        @Override
        public Boolean call(final Object obj) {
            return !(obj instanceof HttpObject);
        }};
        
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
    
    private static Action1<Object> retainHttpObjects(
            final Collection<HttpObject> httpObjects) {
        return new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (obj instanceof HttpObject) {
                    httpObjects.add(ReferenceCountUtil.retain((HttpObject)obj));
                }
            }};
    }
        
    public interface SignalConverter {

        public URI req2uri(final Object request);

        public HttpRequest genHttpRequest(URI uri, Object request);
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
                    final URI uri = _converter.req2uri(request);
                    
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
                    .doOnNext(retainHttpObjects(httpObjects))
                    .filter(NOT_HTTPOBJECT)
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

    protected Pair<List<Object>,Long> buildHttpRequest(
            final URI uri,
            final Object request, 
            final Attachment[] attachments) throws Exception {
        
        final List<Object> ret = new ArrayList<>();
        if (0 == attachments.length) {
            final HttpRequest httpRequest =
                    _converter.genHttpRequest(uri, request);
            ret.addAll(Arrays.asList(new Object[]{httpRequest}));
            return Pair.of(ret, -1L);
        } else {
            // multipart
            
            final HttpRequest httpRequest = genPostHttpRequest(uri);
            
            this._processorCache.get(request.getClass())
                    .processHttpRequest(request, httpRequest);
            
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
    private final Map<Class<?>, Triple<Class, String, Func0<Feature[]>>> _req2pathPrefix = 
            new ConcurrentHashMap<>();
    
    private final SignalConverter _converter = new SignalConverter() {

        @Override
        public URI req2uri(final Object request) {
            final String uri = 
                _processorCache.get(request.getClass())
                .req2path(request);
            
            try {
                return ( null != uri ? new URI(uri) : null);
            } catch (Exception e) {
                LOG.error("exception when generate URI for request({}), detail:{}",
                        request, ExceptionUtils.exception2detail(e));
                return null;
            }
        }

        @Override
        public HttpRequest genHttpRequest(
                final URI uri,
                final Object request) {
            try {
                return _processorCache.get(request.getClass())
                    .genHttpRequest(uri, request);
            }
            catch (Exception e) {
                LOG.error("exception when generate httpRequest for request bean({})",
                        request);
                return null;
            }
        }};
        
    private final SimpleCache<Class<?>, RequestProcessor> _processorCache = 
        new SimpleCache<Class<?>, RequestProcessor>(
            new Function<Class<?>, RequestProcessor>() {
                @Override
                public RequestProcessor apply(final Class<?> reqCls) {
                    return new RequestProcessor(reqCls);
                }});
    
    private final class RequestProcessor {

        RequestProcessor(final Class<?> reqCls) {
            this._queryFields = ReflectUtils.getAnnotationFieldsOf(reqCls, QueryParam.class);
            this._headerFields = ReflectUtils.getAnnotationFieldsOf(reqCls, HeaderParam.class);
            
            this._pathSuffix = getPathValueOf(reqCls);
            
            this._pathparamResolver = genPlaceholderResolverOf(reqCls, PathParam.class);

            this._pathparamReplacer = 
                    ( null != this._pathparamResolver ? new PropertyPlaceholderHelper("{", "}") : null);
        }

        public String req2path(final Object request) {
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
            final Triple<Class, String, ?> triple = _req2pathPrefix.get(request.getClass());
            return (null != triple ? triple.second : null);
        }
        
        public DefaultFullHttpRequest genHttpRequest(final URI uri, final Object request) {
            final DefaultFullHttpRequest httpRequest = genFullHttpRequest(uri);

            final Class<?> httpMethod = getHttpMethod(request);
            if ( null == httpMethod 
                || GET.class.equals(httpMethod)) {
                genQueryParamsRequest(request, httpRequest);
            }
            else if (POST.class.equals(httpMethod)) {
                genPostRequest(request, httpRequest);
            }
            
            applyHeaderParams(request, httpRequest);
            return httpRequest;
        }

        public void processHttpRequest(final Object request, final HttpRequest httpRequest) {
            genQueryParamsRequest(request, httpRequest);
            applyHeaderParams(request, httpRequest);
        }
        
        private void applyHeaderParams(
                final Object request,
                final HttpRequest httpRequest) {
            if ( null != this._headerFields ) {
                for ( Field field : this._headerFields ) {
                    try {
                        final Object value = field.get(request);
                        if ( null != value ) {
                            final String headername = 
                                field.getAnnotation(HeaderParam.class).value();
                            httpRequest.headers().set(headername, value);
                        }
                    } catch (Exception e) {
                        LOG.warn("exception when get value from field:[{}], detail:{}",
                                field, ExceptionUtils.exception2detail(e));
                    }
                }
                
            }
        }

        private void genPostRequest(
                final Object request,
                final DefaultFullHttpRequest httpRequest) {
            final byte[] jsonBytes = JSON.toJSONBytes(request);
            
            genContentAsJSON(httpRequest, jsonBytes);
            genQueryParamsRequest(request, httpRequest);
            
            httpRequest.setMethod(HttpMethod.POST);
        }

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

        private void genQueryParamsRequest(
                final Object request, 
                final HttpRequest httpRequest) {
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
        
        private final Field[] _headerFields;
        
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

    @Override
    public void setBeanHolder(BeanHolder beanHolder) {
        this._beanHolder = beanHolder;
    }
    
    final private HttpClient _httpClient;
    private BeanHolder _beanHolder;
}
