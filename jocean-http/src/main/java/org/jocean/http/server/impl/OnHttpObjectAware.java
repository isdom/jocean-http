package org.jocean.http.server.impl;

import org.jocean.http.util.Nettys.OnHttpObject;

public interface OnHttpObjectAware {
    public void setOnHttpObject(final OnHttpObject onHttpObject);
}
