/**
 * 
 */
package org.jocean.http.rosa;

import java.util.concurrent.CountDownLatch;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.rosa.SignalClient.Attachment;
import org.jocean.http.rosa.SignalClient.ProgressiveSubscriber;
import org.jocean.http.rosa.impl.DefaultSignalClient;
import org.jocean.http.util.RxNettys;

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
     * @throws Exception 
     */
    public static void main(String[] args) throws Exception {
        final HttpClient httpClient = new DefaultHttpClient();
        final DefaultSignalClient client = new DefaultSignalClient(httpClient);
        
        client.registerRequestType(
                FetchPatientsRequest.class, 
                FetchPatientsResponse.class,
                "http://jumpbox.medtap.cn:8888");
        
        client.registerRequestType(
                AddMultiMediasToJourneyRequest.class, 
                AddMultiMediasToJourneyResponse.class,
                "http://jumpbox.medtap.cn:8888");
        
        final CountDownLatch latch = new CountDownLatch(1);
        
        {
            final FetchPatientsRequest req = new FetchPatientsRequest();
            req.setAccountId("2");
            
            client.defineInteraction(req)
                .compose(RxNettys.<FetchPatientsResponse>filterProgress())
                .subscribe(new Subscriber<FetchPatientsResponse>() {
    
                @Override
                public void onCompleted() {
                    latch.countDown();
                    System.out.println("onCompleted.");
                }
    
                @Override
                public void onError(Throwable e) {
                    latch.countDown();
                    System.out.println("onError:" + e);
                }
    
                @Override
                public void onNext(final FetchPatientsResponse response) {
                    System.out.println(response);
                }});
        }
        latch.await();
        {
            final AddMultiMediasToJourneyRequest req = new AddMultiMediasToJourneyRequest();
            req.setCaseId("120");
            req.setJourneyId("1");
            
            final Subscription subscription = 
            client.defineInteraction(req, 
                    new Attachment("/Users/isdom/Desktop/997df3df73797e91dea4853c228fcbdee36ceb8a38cc8-1vxyhE_fw236.jpeg", "image/jpeg"))
                .subscribe(new ProgressiveSubscriber<AddMultiMediasToJourneyResponse>() {
    
                @Override
                public void onCompleted() {
                    System.out.println("onCompleted.");
                }
    
                @Override
                public void onError(Throwable e) {
                    System.out.println("onError:" + e);
                }
    
                @Override
                public void onUploadProgress(long progress, long total) {
                    System.out.println("AddMultiMediasToJourneyResponse->onUploadProgress:" + progress + "/" + total);
                }

                @Override
                public void onDownloadProgress(long progress, long total) {
                    System.out.println("AddMultiMediasToJourneyResponse->onDownloadProgress:" + progress + "/" + total);
                }

                @Override
                public void onResponse(AddMultiMediasToJourneyResponse response) {
                    System.out.println(response);
                }});
            //subscription.unsubscribe();
        }
    }
}
