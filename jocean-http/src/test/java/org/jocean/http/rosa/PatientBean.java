package org.jocean.http.rosa;

import com.alibaba.fastjson.annotation.JSONField;

public class PatientBean {
    
    private String _patientId;

    private String _accountId;

    private String _userName;
    
    private String _birthDate;

    private String _sex;

    private String _age;

    private ProvinceBean _province;

    private String _healthcareType;
    
    private String _headPictureUrl;

    @JSONField(name="birthDate")
    public String getBirthDate() {
        return _birthDate;
    }

    @JSONField(name="birthDate")
    public void setBirthDate(final String birthDate) {
        this._birthDate = birthDate;
    }

    @JSONField(name="patientId")
    public String getPatientId() {
        return _patientId;
    }

    @JSONField(name="patientId")
    public void setPatientId(final String patientId) {
        this._patientId = patientId;
    }

    @JSONField(name="accountId")
    public String getAccountId() {
        return _accountId;
    }

    @JSONField(name="accountId")
    public void setAccountId(final String accountId) {
        this._accountId = accountId;
    }

    @JSONField(name="userName")
    public String getUserName() {
        return _userName;
    }

    @JSONField(name="userName")
    public void setUserName(final String userName) {
        this._userName = userName;
    }

    @JSONField(name="sex")
    public String getSex() {
        return _sex;
    }

    @JSONField(name="sex")
    public void setSex(final String sex) {
        this._sex = sex;
    }

    @JSONField(name="age")
    public String getAge() {
        return _age;
    }

    @JSONField(name="age")
    public void setAge(final String age) {
        this._age = age;
    }

    @JSONField(name="province")
    public ProvinceBean getProvince() {
        return _province;
    }

    @JSONField(name="province")
    public void setProvince(final ProvinceBean province) {
        this._province = province;
    }

    @JSONField(name="healthcareType")
    public String getHealthcareType() {
        return _healthcareType;
    }

    @JSONField(name="healthcareType")
    public void setHealthcareType(final String healthcareType) {
        this._healthcareType = healthcareType;
    }

    @JSONField(name="headPictureUrl")
    public String getHeadPictureUrl() {
        return _headPictureUrl;
    }

    @JSONField(name="headPictureUrl")
    public void setHeadPictureUrl(final String headPictureUrl) {
        this._headPictureUrl = headPictureUrl;
    }
    
    @Override
    public String toString() {
        return "PatientBean [_patientId=" + _patientId + ", _accountId="
                + _accountId + ", _userName=" + _userName + ", _sex=" + _sex
                + ", _age=" + _age + ", _province=" + _province
                + ", _healthcareType=" + _healthcareType + ", _headPictureUrl="
                + _headPictureUrl + "]";
    }

}