package org.jocean.http.rosa;

import java.util.Arrays;

import com.alibaba.fastjson.annotation.JSONField;

public class FetchPatientsResponse extends CommonResponse{
    
    private PatientBean[] _patients;

    @JSONField(name="patients")
    public PatientBean[] getPatients() {
        return _patients;
    }

    @JSONField(name="patients")
    public void setPatients(final PatientBean[] patients) {
        this._patients = patients;
    }

    @Override
    public String toString() {
        return "FetchPatientResponse [_patients=" + Arrays.toString(_patients)
                + ", _code=" + _code + ", _message=" + _message
                + ", _rowCount=" + _rowCount + "]";
    }
}
