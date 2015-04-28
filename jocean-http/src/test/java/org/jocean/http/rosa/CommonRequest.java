/**
 * 
 */
package org.jocean.http.rosa;

import javax.ws.rs.QueryParam;

import com.alibaba.fastjson.annotation.JSONField;

/**
 * @author isdom
 *
 */
public class CommonRequest {

    @Override
    public String toString() {
        return "CommonRequest [_globalDeviceType=" + _globalDeviceType
                + ", _globalDeviceModel=" + _globalDeviceModel
                + ", _globalAppType=" + _globalAppType + ", _globalAppVersion="
                + _globalAppVersion + ", _globalDeviceRom=" + _globalDeviceRom
                + ", _token=" + _token + ", _accountId=" + _accountId
                + ", _questionnerId=" + _questionerId + ", _doctorId="
                + _doctorId + ", _callback=" + _callback + "]";
    }

    public String getGlobalDeviceType() {
        return this._globalDeviceType;
    }

    public String getGlobalDeviceModel() {
        return this._globalDeviceModel;
    }

    public String getGlobalAppType() {
        return this._globalAppType;
    }

    public String getGlobalAppVersion() {
        return this._globalAppVersion;
    }

    public String getGlobalDeviceRom() {
        return this._globalDeviceRom;
    }

    public String getToken() {
        return this._token;
    }

    public String getAccountId() {
        return this._accountId;
    }

    public String getQuestionerId() {
        return this._questionerId;
    }

    public String getDoctorId() {
        return this._doctorId;
    }

    public String getCallback() {
        return this._callback;
    }
    
    public void setGlobalDeviceType(final String globalDeviceType) {
        this._globalDeviceType = globalDeviceType;
    }

    public void setGlobalDeviceModel(final String globalDeviceModel) {
        this._globalDeviceModel = globalDeviceModel;
    }

    public void setGlobalAppType(final String globalAppType) {
        this._globalAppType = globalAppType;
    }

    public void setGlobalAppVersion(final String globalAppVersion) {
        this._globalAppVersion = globalAppVersion;
    }

    public void setGlobalDeviceRom(final String globalDeviceRom) {
        this._globalDeviceRom = globalDeviceRom;
    }

    public void setToken(final String token) {
        this._token = token;
    }

    public void setAccountId(final String accountId) {
        this._accountId = accountId;
    }

    public void setQuestionerId(final String questionerId) {
        this._questionerId = questionerId;
    }

    public void setDoctorId(final String doctorId) {
        this._doctorId = doctorId;
    }

    @QueryParam("globalDeviceType")
    @JSONField(name="globalDeviceType")
    protected String _globalDeviceType;
    
    @QueryParam("globalDeviceModel")
    @JSONField(name="globalDeviceModel")
    protected String _globalDeviceModel;
    
    @QueryParam("globalAppType")
    @JSONField(name="globalAppType")
    protected String _globalAppType;
    
    @QueryParam("globalAppVersion")
    @JSONField(name="globalAppVersion")
    protected String _globalAppVersion;
    
    @QueryParam("globalDeviceRom")
    @JSONField(name="globalDeviceRom")
    protected String _globalDeviceRom;
    
    @QueryParam("token")
    @JSONField(name="token")
    protected String _token;
    
    @QueryParam("accountId")
    @JSONField(name="accountId")
    protected String _accountId;
    
    @QueryParam("questionerId")
    @JSONField(name="questionerId")
    protected String _questionerId;
    
    @QueryParam("doctorId")
    @JSONField(name="doctorId")
    protected String _doctorId;

    @QueryParam("callback")
    protected String _callback;
}
