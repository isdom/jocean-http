package org.jocean.http.rosa.old;

import java.util.Arrays;

import com.alibaba.fastjson.annotation.JSONField;

public class QueryMyPatientsForDoctorResponse extends CommonResponse{
    
    private DoctorPatientRelevantBean[] _patients;

    @JSONField(name="patients")
    public DoctorPatientRelevantBean[] getPatients() {
        return _patients;
    }

    @JSONField(name="patients")
    public void setPatients(final DoctorPatientRelevantBean[] patients) {
        this._patients = patients;
    }

    @Override
    public String toString() {
        return "QueryMyPatientsForDoctorResponse [_patients="
                + Arrays.toString(_patients) + ", _code=" + _code
                + ", _message=" + _message + ", _rowCount=" + _rowCount + "]";
    }

}
