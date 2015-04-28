package org.jocean.http.rosa;

import javax.ws.rs.POST;

import org.jocean.idiom.AnnotationWrapper;

@AnnotationWrapper(POST.class)
public class FetchPatientsRequest extends CommonRequest{

    @Override
    public String toString() {
        return "FetchPatientsRequest [_globalDeviceType=" + _globalDeviceType
                + ", _globalDeviceModel=" + _globalDeviceModel
                + ", _globalAppType=" + _globalAppType + ", _globalAppVersion="
                + _globalAppVersion + ", _globalDeviceRom=" + _globalDeviceRom
                + ", _token=" + _token + ", _accountId=" + _accountId
                + ", _questionerId=" + _questionerId + ", _doctorId="
                + _doctorId + ", _callback=" + _callback + "]";
    }

}
