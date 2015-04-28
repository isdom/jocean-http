/**
 * 
 */
package org.jocean.http.rosa;

import com.alibaba.fastjson.annotation.JSONField;

/**
 * @author isdom
 *
 */
public class CommonResponse {
    
    @Override
    public String toString() {
        return "CommonResponse [_message=" + _message + ", _code=" + _code
                + ", _rowCount=" + _rowCount + "]";
    }
    
    public String getCode() {
        return this._code;
    }

    public void setCode(final String code) {
        this._code = code;
    }

    public String getMessage() {
        return this._message;
    }

    public void setMessage(final String msg) {
        this._message = msg;
    }

    public String getRowCount() {
        return this._rowCount;
    }

    public void setRowCount(final String rowCount) {
        this._rowCount = rowCount;
    }

    @JSONField(name="code")
    protected String _code;
    
    @JSONField(name="message")
    protected String _message;
    
    @JSONField(name="rowCount")
    protected String _rowCount;
}
