package org.jocean.http.rosa;

import com.alibaba.fastjson.annotation.JSONField;

public class QuestionerBean {
    
    private String _questionerId;
    
    private String _questionerName;
    
    private String _sex;
    
    private String _age;
    
    private String _birthDate;
    
    private String _headPictureUrl;
    
    private ProvinceBean _province;
    
    private PatientAccountBean _patientAccount;
    
    @JSONField(name="patientAccount")
    public PatientAccountBean getPatientAccount() {
        return _patientAccount;
    }

    @JSONField(name="patientAccount")
    public void setPatientAccount(final PatientAccountBean patientAccount) {
        this._patientAccount = patientAccount;
    }

    @JSONField(name="questionerId")
    public String getQuestionerId() {
        return _questionerId;
    }

    @JSONField(name="questionerId")
    public void setQuestionerId(final String questionerId) {
        this._questionerId = questionerId;
    }

    @JSONField(name="questionerName")
    public String getQuestionerName() {
        return _questionerName;
    }

    @JSONField(name="questionerName")
    public void setQuestionerName(final String questionerName) {
        this._questionerName = questionerName;
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

    @JSONField(name="birthDate")
    public String getBirthDate() {
        return _birthDate;
    }

    @JSONField(name="birthDate")
    public void setBirthDate(final String birthDate) {
        this._birthDate = birthDate;
    }

    @JSONField(name="province")
    public ProvinceBean getProvince() {
        return _province;
    }

    @JSONField(name="province")
    public void setProvince(final ProvinceBean province) {
        this._province = province;
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
        return "QuestionerBean [_questionerId=" + _questionerId
                + ", _questionerName=" + _questionerName + ", _sex=" + _sex
                + ", _age=" + _age + ", _birthDate=" + _birthDate
                + ", _headPictureUrl=" + _headPictureUrl + ", _province="
                + _province + ", _patientAccount=" + _patientAccount + "]";
    }
    
}
