package org.jocean.http.rosa;

import com.alibaba.fastjson.annotation.JSONField;

public class PatientAccountBean {
    
    private String _accountId;
    
    private boolean _hasCaseHistory;
    
    private boolean _hasDiary;

    @JSONField(name="hasCaseHistory")
    public boolean isHasCaseHistory() {
        return _hasCaseHistory;
    }

    @JSONField(name="hasCaseHistory")
    public void setHasCaseHistory(final boolean hasCaseHistory) {
        this._hasCaseHistory = hasCaseHistory;
    }

    @JSONField(name="hasDiary")
    public boolean isHasDiary() {
        return _hasDiary;
    }

    @JSONField(name="hasDiary")
    public void setHasDiary(final boolean hasDiary) {
        this._hasDiary = hasDiary;
    }

    @JSONField(name="accountId")
    public String getAccountId() {
        return _accountId;
    }

    @JSONField(name="accountId")
    public void setAccountId(final String accountId) {
        this._accountId = accountId;
    }

    @Override
    public String toString() {
        return "PatientAccountBean [_accountId=" + _accountId
                + ", _hasCaseHistory=" + _hasCaseHistory + ", _hasDiary="
                + _hasDiary + "]";
    }

}
