/**
 * 
 */
package org.jocean.http.rosa.impl.internal;

import java.lang.reflect.Field;

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
import io.netty.handler.codec.http.QueryStringEncoder;
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
            final QueryStringEncoder encoder = new QueryStringEncoder("");
            for ( Field field : queryFields ) {
                try {
                    final Object value = field.get(this._signalBean);
                    if ( null != value ) {
                        final String paramkey = 
                            field.getAnnotation(QueryParam.class).value();
                        encoder.addParam(paramkey, String.valueOf(value));
                    }
                }
                catch (Exception e) {
                    LOG.warn("exception when get field({})'s value, detail:{}", 
                            field, ExceptionUtils.exception2detail(e));
                }
            }
            return encoder.toString().getBytes(CharsetUtil.UTF_8);
        }
        return new byte[0];
    }

    @Override
    public int ordinal() {
        return 99;
    }
    
    private final Object _signalBean;
}
