package org.jocean.http.rosa.impl.internal;

import org.jocean.http.Feature;

public class RosaProfiles {
    
    static final Feature ENABLE_SETMETHOD = new SetMethodPreprocessor();
    
    static final Feature ENABLE_SETPATH = new SetPathPreprocessor();
    
    static final Feature ENABLE_HEADERPARAM = new HeaderParamPreprocessor();

    static final Feature ENABLE_QUERYPARAM = new QueryParamPreprocessor();

    static final Feature ENABLE_DEFAULTBODY = new DefaultBodyPreprocessor();
    
    public static final Feature[] _DEFAULT_PROFILE = new Feature[]{
            RosaProfiles.ENABLE_SETMETHOD,
            RosaProfiles.ENABLE_SETPATH,
            RosaProfiles.ENABLE_QUERYPARAM,
            RosaProfiles.ENABLE_HEADERPARAM,
            RosaProfiles.ENABLE_DEFAULTBODY,
    };
}
