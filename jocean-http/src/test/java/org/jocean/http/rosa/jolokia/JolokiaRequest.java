package org.jocean.http.rosa.jolokia;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.annotation.JSONField;

public class JolokiaRequest {
    
    public static class Config {
        
        @JSONField(name="canonicalNaming")
        public boolean getCanonicalNaming() {
            return false;
        }
    }
    
    @JSONField(name="type")
    public String getType() {
        return this._type;
    }

    @JSONField(name="type")
    public void setType(final String type) {
        this._type = type;
    }

    @JSONField(name="mbean")
    public String getMBean() {
        return this._mbean;
    }

    @JSONField(name="mbean")
    public void setMBean(final String mbean) {
        this._mbean = mbean;
    }

    @JSONField(name="attribute")
    public String getAttribute() {
        return this._attribute;
    }

    @JSONField(name="attribute")
    public void setAttribute(final String attribute) {
        this._attribute = attribute;
    }
    
    @JSONField(name="path")
    public String getPath() {
        return this._path;
    }

    @JSONField(name="path")
    public void setPath(final String path) {
        this._path = path;
    }
    
    @JSONField(name="config")
    public Config getConfig() {
        return new Config();
    }
    
    @JSONField(name="operation")
    public String getOperation() {
        return this._operation;
    }

    @JSONField(name="operation")
    public void setOperation(final String operation) {
        this._operation = operation;
    }
    
    @JSONField(name="arguments")
    public void setArguments(final JSONArray arguments) {
        this._arguments = arguments;
    }
    
    @JSONField(name="arguments")
    public JSONArray getArguments() {
        return this._arguments;
    }

    private String _type;
    private String _mbean;
    private String _path;
    private String _attribute;
    private String _operation;
    private JSONArray _arguments;
}
