/**
 * 
 */
package org.jocean.http.rosa.impl.internal;

import org.jocean.http.Feature;
import org.jocean.http.Feature.FeaturesAware;
import org.jocean.http.rosa.impl.BodyBuilder;
import org.jocean.http.rosa.impl.BodyForm;
import org.jocean.http.rosa.impl.internal.Facades.JSONSource;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;

/**
 * @author isdom
 *
 */
class JSONBodyBuilder implements BodyBuilder, FeaturesAware {

    private static final Logger LOG =
            LoggerFactory.getLogger(JSONBodyBuilder.class);
    
    public JSONBodyBuilder(final Object signalBean) {
        this._signalBean = signalBean;
    }
    
    @Override
    public void setFeatures(final Feature[] features) {
        final JSONSource[] jsons = InterfaceUtils.selectIncludeType(
                JSONSource.class, (Object[])features);
        if ( null!=jsons && jsons.length > 0) {
            for (JSONSource json : jsons) {
                try {
                    this._jsonobj.putAll(JSON.parseObject(new String(json.content(), CharsetUtil.UTF_8)));
                } catch (Exception e) {
                    LOG.warn("exception when JSON.parseObject, detail:{}",
                            ExceptionUtils.exception2detail(e));
                }
            }
        }
    }
    
    @Override
    public BodyForm call() {
        abstract class AbstractBodyForm extends DefaultByteBufHolder implements BodyForm {
            public AbstractBodyForm(final ByteBuf data) {
                super(data);
            }
        }
        
        final byte[] jsonBytes = genJSONBytes();
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

    private byte[] genJSONBytes() {
        if (!this._jsonobj.isEmpty()) {
            return JSON.toJSONBytes(((JSONObject)JSON.toJSON(this._signalBean)).fluentPutAll(this._jsonobj));
        } else {
            return JSON.toJSONBytes(this._signalBean);
        }
    }

    @Override
    public int ordinal() {
        return 100;
    }
    
    private final Object _signalBean;
    private final JSONObject _jsonobj = new JSONObject();
}
