package org.jocean.http.rosa;

import com.alibaba.fastjson.annotation.JSONField;

public class CityBean {
    
    @JSONField(name="cityId")
    public String getCityId() {
        return _cityId;
    }
    
    @JSONField(name="cityId")
    public void setCityId(final String cityId) {
        this._cityId = cityId;
    }
    
    @JSONField(name="cityName")
    public String getCityName() {
        return _cityName;
    }
    
    @JSONField(name="cityName")
    public void setCityName(final String cityName) {
        this._cityName = cityName;
    }
    
    @JSONField(name="isCapital")
    public boolean getIsCapital() {
        return _isCapital;
    }
    
    @JSONField(name="isCapital")
    public void setIsCapital(final boolean isCapital) {
        this._isCapital = isCapital;
    }

    private String _cityId;  

    private String _cityName; 
    
    private boolean _isCapital;

    @Override
    public String toString() {
        return "CityBean [_cityId=" + _cityId + ", _cityName=" + _cityName
                + ", _isCapital=" + _isCapital + "]";
    }
}
