/**
 * 
 */
package org.jocean.http.rosa.impl;

import com.alibaba.fastjson.annotation.JSONField;

/**
 * @author isdom
 *
 */
public class OkResponse {
    
    @Override
    public String toString() {
        return "[code=" + _code +  ",message=" + _message + "]";
    }
    
    @JSONField(name="code")
    public String getCode() {
        return this._code;
    }

    @JSONField(name="code")
    public void setCode(final String code) {
        this._code = code;
    }

    @JSONField(name="message")
    public String getMessage() {
        return this._message;
    }

    @JSONField(name="message")
    public void setMessage(final String msg) {
        this._message = msg;
    }

    protected String _code;
    
    protected String _message;
}
