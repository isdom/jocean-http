/**
 * 
 */
package org.jocean.http.rosa.impl.internal;

import org.jocean.http.rosa.impl.BodyBuilder;
import org.jocean.http.rosa.impl.BodyForm;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;

/**
 * @author isdom
 *
 */
class XMLBodyBuilder implements BodyBuilder {

    private static final Logger LOG =
            LoggerFactory.getLogger(XMLBodyBuilder.class);
    
    public XMLBodyBuilder(final Object signalBean) {
        this._signalBean = signalBean;
    }
    
    @Override
    public BodyForm call() {
        abstract class AbstractBodyForm extends DefaultByteBufHolder implements BodyForm {
            public AbstractBodyForm(final ByteBuf data) {
                super(data);
            }
        }
        
        final byte[] xmlBytes = genXMLBytes();
        return new AbstractBodyForm(Unpooled.wrappedBuffer(xmlBytes)) {
            @Override
            public String contentType() {
                return "application/xml; charset=UTF-8";
            }

            @Override
            public int length() {
                return xmlBytes.length;
            }};
    }

    private byte[] genXMLBytes() {
        try {
            final XmlMapper mapper = new XmlMapper();
            return mapper.writeValueAsBytes(this._signalBean);
        } catch (JsonProcessingException e) {
            LOG.warn("exception when convert {} to xml, detail: {}", this._signalBean, ExceptionUtils.exception2detail(e));
            return new byte[0];
        }
    }

    @Override
    public int ordinal() {
        return 100;
    }
    
    private final Object _signalBean;
}
