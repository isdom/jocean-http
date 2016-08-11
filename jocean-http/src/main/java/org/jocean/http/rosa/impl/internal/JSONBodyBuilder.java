/**
 * 
 */
package org.jocean.http.rosa.impl.internal;

import org.jocean.http.rosa.impl.BodyBuilder;
import org.jocean.http.rosa.impl.BodyForm;

import com.alibaba.fastjson.JSON;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;

/**
 * @author isdom
 *
 */
class JSONBodyBuilder implements BodyBuilder {

    public JSONBodyBuilder(final Object signalBean) {
        this._signalBean = signalBean;
    }
    
    @Override
    public BodyForm call() {
        abstract class AbstractBodyForm extends DefaultByteBufHolder implements BodyForm {
            public AbstractBodyForm(final ByteBuf data) {
                super(data);
            }
        }
        
        final byte[] jsonBytes = JSON.toJSONBytes(this._signalBean);
        return new AbstractBodyForm(Unpooled.wrappedBuffer(jsonBytes)) {
            @Override
            public String contentType() {
                return "application/json; charset=UTF-8";
            }

            @Override
            public int length() {
                return jsonBytes.length;
            }};
    }

    @Override
    public int ordinal() {
        return 100;
    }
    
    private final Object _signalBean;
}
