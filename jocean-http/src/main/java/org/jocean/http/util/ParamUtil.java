package org.jocean.http.util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;

import javax.ws.rs.HeaderParam;
import javax.ws.rs.QueryParam;

import org.jocean.idiom.Beans;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

public class ParamUtil {
    
    private static final Logger LOG
        = LoggerFactory.getLogger(ParamUtil.class);
    
    private ParamUtil() {
        throw new IllegalStateException("No instances!");
    }
    
    public static Action1<HttpRequest> injectQueryParams(final Object bean) {
        return new Action1<HttpRequest>() {
            @Override
            public void call(final HttpRequest request) {
                request2QueryParams(request, bean);
            }};
    }
    
    public static void request2QueryParams(final HttpRequest request, final Object bean) {
        final Field[] fields = ReflectUtils.getAnnotationFieldsOf(bean.getClass(), QueryParam.class);
        if (null != fields) {
            final QueryStringDecoder decoder = new QueryStringDecoder(request.uri());

            for (Field field : fields) {
                final String key = field.getAnnotation(QueryParam.class).value();
                if (!"".equals(key) && null != decoder.parameters()) {
                    // for case: QueryParam("demo")
                    injectParamValue(decoder.parameters().get(key), bean, field);
                }
                if ("".equals(key)) {
                    // for case: QueryParam(""), means fill with entire query string
                    injectValueToField(rawQuery(request.uri()), bean, field);
                }
            }
        }
    }
    
    public static String rawQuery(final String uri) {
        final int pos = uri.indexOf('?');
        if (-1 != pos) {
            return uri.substring(pos+1);
        } else {
            return null;
        }
    }
    
    public static Action1<HttpRequest> injectHeaderParams(final Object bean) {
        return new Action1<HttpRequest>() {
            @Override
            public void call(final HttpRequest request) {
                request2HeaderParams(request, bean);
            }};
    }
    
    public static void request2HeaderParams(final HttpRequest request, final Object bean) {
        final Field[] fields = ReflectUtils.getAnnotationFieldsOf(bean.getClass(), HeaderParam.class);
        if (null != fields) {
            for (Field field : fields) {
                injectParamValue(request.headers().getAll(field.getAnnotation(HeaderParam.class).value()), 
                    bean,
                    field
                );
            }
        }
    }
    
    private static void injectParamValue(
            final List<String> values,
            final Object obj,
            final Field field) {
        if (null != values && values.size() > 0) {
            injectValueToField(values.get(0), obj, field);
        }
    }
    
    public static <T> T getAsType(final List<String> list, final Class<T> type) {
        if (null != list && list.size() > 0) {
            return Beans.fromString(list.get(0), type);
        } else {
            return null;
        }
    }
    
    /**
     * @param value
     * @param obj
     * @param field
     */
    private static void injectValueToField(
            final String value,
            final Object obj,
            final Field field) {
        if (null != value) {
            try {
                field.set(obj, Beans.fromString(value, field.getType()));
            } catch (Exception e) {
                LOG.warn("exception when set obj({}).{} with value({}), detail:{} ",
                        obj, field.getName(), value, ExceptionUtils.exception2detail(e));
            }
        }
    }

    public static <T> Func1<Func0<FullHttpRequest>, T> decodeXmlContentAs(final Class<T> type) {
        return new Func1<Func0<FullHttpRequest>, T>() {
            @Override
            public T call(final Func0<FullHttpRequest> getfhr) {
                final FullHttpRequest fhr = getfhr.call();
                if (null != fhr) {
                    try {
                        return parseContentAsXml(fhr.content(), type);
                    } finally {
                        fhr.release();
                    }
                }
                return null;
            }};
    }
    
    public static <T> T parseContentAsXml(final ByteBuf buf, final Class<T> type) {
        final XmlMapper mapper = new XmlMapper();
        mapper.addHandler(new DeserializationProblemHandler() {
            @Override
            public boolean handleUnknownProperty(final DeserializationContext ctxt, final JsonParser p,
                    final JsonDeserializer<?> deserializer, final Object beanOrClass, final String propertyName)
                    throws IOException {
                LOG.warn("UnknownProperty [{}], just skip", propertyName);
                p.skipChildren();
                return true;
            }});
        try {
            return mapper.readValue(contentAsInputStream(buf), type);
        } catch (Exception e) {
            LOG.warn("exception when parse xml, detail: {}",
                    ExceptionUtils.exception2detail(e));
            return null;
        }
    }
    
    public static <T> Func1<Func0<FullHttpRequest>, T> decodeJsonContentAs(final Class<T> type) {
        return new Func1<Func0<FullHttpRequest>, T>() {
            @Override
            public T call(final Func0<FullHttpRequest> getfhr) {
                final FullHttpRequest fhr = getfhr.call();
                if (null != fhr) {
                    try {
                        return parseContentAsJson(fhr.content(), type);
                    } finally {
                        fhr.release();
                    }
                }
                return null;
            }};
    }
    
    public static <T> T parseContentAsJson(final ByteBuf buf, final Class<T> type) {
        try {
            return JSON.parseObject(contentAsInputStream(buf), type);
        } catch (IOException e) {
            LOG.warn("exception when parse {} as json, detail: {}",
                    buf, ExceptionUtils.exception2detail(e));
            return null;
        }
    }
    
    private static InputStream contentAsInputStream(final ByteBuf buf) {
        return new ByteBufInputStream(buf.slice());
    }
}
