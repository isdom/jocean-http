/**
 * 
 */
package org.jocean.http.rosa;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.OutboundFeature;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.rosa.impl.DefaultSignalClient;

import rx.Subscriber;

/**
 * @author isdom
 *
 */
public class SignalTest {

    /**
     * @param args
     * @throws Exception 
     */
    public static void main(String[] args) {
        final HttpClient httpClient = new DefaultHttpClient(
                OutboundFeature.APPLY_LOGGING,
                OutboundFeature.APPLY_CONTENT_DECOMPRESSOR);
        final DefaultSignalClient client = new DefaultSignalClient(httpClient);
        
        client.registerRequestType(FetchPatientsRequest.class, 
                "http://jumpbox.medtap.cn:8888", OutboundFeature.APPLY_LOGGING);
        
        final FetchPatientsRequest req = new FetchPatientsRequest();
        req.setAccountId("2");
        
        client.start(req, FetchPatientsResponse.class)
        .subscribe(new Subscriber<FetchPatientsResponse>() {

            @Override
            public void onCompleted() {
                System.out.println("onCompleted.");
            }

            @Override
            public void onError(Throwable e) {
                System.out.println(e);
            }

            @Override
            public void onNext(final FetchPatientsResponse resp) {
                System.out.println(resp);
            }});
    }

}
