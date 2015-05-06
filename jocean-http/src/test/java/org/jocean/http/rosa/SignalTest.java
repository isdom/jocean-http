/**
 * 
 */
package org.jocean.http.rosa;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.OutboundFeature;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.rosa.SignalClient.Attachment;
import org.jocean.http.rosa.impl.DefaultSignalClient;

import rx.Subscriber;
import rx.Subscription;

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
                OutboundFeature.APPLY_LOGGING);
        final DefaultSignalClient client = new DefaultSignalClient(httpClient);
        
        client.registerRequestType(
                FetchPatientsRequest.class, 
                FetchPatientsResponse.class,
                "http://jumpbox.medtap.cn:8888");
        
        client.registerRequestType(
                AddMultiMediasToJourneyRequest.class, 
                AddMultiMediasToJourneyResponse.class,
                "http://jumpbox.medtap.cn:8888");
        
        
        /*
        final FetchPatientsRequest req = new FetchPatientsRequest();
        req.setAccountId("2");
        
        client.<FetchPatientsResponse>interaction(req)
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
            */
        final AddMultiMediasToJourneyRequest req = new AddMultiMediasToJourneyRequest();
        req.setCaseId("120");
        req.setJourneyId("1");
        
        final Subscription subscription = 
        client.<AddMultiMediasToJourneyResponse>defineInteraction(req, 
                new Attachment("/Users/isdom/Desktop/997df3df73797e91dea4853c228fcbdee36ceb8a38cc8-1vxyhE_fw236.jpeg", "image/jpeg"))
            .subscribe(new Subscriber<AddMultiMediasToJourneyResponse>() {

            @Override
            public void onCompleted() {
                System.out.println("onCompleted.");
            }

            @Override
            public void onError(Throwable e) {
                System.out.println("onError:" + e);
            }

            @Override
            public void onNext(final AddMultiMediasToJourneyResponse resp) {
                System.out.println(resp);
            }});
        subscription.unsubscribe();
    }

}
