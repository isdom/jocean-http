package org.jocean.http.rosa.impl.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

import org.jocean.http.Feature;
import org.jocean.http.rosa.impl.RequestChanger;
import org.jocean.http.rosa.impl.RequestPreprocessor;
import org.jocean.http.rosa.impl.internal.Facades.PathSource;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.PropertyPlaceholderHelper;
import org.jocean.idiom.ReflectUtils;
import org.jocean.idiom.PropertyPlaceholderHelper.PlaceholderResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpRequest;

class SetPathPreprocessor implements Feature, RequestPreprocessor {

    private static final class UriSetter implements RequestChanger, FeaturesAware {
        private final Object _signalBean;
        private final PlaceholderResolver _pathparamResolver;
        private final PropertyPlaceholderHelper _pathparamReplacer;
        
        private String _path = null;

        private UriSetter(final Object signalBean,
                final PlaceholderResolver pathparamResolver,
                final PropertyPlaceholderHelper pathparamReplacer) {
            this._signalBean = signalBean;
            this._pathparamResolver = pathparamResolver;
            this._pathparamReplacer = pathparamReplacer;
        }

        @Override
        public void setFeatures(final Feature[] features) {
            final PathSource[] paths = InterfaceUtils.selectIncludeType(
                    PathSource.class, (Object[])features);
            if ( null!=paths && paths.length > 0) {
                this._path = paths[0].path();
            }
        }
        
        @Override
        public void call(final HttpRequest request) {
            request.setUri(genUriAsString(request.uri(),
                    _signalBean, 
                    _pathparamResolver,
                    _pathparamReplacer));
        }

        private String genUriAsString(
                final String prefix,
                final Object signalBean, 
                final PlaceholderResolver pathparamResolver,
                final PropertyPlaceholderHelper pathparamReplacer) {
            //  当 rawpath 结果为 null 时, 避免在 fullpath 后空字符串
            final String rawpath = getPath(null != signalBean ? signalBean.getClass() : Object.class);
            final String fullPath = prefix + (null != rawpath ? rawpath : "");
            if ( null != pathparamReplacer ) {
                return pathparamReplacer.replacePlaceholders(
                        signalBean,
                        fullPath, 
                        pathparamResolver, 
                        null);
            }
            else {
                return fullPath;
            }
        }

        private String getPath(final Class<?> type) {
            if (null != this._path) {
                return this._path;
            } else {
                final Path path = type.getAnnotation(Path.class);
                return (null != path ? path.value() : null);
            }
        }
        
        @Override
        public int ordinal() {
            return 10;
        }
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(SetPathPreprocessor.class);
    
    @Override
    public RequestChanger call(final Object signalBean) {
        if (null == signalBean) {
            return new UriSetter(null, null, null);
        } else {
            final PlaceholderResolver pathparamResolver = genPlaceholderResolverOf(
                    signalBean.getClass(), PathParam.class);
    
            final PropertyPlaceholderHelper pathparamReplacer = 
                    ( null != pathparamResolver ? new PropertyPlaceholderHelper("{", "}") : null);
            
            return new UriSetter(signalBean, pathparamResolver, pathparamReplacer);
        }
    }

    private static PlaceholderResolver genPlaceholderResolverOf(
            final Class<?> cls, 
            final Class<? extends Annotation> annotationCls) {
        final Map<String, Field> pathparam2field = 
            genPath2Field(ReflectUtils.getAnnotationFieldsOf(cls, annotationCls));
        
        final Map<String, Method> pathparam2method = 
            genPath2Method(cls, ReflectUtils.getAnnotationMethodsOf(cls, annotationCls));
        
        if ( null == pathparam2field 
           && null ==  pathparam2method) {
            return null;
        }
        
        return new PropertyPlaceholderHelper.PlaceholderResolver() {
            @Override
            public String resolvePlaceholder(final Object obj,
                    final String placeholder) {
                if (null != pathparam2field) {
                    final Field field = pathparam2field
                            .get(placeholder);
                    if (null != field) {
                        try {
                            return String.valueOf(field.get(obj));
                        } catch (Exception e) {
                            LOG.error("exception when get value for ({}).{}, detail: {}",
                                    obj, field.getName(),
                                    ExceptionUtils.exception2detail(e));
                        }
                    }
                }

                if ( null != pathparam2method ) {
                    final Method method = pathparam2method
                            .get(placeholder);
                    if (null != method) {
                        try {
                            return String.valueOf(method.invoke(obj));
                        } catch (Exception e) {
                            LOG.error("exception when invoke ({}).{}, detail: {}",
                                    obj, method.getName(),
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
    
    private static Map<String, Field> genPath2Field(
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
    
    private static Map<String, Method> genPath2Method(
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
}
