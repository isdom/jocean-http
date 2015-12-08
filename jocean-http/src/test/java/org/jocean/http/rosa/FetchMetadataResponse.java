package org.jocean.http.rosa;

import java.util.Arrays;

import com.alibaba.fastjson.annotation.JSONField;

public class FetchMetadataResponse  {

    private String [] _journeyTypes;//所有病程类型
    
    private String[] _myMoods;
    
    @JSONField(name = "myMoods")
    public String[] getMyMoods() {
        return _myMoods;
    }

    @JSONField(name = "myMoods")
    public void setMyMoods(final String[] myMoods) {
        this._myMoods = myMoods;
    }

    @JSONField(name = "journeyTypes")
    public String[] getJourneyTypes() {
        return _journeyTypes;
    }

    @JSONField(name = "journeyTypes")
    public void setJourneyTypes(final String[] journeyTypes) {
        this._journeyTypes = journeyTypes;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("FetchMetadataResponse [_journeyTypes=")
                .append(Arrays.toString(_journeyTypes)).append(", _myMoods=")
                .append(Arrays.toString(_myMoods)).append("]");
        return builder.toString();
    }
}
