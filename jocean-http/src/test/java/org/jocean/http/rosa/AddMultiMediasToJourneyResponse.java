package org.jocean.http.rosa;

import java.util.Arrays;

import com.alibaba.fastjson.annotation.JSONField;

public class AddMultiMediasToJourneyResponse extends CommonResponse{

    @JSONField(name = "multiMedias")
    public MultiMediaBean[] getMultiMedias() {
        return _multiMedias;
    }

    @JSONField(name = "multiMedias")
    public void setMultiMedias(final MultiMediaBean[] multiMedias) {
        this._multiMedias = multiMedias;
    }

    private MultiMediaBean[] _multiMedias;//

    @Override
    public String toString() {
        return "AddMultiMediasToJourneyResponse [_multiMedias="
                + Arrays.toString(_multiMedias) + ", _code=" + _code
                + ", _message=" + _message + ", _rowCount=" + _rowCount + "]";
    }
}
