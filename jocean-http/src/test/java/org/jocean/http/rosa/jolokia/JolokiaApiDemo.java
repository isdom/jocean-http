package org.jocean.http.rosa.jolokia;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.POST;

import org.jocean.http.Feature;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.rosa.SignalClient;
import org.jocean.http.rosa.impl.DefaultSignalClient;

public class JolokiaApiDemo {
    private static ReadAttrResponse queryAttrValue(
            final SignalClient signal,
            final URI jolokiauri,
            final String objname) {
        final JolokiaRequest req = new JolokiaRequest();
        req.setType("read");
        req.setMBean(objname);
        
        final ReadAttrResponse resp = 
                
        signal.interaction().request(req).feature( 
                Feature.ENABLE_LOGGING,
                Feature.ENABLE_COMPRESSOR,
                new SignalClient.UsingUri(jolokiauri),
                new SignalClient.UsingMethod(POST.class),
                new SignalClient.DecodeResponseBodyAs(ReadAttrResponse.class)
                )
        .<ReadAttrResponse>build()
        .timeout(10, TimeUnit.SECONDS)
        .toBlocking().single();
        return resp;
    }

    public static void main(String[] args) throws Exception {
        final SignalClient signal = new DefaultSignalClient(new DefaultHttpClient());
        final URI uri = new URI("http://192.168.8.8/jolokia/");
        queryAttrValue(signal, uri, "java.lang:type=Threading");
        Thread.sleep(100);
        queryAttrValue(signal, uri, "java.lang:type=Threading");
        Thread.sleep(100);
        System.exit(0);
    }

}
