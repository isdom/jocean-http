package org.jocean.rpc.bean;

import javax.ws.rs.HeaderParam;

import org.jocean.rpc.annotation.StatusCodeAware;

import com.alibaba.fastjson.annotation.JSONField;

public interface BasicResponse {
    @JSONField(serialize = false)
    public int getStatusCode();

    @JSONField(deserialize = false)
    @StatusCodeAware
    public void setStatusCode(final int code);

    @JSONField(serialize = false)
    public String getResponseServer();

    @JSONField(deserialize = false)
    @HeaderParam("Server")
    public void setResponseServer(final String server);

    @JSONField(serialize = false)
    public String getResponseDate();

    @JSONField(deserialize = false)
    @HeaderParam("Date")
    public void setResponseDate(final String date);

    @JSONField(serialize = false)
    public String getContentType();

    @JSONField(deserialize = false)
    @HeaderParam("Content-Type")
    public void setContentType(final String contentType);

    @JSONField(serialize = false)
    public String getContentLength();

    @JSONField(deserialize = false)
    @HeaderParam("Content-Length")
    public void setContentLength(final String contentLength);
}
