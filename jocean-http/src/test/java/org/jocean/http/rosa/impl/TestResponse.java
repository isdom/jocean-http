/**
 * 
 */
package org.jocean.http.rosa.impl;

import com.alibaba.fastjson.annotation.JSONField;

/**
 * @author isdom
 *
 */
public class TestResponse {
    
    public TestResponse() {}
    
    public TestResponse(final String code, final String msg) {
        this._code = code;
        this._message = msg;
    }
    
    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((_code == null) ? 0 : _code.hashCode());
        result = prime * result
                + ((_message == null) ? 0 : _message.hashCode());
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        TestResponse other = (TestResponse) obj;
        if (_code == null) {
            if (other._code != null)
                return false;
        } else if (!_code.equals(other._code))
            return false;
        if (_message == null) {
            if (other._message != null)
                return false;
        } else if (!_message.equals(other._message))
            return false;
        return true;
    }

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
