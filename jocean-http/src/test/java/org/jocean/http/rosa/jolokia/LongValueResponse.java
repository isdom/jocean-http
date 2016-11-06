package org.jocean.http.rosa.jolokia;

import com.alibaba.fastjson.annotation.JSONField;

public class LongValueResponse extends JolokiaResponse {
    
    @JSONField(name="value")
    public long getValue() {
        return _value;
    }

    @JSONField(name="value")
    public void setValue(final long value) {
        this._value = value;
    }
    
    private long _value;

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("LongValueResponse [_value=").append(_value)
                .append(", super=").append(super.toString()).append("]");
        return builder.toString();
    }
}
