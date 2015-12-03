package org.jocean.http.rosa.impl;

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

import org.jocean.idiom.AnnotationWrapper;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.PropertyPlaceholderHelper;
import org.jocean.idiom.PropertyPlaceholderHelper.PlaceholderResolver;
import org.jocean.idiom.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

import io.netty.buffer.ByteBufOutputStream;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;

final class RequestProcessor {
    private static final Logger LOG =
            LoggerFactory.getLogger(RequestProcessor.class);

    RequestProcessor(final Class<?> reqCls) {
        this._queryFields = ReflectUtils.getAnnotationFieldsOf(reqCls, QueryParam.class);
        this._headerFields = ReflectUtils.getAnnotationFieldsOf(reqCls, HeaderParam.class);
        
        this._pathSuffix = getPathValueOf(reqCls);
        
        this._pathparamResolver = genPlaceholderResolverOf(reqCls, PathParam.class);

        this._pathparamReplacer = 
                ( null != this._pathparamResolver ? new PropertyPlaceholderHelper("{", "}") : null);
    }

    public String req2path(final Object request, final String pathPrefix) {
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
    
    public String pathSuffix() {
        return this._pathSuffix;
    }

    public DefaultFullHttpRequest genHttpRequest(final URI uri, final Object request) {
        final DefaultFullHttpRequest httpRequest = genFullHttpRequest(uri);

        final Class<?> httpMethod = getHttpMethod(request.getClass());
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
    
    private static String safeConcatPath(final String pathPrefix, final String pathSuffix) {
        if ( null == pathSuffix ) {
            return pathPrefix;
        }
        if ( null == pathPrefix ) {
            return pathSuffix;
        }
        return pathPrefix + pathSuffix;
    }
    
    private static String getPathValueOf(final Class<?> cls) {
        final Path path = cls.getAnnotation(Path.class);
        return (null != path ? path.value() : null);
    }
    
    private static DefaultFullHttpRequest genFullHttpRequest(final URI uri) {
        // Prepare the HTTP request.
        final String host = uri.getHost() == null ? "localhost" : uri.getHost();

        final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getRawPath());
        request.headers().set(HttpHeaders.Names.HOST, host);

        return request;
    }
    
    private static Class<?> getHttpMethod(final Class<?> reqCls) {
        final AnnotationWrapper wrapper = 
                reqCls.getAnnotation(AnnotationWrapper.class);
        if ( null != wrapper ) {
            return wrapper.value();
        }
        else {
            return null;
        }
    }
    
    private final Field[] _queryFields;
    
    private final Field[] _headerFields;
    
    private final String _pathSuffix;
    
    private final PropertyPlaceholderHelper _pathparamReplacer;
    
    private final PlaceholderResolver _pathparamResolver;
    
    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("RequestProcessor [queryFields=")
                .append(Arrays.toString(_queryFields))
                .append(", headerFields=")
                .append(Arrays.toString(_headerFields))
                .append(", pathSuffix=").append(_pathSuffix).append("]");
        return builder.toString();
    }
};