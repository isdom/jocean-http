/**
 * 
 */
package org.jocean.http.rosa.impl.internal;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;

import javax.ws.rs.QueryParam;

import org.jocean.http.rosa.impl.BodyBuilder;
import org.jocean.http.rosa.impl.BodyForm;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;

/**
 * @author isdom
 *
 */
class FormURLEncodedBodyBuilder implements BodyBuilder {

    private static final Logger LOG =
            LoggerFactory.getLogger(FormURLEncodedBodyBuilder.class);
    
    public FormURLEncodedBodyBuilder(final Object signalBean) {
        this._signalBean = signalBean;
    }
    
    @Override
    public BodyForm call() {
        abstract class AbstractBodyForm extends DefaultByteBufHolder implements BodyForm {
            public AbstractBodyForm(final ByteBuf data) {
                super(data);
            }
        }
        
        final byte[] bytes = genBodyBytes();
        return new AbstractBodyForm(Unpooled.wrappedBuffer(bytes)) {
            @Override
            public String contentType() {
                return "application/x-www-form-urlencoded";
            }

            @Override
            public int length() {
                return bytes.length;
            }};
    }

    private byte[] genBodyBytes() {
        final Field[] queryFields = 
                ReflectUtils.getAnnotationFieldsOf(this._signalBean.getClass(), QueryParam.class);
        if ( queryFields.length > 0 ) {
            final Charset charset = CharsetUtil.UTF_8;
            final StringBuilder sb = new StringBuilder();
            String splitter = "";
            for ( Field field : queryFields ) {
                try {
                    final String paramkey = 
                            field.getAnnotation(QueryParam.class).value();
                    final Object value = field.get(this._signalBean);
                    sb.append(splitter);
                    sb.append(encodeComponent(paramkey, charset));
                    sb.append('=');
                    if (value != null) {
                        sb.append(encodeComponent(value.toString(), charset));
                    }
                    splitter="&";
                }
                catch (Exception e) {
                    LOG.warn("exception when get field({})'s value, detail:{}", 
                            field, ExceptionUtils.exception2detail(e));
                }
            }
            return sb.toString().getBytes(CharsetUtil.UTF_8);
        }
        return new byte[0];
    }

    private static String encodeComponent(String s, Charset charset) {
        // TODO: Optimize me.
        try {
            return URLEncoder.encode(s, charset.name()).replace("+", "%20");
        } catch (UnsupportedEncodingException ignored) {
            throw new UnsupportedCharsetException(charset.name());
        }
    }
    
    @Override
    public int ordinal() {
        return 99;
    }
    
    private final Object _signalBean;
}
