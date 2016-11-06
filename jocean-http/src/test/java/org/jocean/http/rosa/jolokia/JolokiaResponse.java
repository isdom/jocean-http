package org.jocean.http.rosa.jolokia;

import com.alibaba.fastjson.annotation.JSONField;

public class JolokiaResponse {

    @JSONField(name = "request")
    public JolokiaRequest getRequest() {
        return _request;
    }

    @JSONField(name = "request")
    public void setRequest(final JolokiaRequest req) {
        this._request = req;
    }

    @JSONField(name = "timestamp")
    public long getTimestamp() {
        return _timestamp;
    }

    @JSONField(name = "timestamp")
    public void setTimestamp(final long timestamp) {
        this._timestamp = timestamp;
    }

    @JSONField(name = "status")
    public int getStatus() {
        return _status;
    }

    @JSONField(name = "status")
    public void setStatus(final int status) {
        this._status = status;
    }

    @JSONField(name = "stacktrace")
    public String getStacktrace() {
        return _stacktrace;
    }

    @JSONField(name = "stacktrace")
    public void setStacktrace(final String stacktrace) {
        this._stacktrace = stacktrace;
    }

    @JSONField(name = "error_type")
    public String getErrorType() {
        return _error_type;
    }

    @JSONField(name = "error_type")
    public void setErrorType(final String error_type) {
        this._error_type = error_type;
    }

    @JSONField(name = "error")
    public String getError() {
        return _error;
    }

    @JSONField(name = "error")
    public void setError(final String error) {
        this._error = error;
    }

    protected JolokiaRequest _request;
    protected long  _timestamp;
    protected int   _status;
    protected String _stacktrace;
    protected String _error_type;
    protected String _error;

    public JolokiaResponse() {
        super();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("JolokiaResponse [request=").append(_request)
                .append(", timestamp=").append(_timestamp).append(", status=")
                .append(_status).append("]");
        return builder.toString();
    }
}