package org.jocean.rpc;

public interface ParamAware {
    public void setParam(final Class<?> paramType, final String paramName, final Object value);
}
