package org.jocean.http.rosa;

import com.alibaba.fastjson.annotation.JSONField;

public class GroupBean {
    
    private String _groupId;
    
    private String _groupName;

    @JSONField(name="groupId")
    public String getGroupId() {
        return _groupId;
    }

    @JSONField(name="groupId")
    public void setGroupId(String groupId) {
        this._groupId = groupId;
    }

    @JSONField(name="groupName")
    public String getGroupName() {
        return _groupName;
    }

    @JSONField(name="groupName")
    public void setGroupName(String groupName) {
        this._groupName = groupName;
    }

    @Override
    public String toString() {
        return "GroupBean [_groupId=" + _groupId + ", _groupName=" + _groupName
                + "]";
    }

}
