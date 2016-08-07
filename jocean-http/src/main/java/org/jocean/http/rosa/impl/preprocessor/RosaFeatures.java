package org.jocean.http.rosa.impl.preprocessor;

import org.jocean.http.Feature;

public class RosaFeatures {
    
    public static final Feature ENABLE_SETMETHOD = new SetMethodPreprocessor();
    
    public static final Feature ENABLE_SETURI = new SetUriPreprocessor();
    
    public static final Feature ENABLE_HEADERPARAM = new HeaderParamPreprocessor();

    public static final Feature ENABLE_QUERYPARAM = new QueryParamPreprocessor();

    public static final Feature ENABLE_DEFAULTBODY = new DefaultBodyPreprocessor();
}
