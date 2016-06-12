package org.jocean.http.server.impl;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.http.server.HttpServer.HttpTrade;
import org.jocean.http.util.Nettys4Test;
import org.jocean.idiom.rx.SubscriberHolder;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import rx.Observable;
import rx.functions.Action1;
import rx.observables.ConnectableObservable;
import rx.observers.TestSubscriber;

public class DefaultHttpTradeTestCase {

    @SuppressWarnings("unused")
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTradeTestCase.class);
    
    private final String REQ_CONTENT = "testcontent";
    
    @Test
    public final void testDoOnClosedBeforeAndAfterOutboundResponse() {
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                Observable.<HttpObject>empty());
        
        final AtomicBoolean onClosed = new AtomicBoolean(false);
        trade.doOnClosed(new Action1<HttpTrade>(){
            @Override
            public void call(final HttpTrade trade) {
                onClosed.set(true);
            }});
        
        assertFalse(onClosed.get());
        assertTrue(trade.isActive());
        
        trade.outboundResponse(Observable.<HttpObject>error(new RuntimeException("ResponseError")));
        
        assertFalse(trade.isActive());
        assertTrue(onClosed.get());
    }

    @Test
    public final void testInvokeDoOnClosedWhenUnactive() {
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                Observable.<HttpObject>error(new RuntimeException("RequestError")));
        
        assertFalse(trade.isActive());
        
        final AtomicBoolean onClosed = new AtomicBoolean(false);
        trade.doOnClosed(new Action1<HttpTrade>(){
            @Override
            public void call(final HttpTrade trade) {
                onClosed.set(true);
            }});
        
        assertTrue(onClosed.get());
    }

    @Test
    public final void testTradeForCallAbortBeforeRequestPublish() throws Exception {
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Nettys4Test.buildByteBuf("testcontent"));
        
        final ConnectableObservable<HttpObject> requestObservable = 
                Observable.<HttpObject>just(request).publish();
        
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                requestObservable);
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        
        trade.inboundRequest().subscribe(reqSubscriber);
        
        trade.abort();
        assertFalse(trade.isActive());
        reqSubscriber.assertTerminalEvent();
        reqSubscriber.assertError(Exception.class);
        
        requestObservable.connect();
        reqSubscriber.assertValueCount(0);
    }

    @Test
    public final void testTradeForCallAbortAfterRequestPublish() throws Exception {
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Nettys4Test.buildByteBuf("testcontent"));
        
        final ConnectableObservable<HttpObject> requestObservable = 
                Observable.<HttpObject>just(request).publish();
        
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                requestObservable);
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        
        trade.inboundRequest().subscribe(reqSubscriber);
        
        requestObservable.connect();
        
        trade.abort();
        assertFalse(trade.isActive());
        
        reqSubscriber.assertValueCount(1);
        reqSubscriber.assertValues(request);
        reqSubscriber.assertCompleted();
        reqSubscriber.assertNoErrors();
        
    }
    
    @Test
    public final void testTradeForCallAbortAndUseInboundRequest() throws Exception {
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Nettys4Test.buildByteBuf("testcontent"));
        
        final ConnectableObservable<HttpObject> requestObservable = 
                Observable.<HttpObject>just(request).publish();
        
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                requestObservable);
        
        requestObservable.connect();
        trade.abort();
        assertFalse(trade.isActive());
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        trade.inboundRequest().subscribe(reqSubscriber);
        
        reqSubscriber.assertTerminalEvent();
        reqSubscriber.assertError(Exception.class);
        reqSubscriber.assertValueCount(0);
    }
    
    @Test
    public final void testTradeForCallAbortAfterPartRequestThenPushError() throws Exception {
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final SubscriberHolder<HttpObject> holder = new SubscriberHolder<>();
        
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                Observable.create(holder));
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        trade.inboundRequest().subscribe(reqSubscriber);
        
        assertEquals(holder.getSubscriberCount(), 1);
        
        Nettys4Test.emitHttpObjects(holder.getAt(0), request);
        Nettys4Test.emitHttpObjects(holder.getAt(0), req_contents[0]);
        
        trade.abort();
        assertFalse(trade.isActive());
        
        reqSubscriber.assertTerminalEvent();
        reqSubscriber.assertError(Exception.class);
        reqSubscriber.assertValueCount(2);
        reqSubscriber.assertValues(request, req_contents[0]);
    }
    
    @Test
    public final void testTradeForMultiSubscribeRequestOnlyOneToSource() throws Exception {
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final SubscriberHolder<HttpObject> holder = new SubscriberHolder<>();
        
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                Observable.create(holder));
        
        final TestSubscriber<HttpObject> reqSubscriber1 = new TestSubscriber<>();
        trade.inboundRequest().subscribe(reqSubscriber1);
        
        assertEquals(holder.getSubscriberCount(), 1);
        
        final TestSubscriber<HttpObject> reqSubscriber2 = new TestSubscriber<>();
        trade.inboundRequest().subscribe(reqSubscriber2);
        
        assertEquals(holder.getSubscriberCount(), 1);
        
        final TestSubscriber<HttpObject> reqSubscriber3 = new TestSubscriber<>();
        trade.inboundRequest().subscribe(reqSubscriber3);
        
        assertEquals(holder.getSubscriberCount(), 1);
        
        Nettys4Test.emitHttpObjects(holder.getAt(0), request);
        Nettys4Test.emitHttpObjects(holder.getAt(0), req_contents[0]);
        
        reqSubscriber1.assertValueCount(2);
        reqSubscriber1.assertValues(request, req_contents[0]);
        
        reqSubscriber2.assertValueCount(2);
        reqSubscriber2.assertValues(request, req_contents[0]);
        
        reqSubscriber3.assertValueCount(2);
        reqSubscriber3.assertValues(request, req_contents[0]);
    }
    
    //  3 subscriber subscribe inbound request at different time, 
    //  so push with different httpobject
    @Test
    public final void testTradeForMultiSubscribeRequestOnlyOneToSource2() throws Exception {
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final SubscriberHolder<HttpObject> holder = new SubscriberHolder<>();
        
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                Observable.create(holder));
        
        final TestSubscriber<HttpObject> reqSubscriber1 = new TestSubscriber<>();
        trade.inboundRequest().subscribe(reqSubscriber1);
        
        assertEquals(holder.getSubscriberCount(), 1);
        
        Nettys4Test.emitHttpObjects(holder.getAt(0), request);
        
        reqSubscriber1.assertValueCount(1);
        reqSubscriber1.assertValues(request);
        
        final TestSubscriber<HttpObject> reqSubscriber2 = new TestSubscriber<>();
        trade.inboundRequest().subscribe(reqSubscriber2);
        
        assertEquals(holder.getSubscriberCount(), 1);
        
        Nettys4Test.emitHttpObjects(holder.getAt(0), req_contents[0]);
        
        reqSubscriber1.assertValueCount(2);
        reqSubscriber1.assertValues(request, req_contents[0]);
        
        reqSubscriber2.assertValueCount(1);
        reqSubscriber2.assertValues(req_contents[0]);
        
        final TestSubscriber<HttpObject> reqSubscriber3 = new TestSubscriber<>();
        trade.inboundRequest().subscribe(reqSubscriber3);
        
        assertEquals(holder.getSubscriberCount(), 1);
        
        reqSubscriber1.assertValueCount(2);
        reqSubscriber1.assertValues(request, req_contents[0]);
        
        reqSubscriber2.assertValueCount(1);
        reqSubscriber2.assertValues(req_contents[0]);
        
        reqSubscriber3.assertValueCount(0);
    }
    
    @Test
    public final void testTradeForCompleteRound() throws Exception {
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Nettys4Test.buildByteBuf("test content"));
        
        final ConnectableObservable<HttpObject> requestObservable = 
                Observable.<HttpObject>just(request).publish();
        
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                requestObservable);
        
        requestObservable.connect();
        
        assertTrue(trade.isActive());
        
        final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        
        trade.outboundResponse(Observable.<HttpObject>just(response));
        
        assertFalse(trade.isActive());
    }

    @Test
    public final void testTradeForCompleteRoundWithMultiContentRequest() throws Exception {
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final ConnectableObservable<HttpObject> requestObservable = 
                Observable.<HttpObject>from(new ArrayList<HttpObject>() {
                    private static final long serialVersionUID = 1L;
                {
                    this.add(request);
                    this.addAll(Arrays.asList(req_contents));
                    this.add(LastHttpContent.EMPTY_LAST_CONTENT);
                }}).publish();
        
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                requestObservable);
        
        requestObservable.connect();
        
        assertTrue(trade.isActive());
        
        final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        
        trade.outboundResponse(Observable.<HttpObject>just(response));
        
        assertFalse(trade.isActive());
    }
    
    @Test
    public final void testTradeForRequestPartError() throws Exception {
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final ConnectableObservable<HttpObject> requestObservable = 
                Observable.concat(
                    Observable.<HttpObject>from(new ArrayList<HttpObject>() {
                        private static final long serialVersionUID = 1L;
                    {
                        this.add(request);
                        for (int idx = 0; idx < 5; idx++) {
                            this.add(req_contents[idx]);
                        }
                    }}),
                    Observable.<HttpObject>error(new RuntimeException("RequestPartError"))
                ).publish();
        
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                requestObservable);
        
        requestObservable.connect();
        
        assertFalse(trade.isActive());
    }
}
