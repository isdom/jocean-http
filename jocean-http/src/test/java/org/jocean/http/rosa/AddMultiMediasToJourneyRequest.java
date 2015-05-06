package org.jocean.http.rosa;

import javax.ws.rs.POST;
import javax.ws.rs.Path;

import org.jocean.idiom.AnnotationWrapper;

import com.alibaba.fastjson.annotation.JSONField;

@AnnotationWrapper(POST.class)
@Path("/yjy_psm/addMultiMediasToJourney")
public class AddMultiMediasToJourneyRequest extends CommonRequest{

    @JSONField(name="journeyId")
    public String getJourneyId() {
        return this._journeyId;
    }
    
    @JSONField(name="journeyId")
    public void setJourneyId(final String journeyId) {
        this._journeyId = journeyId;
    }

    @JSONField(name="caseId")
    public String getCaseId() {
        return _caseId;
    }

    @JSONField(name="caseId")
    public void setCaseId(final String caseId) {
        this._caseId = caseId;
    }
    
    private String _journeyId;// 病程id
    
    private String _caseId;// 病历id

    @Override
    public String toString() {
        return "AddMultiMediasToJourneyRequest [_journeyId=" + _journeyId
                + ", _caseId=" + _caseId + ", _globalDeviceType="
                + _globalDeviceType + ", _globalDeviceModel="
                + _globalDeviceModel + ", _globalAppType=" + _globalAppType
                + ", _globalAppVersion=" + _globalAppVersion
                + ", _globalDeviceRom=" + _globalDeviceRom + ", _token="
                + _token + ", _accountId=" + _accountId + ", _questionerId="
                + _questionerId + ", _doctorId=" + _doctorId + ", _callback="
                + _callback + "]";
    }
}
