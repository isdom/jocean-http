package org.jocean.netty;


import javax.ws.rs.Consumes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * CopyObject
 *
 * 响应元素(Response Elements)
 * 表 1. 响应元素
 * 名称  类型  描述
 * CopyObjectResult    字符串 CopyObject的结果。
 * 默认值：无

 * ETag    字符串 目标Object的ETag值。
 * 父元素：CopyObjectResult

 * LastModified    字符串 目标Object最后更新时间。
 * 父元素：CopyObjectResult
 *
 */
@Consumes({"application/xml","text/xml"})
@JacksonXmlRootElement(localName="CopyObjectResult")
public class CopyObjectResult {

    //  ETag: 目标Object的ETag值。
    private String _etag;

    //  LastModified: 目标Object最后更新时间。
    private String _lastModified;

    @JacksonXmlProperty(localName="ETag")
    public String getETag() {
        return this._etag;
    }

    @JacksonXmlProperty(localName="ETag")
    public void setETag(final String etag) {
        this._etag = etag;
    }

    @JacksonXmlProperty(localName="LastModified")
    public String getLastModified() {
        return this._lastModified;
    }

    @JacksonXmlProperty(localName="LastModified")
    public void setLastModified(final String lastModified) {
        this._lastModified = lastModified;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("CopyObjectResult [etag=").append(_etag).append(", lastModified=").append(_lastModified)
                .append("]");
        return builder.toString();
    }

    public static void main(final String[] args) throws Exception {
        final String xmlsrc =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<CopyObjectResult xmlns=\"http://doc.oss-cn-hangzhou.aliyuncs.com\">"
                + "<LastModified>Fri, 24 Feb 2012 07:18:48 GMT</LastModified>"
                + "<ETag>\"5B3C1A2E053D763E1B002CC607C5A0FE\"</ETag>"
                + "</CopyObjectResult>"
        ;
        final ObjectMapper mapper = new XmlMapper();

        final CopyObjectResult result = mapper.readValue(xmlsrc, CopyObjectResult.class);
        System.out.println("result:" + result);
        System.out.println("as Xml:" + mapper.writeValueAsString(result) );

    }
}
