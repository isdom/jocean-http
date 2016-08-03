package org.jocean.http.rosa.impl;

import javax.ws.rs.POST;
import javax.ws.rs.Path;

import org.jocean.idiom.AnnotationWrapper;

@AnnotationWrapper(POST.class)
@Path("/yjy_common/fetchMetadata")
public class FetchMetadataRequest {
}
