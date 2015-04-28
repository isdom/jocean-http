package org.jocean.http.rosa;

import java.util.Arrays;

import com.alibaba.fastjson.annotation.JSONField;

public class ProvinceBean {
    
    @JSONField(name="provinceId")
    public String getProvinceId() {
        return _provinceId;
    }

    @JSONField(name="provinceId")
    public void setProvinceId(final String provinceId) {
        this._provinceId = provinceId;
    }

    @JSONField(name="provinceName")
    public String getProvinceName() {
        return _provinceName;
    }

    @JSONField(name="provinceName")
    public void setProvinceName(final String provinceName) {
        this._provinceName = provinceName;
    }

    @JSONField(name="isMunicipalities")
    public boolean getIsMunicipalities() {
        return _isMunicipalities;
    }

    @JSONField(name="isMunicipalities")
    public void setIsMunicipalities(final boolean isMunicipalities) {
        this._isMunicipalities = isMunicipalities;
    }

    @JSONField(name="city")
    public CityBean getCity() {
        return _city;
    }

    @JSONField(name="city")
    public void setCity(final CityBean city) {
        this._city = city;
    }

    @JSONField(name="cities")
    public CityBean[] getCities() {
        return _cities;
    }

    @JSONField(name="cities")
    public void setCities(final CityBean[] cities) {
        this._cities = cities;
    }

    private String _provinceId;
    
    private String _provinceName;

    private boolean _isMunicipalities;

    private CityBean _city;
    
    private CityBean[] _cities;

    @Override
    public String toString() {
        return "ProvinceBean [_provinceId=" + _provinceId + ", _provinceName="
                + _provinceName + ", _isMunicipalities=" + _isMunicipalities
                + ", _city=" + _city + ", _cities=" + Arrays.toString(_cities)
                + "]";
    }
}
