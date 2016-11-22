/**
 * 
 */
package org.jocean.http.rosa.impl.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import rx.functions.Action2;

/**
 * @author isdom
 *
 */
class JSONBodyBuilder implements BodyBuilder, FeaturesAware {

    private static final Object DUMMY_OBJ = new Object();

    private static final Logger LOG =
            LoggerFactory.getLogger(JSONBodyBuilder.class);
    
    private static final Action2<JSONObject, JSONObject> MERGE_JSONOBJ = 
        new Action2<JSONObject, JSONObject>() {
            @Override
            public void call(final JSONObject org, final JSONObject tomerge) {
                org.putAll(tomerge);
            }};
        
    private static final Action2<JSONArray, JSONArray> MERGE_JSONARRAY = 
            new Action2<JSONArray, JSONArray>() {
                @Override
                public void call(final JSONArray org, final JSONArray tomerge) {
                    org.addAll(tomerge);
                }};
                        
    public JSONBodyBuilder(final Object signalBean) {
        this._signalBean = signalBean;
    }
    
    @Override
    public void setFeatures(final Feature[] features) {
        final JSONSource[] jsons = InterfaceUtils.selectIncludeType(
                JSONSource.class, (Object[])features);
        if ( null!=jsons && jsons.length > 0) {
            this._jsons.addAll(Arrays.asList(jsons));
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
        if (this._jsons.isEmpty()) {
            return JSON.toJSONBytes(this._signalBean);
        } else {
            return JSON.toJSONBytes(
                    mergeWithSources(JSON.toJSON(this._signalBean), this._jsons));
        }
    }

    private Object mergeWithSources(final Object jsonbase, final List<JSONSource> sources) {
        if (sources.isEmpty()) {
            return jsonbase;
        } else {
            if (jsonbase instanceof JSONObject) {
                final JSONObject jsonobj = (JSONObject)jsonbase;
                
                if (jsonobj.isEmpty()) {
                    return mergeWithSources(parseSource(sources.get(0)), sources.subList(1, sources.size()));
                } else {
                    return mergeAllJSON(jsonobj, sources, MERGE_JSONOBJ);
                }
            } else if (jsonbase instanceof JSONArray) {
                final JSONArray jsonarray = (JSONArray)jsonbase;
                
                return mergeAllJSON(jsonarray, sources, MERGE_JSONARRAY);
            } else {
                LOG.warn("JSON is not either JSONObject nor JSONArray, but {}, just return null", 
                        jsonbase.getClass());
                return DUMMY_OBJ;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T mergeAllJSON(
            final T jsonbase,
            final List<JSONSource> jsons, 
            final Action2<T, T> domerge) {
        for (JSONSource jsonsrc : jsons) {
            try {
                final Object parsed = parseSource(jsonsrc);
                if (parsed.getClass().equals(jsonbase.getClass())) {
                    domerge.call(jsonbase, (T)parsed);
                }
            } catch (Exception e) {
                LOG.warn("exception when JSON.parse, detail:{}",
                        ExceptionUtils.exception2detail(e));
            }
        }
        return jsonbase;
    }

    private static Object parseSource(final JSONSource jsonsrc) {
        return JSON.parse(new String(jsonsrc.content(), CharsetUtil.UTF_8));
    }

    @Override
    public int ordinal() {
        return 100;
    }
    
    private final Object _signalBean;
    private final List<JSONSource> _jsons = new ArrayList<>();
}
