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
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Subscriber;
import rx.Subscription;

/**
 * @author isdom
 *
 */
public class SignalTest {

    private static final Logger LOG =
            LoggerFactory.getLogger(SignalTest.class);
    
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
                    LOG.debug("FetchPatientsRequest: onCompleted.");
                }
    
                @Override
                public void onError(Throwable e) {
                    latch.countDown();
                    LOG.debug("FetchPatientsRequest: onError: {}", ExceptionUtils.exception2detail(e));
                }
    
                @Override
                public void onNext(final FetchPatientsResponse response) {
                    LOG.debug("FetchPatientsRequest: onNext: {}", response);
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
                    LOG.debug("AddMultiMediasToJourneyRequest: onCompleted.");
                }
    
                @Override
                public void onError(Throwable e) {
                    LOG.debug("AddMultiMediasToJourneyRequest: onError: {}", ExceptionUtils.exception2detail(e));
                }
    
                @Override
                public void onUploadProgress(long progress, long total) {
                    LOG.debug("AddMultiMediasToJourneyRequest->onUploadProgress: {}/{}", progress, total);
                }

                @Override
                public void onDownloadProgress(long progress, long total) {
                    LOG.debug("AddMultiMediasToJourneyRequest->onDownloadProgress: {}/{}", progress, total);
                }

                @Override
                public void onResponse(AddMultiMediasToJourneyResponse response) {
                    LOG.debug("AddMultiMediasToJourneyRequest: onNext: {}", response);
                }});
//            subscription.unsubscribe();
        }
    }
}
