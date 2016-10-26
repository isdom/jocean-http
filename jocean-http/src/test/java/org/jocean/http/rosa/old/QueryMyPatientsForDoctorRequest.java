package org.jocean.http.rosa.old;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;

import org.jocean.idiom.AnnotationWrapper;

import com.alibaba.fastjson.annotation.JSONField;

@AnnotationWrapper(POST.class)
@Path("/yjy/queryMyPatientsForDoctor")
public class QueryMyPatientsForDoctorRequest extends CommonRequest{
    
    @QueryParam("groupId")
    private String _groupId;//分组

    @JSONField(name="groupId")
    public String getGroupId() {
        return _groupId;
    }

    @JSONField(name="groupId")
    public void setGroupId(final String groupId) {
        this._groupId = groupId;
    }

    @Override
    public String toString() {
        return "QueryMyPatientsForDoctorRequest [_groupId=" + _groupId
                + ", _globalDeviceType=" + _globalDeviceType
                + ", _globalDeviceModel=" + _globalDeviceModel
                + ", _globalAppType=" + _globalAppType + ", _globalAppVersion="
                + _globalAppVersion + ", _globalDeviceRom=" + _globalDeviceRom
                + ", _token=" + _token + ", _accountId=" + _accountId
                + ", _questionerId=" + _questionerId + ", _doctorId="
                + _doctorId + ", _callback=" + _callback + "]";
    }
}
