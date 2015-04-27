package org.jocean.http.rosa.impl;

import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

import org.jocean.http.client.HttpClient;
import org.jocean.http.rosa.SignalClient;
import org.jocean.idiom.AnnotationWrapper;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.idiom.PropertyPlaceholderHelper;
import org.jocean.idiom.ReflectUtils;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Visitor2;
import org.jocean.idiom.PropertyPlaceholderHelper.PlaceholderResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.jcraft.jzlib.Deflater;
import com.jcraft.jzlib.DeflaterOutputStream;
import com.jcraft.jzlib.JZlib;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;

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
    public <REQUEST, RESPONSE> Observable<RESPONSE> start(final REQUEST request) {
        return Observable.create(new OnSubscribe<RESPONSE>() {

            @Override
            public void call(final Subscriber<? super RESPONSE> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    final URI uri = _converter.req2uri(request);
                    final InetSocketAddress remoteAddress = new InetSocketAddress(uri.getHost(), uri.getPort());
                    
                    // TODO
//                    _httpClient.sendRequest(remoteAddress, request);
                }
            }
            
        });
    }

    private final Map<Class<?>, Pair<String, Integer>> _req2pathPrefix = 
            new ConcurrentHashMap<Class<?>, Pair<String, Integer>>();
    
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

        /**
         * @param request
         * @return
         */
        private String safeGetPathPrefix(final Object request) {
            final Pair<String, Integer> pair = _req2pathPrefix.get(request.getClass());
            return (null != pair ? pair.first : null);
        }
        
        @Override
        public void visit(final Object request, final DefaultFullHttpRequest httpRequest) 
                throws Exception {
            final int features = safeGetRequestFeatures(request);
            final Class<?> httpMethod = getHttpMethod(request);
            if ( null == httpMethod 
                || GET.class.equals(httpMethod)) {
                genGetRequest(request, httpRequest);
            }
            else if (POST.class.equals(httpMethod)) {
                genPostRequest(request, httpRequest, features);
            }
        }

        /**
         * @param request
         * @return
         */
        private int safeGetRequestFeatures(final Object request) {
            final Pair<String, Integer> pair = _req2pathPrefix.get(request.getClass());
            return (null != pair ? pair.second : 0);
        }
        
        private void genPostRequest(
                final Object request,
                final DefaultFullHttpRequest httpRequest,
                final int features) {
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
        private void genContentAsCJSON(
                final DefaultFullHttpRequest httpRequest,
                final byte[] jsonBytes) {
            final OutputStream os = new ByteBufOutputStream(httpRequest.content());
            DeflaterOutputStream zos = null;
            
            try {
                zos = new DeflaterOutputStream(os, new Deflater(JZlib.Z_BEST_COMPRESSION));
                zos.write(jsonBytes);
                zos.finish();
                HttpHeaders.setContentLength(httpRequest, zos.getTotalOut());
            }
            catch (Throwable e) {
                LOG.warn("exception when compress json, detail:{}", 
                        ExceptionUtils.exception2detail(e));
            }
            finally {
                if ( null != zos ) {
                    try {
                        zos.close();
                    } catch (Exception e) {
                    }
                }
            }
            httpRequest.headers().set(HttpHeaders.Names.CONTENT_TYPE, "application/cjson");
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
