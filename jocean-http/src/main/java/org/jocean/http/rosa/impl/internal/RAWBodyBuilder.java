/**
 * 
 */
package org.jocean.http.rosa.impl.internal;

import java.lang.reflect.Field;

import javax.ws.rs.BeanParam;

import org.jocean.http.rosa.impl.BodyBuilder;
import org.jocean.http.rosa.impl.BodyForm;
import org.jocean.idiom.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;

/**
 * @author isdom
 *
 */
class RAWBodyBuilder implements BodyBuilder {

    @SuppressWarnings("unused")
    private static final Logger LOG =
            LoggerFactory.getLogger(RAWBodyBuilder.class);
    
    public static RAWBodyBuilder hasRAWBody(final Object signalBean) {
        if (null != signalBean) {
            final Field[] beanFields = 
                    ReflectUtils.getAnnotationFieldsOf(signalBean.getClass(), BeanParam.class);
            for (Field field : beanFields) {
                if (field.getType().equals(byte[].class)) {
                    return new RAWBodyBuilder(signalBean, field);
                }
            }
        }
        
        return null;
    }
    
    public RAWBodyBuilder(final Object signalBean, final Field field) {
        this._signalBean = signalBean;
        this._bodyField = field;
    }
    
    @Override
    public BodyForm call() {
        abstract class AbstractBodyForm extends DefaultByteBufHolder implements BodyForm {
            public AbstractBodyForm(final ByteBuf data) {
                super(data);
            }
        }
        
        try {
            final byte[] bodyBytes = (byte[])this._bodyField.get(this._signalBean);
            return new AbstractBodyForm(Unpooled.wrappedBuffer(bodyBytes)) {
                @Override
                public String contentType() {
                    return "multipart/form=data; ";
                }

                @Override
                public int length() {
                    return bodyBytes.length;
                }};
        } catch (Exception e) {
            return null;
        }
    }


    @Override
    public int ordinal() {
        return 90;
    }
    
    private final Object _signalBean;
    private final Field _bodyField;
}
