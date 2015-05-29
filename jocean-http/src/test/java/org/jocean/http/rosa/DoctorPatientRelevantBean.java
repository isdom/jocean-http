package org.jocean.http.rosa;

import com.alibaba.fastjson.annotation.JSONField;

public class DoctorPatientRelevantBean {
    
    private String _questionerAttentionStatus; // 0 未关注   1 我关注的医生   2 我的医生
    
    private String _doctorAttentionStatus; // 0 未关注   1 我的粉丝  2 我的患者
    
    private String _verifyMessage;// 验证消息
    
    private String _remark;
    
    private String _sequence;
    
    private GroupBean _group;
    
    private boolean _canSeeCaseHistory;
    
    private QuestionerBean _questioner;

    @JSONField(name="questioner")
    public QuestionerBean getQuestioner() {
        return _questioner;
    }

    @JSONField(name="questioner")
    public void setQuestioner(QuestionerBean questioner) {
        this._questioner = questioner;
    }

    @JSONField(name="canSeeCaseHistory")
    public boolean isCanSeeCaseHistory() {
        return _canSeeCaseHistory;
    }

    @JSONField(name="canSeeCaseHistory")
    public void setCanSeeCaseHistory(boolean canSeeCaseHistory) {
        this._canSeeCaseHistory = canSeeCaseHistory;
    }

    @JSONField(name="sequence")
    public String getSequence() {
        return _sequence;
    }

    @JSONField(name="sequence")
    public void setSequence(String sequence) {
        this._sequence = sequence;
    }

    @JSONField(name="remark")
    public String getRemark() {
        return _remark;
    }

    @JSONField(name="remark")
    public void setRemark(String remark) {
        this._remark = remark;
    }

    @JSONField(name="questionerAttentionStatus")
    public String getQuestionerAttentionStatus() {
        return _questionerAttentionStatus;
    }

    @JSONField(name="questionerAttentionStatus")
    public void setQuestionerAttentionStatus(String questionerAttentionStatus) {
        this._questionerAttentionStatus = questionerAttentionStatus;
    }

    @JSONField(name="doctorAttentionStatus")
    public String getDoctorAttentionStatus() {
        return _doctorAttentionStatus;
    }

    @JSONField(name="doctorAttentionStatus")
    public void setDoctorAttentionStatus(String doctorAttentionStatus) {
        this._doctorAttentionStatus = doctorAttentionStatus;
    }

    @JSONField(name="verifyMessage")
    public String getVerifyMessage() {
        return _verifyMessage;
    }

    @JSONField(name="verifyMessage")
    public void setVerifyMessage(String verifyMessage) {
        this._verifyMessage = verifyMessage;
    }

    @JSONField(name="group")
    public GroupBean getGroup() {
        return _group;
    }

    @JSONField(name="group")
    public void setGroup(GroupBean group) {
        this._group = group;
    }

    @Override
    public String toString() {
        return "DoctorPatientRelevantBean [_questionerAttentionStatus="
                + _questionerAttentionStatus + ", _doctorAttentionStatus="
                + _doctorAttentionStatus + ", _verifyMessage=" + _verifyMessage
                + ", _remark=" + _remark + ", _sequence=" + _sequence
                + ", _group=" + _group + ", _canSeeCaseHistory="
                + _canSeeCaseHistory + ", _questioner=" + _questioner + "]";
    }
    
}
