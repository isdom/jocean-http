/**
 * 
 */
package org.jocean.http.rosa;

import static org.jocean.http.Feature.ENABLE_COMPRESSOR;
import static org.jocean.http.Feature.ENABLE_LOGGING;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.Outbound;
import org.jocean.http.client.impl.AbstractChannelCreator;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.client.impl.TestChannelPool;
import org.jocean.http.rosa.SignalClient.Attachment;
import org.jocean.http.rosa.SignalClient.ProgressiveSubscriber;
import org.jocean.http.rosa.impl.DefaultSignalClient;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        final TestChannelPool pool = new TestChannelPool(1);
        final HttpClient httpClient = new DefaultHttpClient(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap
                .group(new NioEventLoopGroup(1))
                .channel(NioSocketChannel.class);
            }}, pool
            , 
            ENABLE_LOGGING, 
            ENABLE_COMPRESSOR);
        final DefaultSignalClient client = new DefaultSignalClient(httpClient);
        
        client.registerRequestType(
                FetchPatientsRequest.class, 
                FetchPatientsResponse.class,
                "http://jumpbox.medtap.cn:8888");
        
        client.registerRequestType(
                QueryMyPatientsForDoctorRequest.class, 
                QueryMyPatientsForDoctorResponse.class,
                "http://api.iplusmed.com");
        
        client.registerRequestType(
                AddMultiMediasToJourneyRequest.class, 
                AddMultiMediasToJourneyResponse.class,
//                "http://jumpbox.medtap.cn:8888",
                "http://127.0.0.1:9090",
                Outbound.ENABLE_MULTIPART,
                new Outbound.ENABLE_PROGRESSIVE(100)
                );
        
        /*
        {
            final QueryMyPatientsForDoctorRequest req = new QueryMyPatientsForDoctorRequest();
            req.setDoctorId("8510");
            
            client.defineInteraction(req)
                .compose(RxNettys.<QueryMyPatientsForDoctorResponse>filterProgress())
                .subscribe(new Subscriber<QueryMyPatientsForDoctorResponse>() {
    
                @Override
                public void onCompleted() {
                    LOG.debug("FetchPatientsRequest: onCompleted.");
                }
    
                @Override
                public void onError(Throwable e) {
                    LOG.debug("FetchPatientsRequest: onError: {}", ExceptionUtils.exception2detail(e));
                }
    
                @Override
                public void onNext(final QueryMyPatientsForDoctorResponse response) {
                    LOG.debug("QueryMyPatientsForDoctorRequest: onNext: {}", response);
                }});
        }
        
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
        pool.awaitRecycleChannels();
        */
        {
            final AddMultiMediasToJourneyRequest req = new AddMultiMediasToJourneyRequest();
            req.setCaseId("120");
            req.setJourneyId("1");
            
            final Subscription subscription = 
            client.defineInteraction(req, 
                    new Attachment("/Users/isdom/Desktop/997df3df73797e91dea4853c228fcbdee36ceb8a38cc8-1vxyhE_fw236.jpeg", "image/jpeg"))
//                    new Attachment("/Users/isdom/Pictures/2015-05-23-胜利实验一(1)班临安杨溪村亲子活动/11850523/DSC06443.JPG", "image/jpeg"))
                    
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
            //  TODO, why invoke onCompleted Event? not onError, check
            //  TO BE CONTINUE, 2015-05-13
        }
    }
}
