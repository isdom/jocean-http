package org.jocean.http.rosa;

import com.alibaba.fastjson.annotation.JSONField;

public class MultiMediaBean {
    
    @JSONField(name="multiMediaType")
    public String getMultiMediaType() {
        return _multiMediaType;
    }
    
    @JSONField(name="multiMediaType")
    public void setMultiMediaType(final String multiMediaType) {
        this._multiMediaType = multiMediaType;
    }
    
    @JSONField(name="multiMediaUrl")
    public String getMultiMediaUrl() {
        return _multiMediaUrl;
    }
    
    @JSONField(name="multiMediaUrl")
    public void setMultiMediaUrl(final String multiMediaUrl) {
        this._multiMediaUrl = multiMediaUrl;
    }
    
    @JSONField(name="multiMediaName")
    public String getMultiMediaName() {
        return _multiMediaName;
    }
    
    @JSONField(name="multiMediaName")
    public void setMultiMediaName(final String multiMediaName) {
        this._multiMediaName = multiMediaName;
    }
    
    @JSONField(name="multiMediaId")
    public String getMultiMediaId() {
        return _multiMediaId;
    }
    
    @JSONField(name="multiMediaId")
    public void setMultiMediaId(final String multiMediaId) {
        this._multiMediaId = multiMediaId;
    }

    @JSONField(name="frontCoverUrl")
    public String getFrontCoverUrl() {
        return _frontCoverUrl;
    }
    
    @JSONField(name="frontCoverUrl")
    public void setFrontCoverUrl(final String frontCoverUrl) {
        this._frontCoverUrl = frontCoverUrl;
    }

    private String _multiMediaId;//多媒体Id

    private String _multiMediaType;//多媒体类型
    
    private String _multiMediaUrl;//多媒体链接
    
    private String _multiMediaName;//多媒体名称

    private String _frontCoverUrl;// 封面

    @Override
    public String toString() {
        return "MultiMediaBean [_multiMediaId=" + _multiMediaId
                + ", _multiMediaType=" + _multiMediaType + ", _multiMediaUrl="
                + _multiMediaUrl + ", _multiMediaName=" + _multiMediaName
                + ", _frontCoverUrl=" + _frontCoverUrl + "]";
    }
}
