package org.jocean.http.rosa.jolokia;

import com.alibaba.fastjson.annotation.JSONField;

public class StringArrayResponse extends JolokiaResponse {
    @JSONField(name="value")
    public String[] getValue() {
        return _value;
    }

    @JSONField(name="value")
    public void setValue(final String[] value) {
        this._value = value;
    }

    private String[] _value;
}
