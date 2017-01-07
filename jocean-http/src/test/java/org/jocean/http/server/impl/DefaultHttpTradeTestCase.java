package org.jocean.http.server.impl;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys4Test;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.rx.RxActions;
import org.jocean.idiom.rx.RxSubscribers;
import org.jocean.idiom.rx.SubscriberHolder;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.observables.ConnectableObservable;
import rx.observers.TestSubscriber;

public class DefaultHttpTradeTestCase {

    private static final byte[] EMPTY_BYTES = new byte[0];

    @SuppressWarnings("unused")
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTradeTestCase.class);
    
    private final String REQ_CONTENT = "testcontent";
    
    @Test
    public final void testDoOnClosedBeforeAndAfterOutboundResponse() {
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                Observable.<HttpObject>empty());
        
        final AtomicBoolean onClosed = new AtomicBoolean(false);
        trade.addCloseHook(new Action1<HttpTrade>(){
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
        trade.addCloseHook(new Action1<HttpTrade>(){
            @Override
            public void call(final HttpTrade trade) {
                onClosed.set(true);
            }});
        
        assertTrue(onClosed.get());
    }

    @Test
    public final void testTradeForCallAbortBeforeRequestPublish() {
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
    public final void testTradeForCallAbortAfterRequestPublish() {
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
    public final void testTradeForCallAbortAndUseInboundRequest() {
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
    public final void testTradeForCallAbortAfterPartRequestThenPushError() {
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
    public final void testTradeForMultiSubscribeRequestOnlyOneToSource() {
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
    public final void testTradeForMultiSubscribeRequestOnlyOneToSource2() {
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
    public final void testTradeForCompleteRound() {
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
    public final void testTradeForCompleteRoundWithMultiContentRequest() {
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
    public final void testTradeForRequestPartError() {
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final List<HttpObject> part_req = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(request);
            for (int idx = 0; idx < 5; idx++) {
                this.add(req_contents[idx]);
            }
        }};
        final RuntimeException error = new RuntimeException("RequestPartError");
        final ConnectableObservable<HttpObject> requestObservable = 
                Observable.concat(
                    Observable.<HttpObject>from(part_req),
                    Observable.<HttpObject>error(error)
                ).publish();
        
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                requestObservable);
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        trade.inboundRequest().subscribe(reqSubscriber);
        
        requestObservable.connect();
        
        assertFalse(trade.isActive());
        reqSubscriber.assertTerminalEvent();
        //  java.lang.AssertionError: Exceptions differ; expected: java.lang.RuntimeException: RequestPartError, 
        //      actual: java.lang.RuntimeException: trade unactived
        reqSubscriber.assertError(Exception.class);
        reqSubscriber.assertNotCompleted();
        reqSubscriber.assertValues(part_req.toArray(new HttpObject[0]));
    }

    @Test
    public final void testTradeForFirstResponseErrorThenRetry() {
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                Observable.<HttpObject>empty());
        
        final AtomicReference<Throwable> onError = new AtomicReference<>();
        assertTrue(trade.isActive());
        
        final RuntimeException error = new RuntimeException("ResponseError");
        final Subscription subscription = outputResponseWithOnError(trade, Observable.<HttpObject>error(error), onError);
    
        assertTrue(trade.isActive());
        assertSame(error, onError.get());
        assertTrue(subscription.isUnsubscribed());
        
        final AtomicReference<Throwable> onError2 = new AtomicReference<>();
        final Subscription subscription2 = outputResponseWithOnError(trade, Observable.<HttpObject>empty(), onError2);
        assertNotNull(subscription2);
        assertNull(onError2.get());
        assertFalse(trade.isActive());
    }

    private Subscription outputResponseWithOnError(final HttpTrade trade,
            final Observable<HttpObject> response,
            final AtomicReference<Throwable> onError) {
        return trade.outboundResponse(response, new Action1<Throwable>() {
            @Override
            public void call(Throwable e) {
                onError.set(e);
            }});
    }
    
    public static Channel dummyChannel(final AtomicReference<Object> output) {
        return new Channel() {

            @Override
            public <T> Attribute<T> attr(AttributeKey<T> key) {
                return null;
            }

            @Override
            public int compareTo(Channel o) {
                return 0;
            }

            @Override
            public EventLoop eventLoop() {
                return null;
            }

            @Override
            public Channel parent() {
                return null;
            }

            @Override
            public ChannelConfig config() {
                return null;
            }

            @Override
            public boolean isOpen() {
                return false;
            }

            @Override
            public boolean isRegistered() {
                return false;
            }

            @Override
            public boolean isActive() {
                return false;
            }

            @Override
            public ChannelMetadata metadata() {
                return null;
            }

            @Override
            public SocketAddress localAddress() {
                return null;
            }

            @Override
            public SocketAddress remoteAddress() {
                return null;
            }

            @Override
            public ChannelFuture closeFuture() {
                return null;
            }

            @Override
            public boolean isWritable() {
                return false;
            }

            @Override
            public Unsafe unsafe() {
                return null;
            }

            @Override
            public ChannelPipeline pipeline() {
                return null;
            }

            @Override
            public ByteBufAllocator alloc() {
                return null;
            }

            @Override
            public ChannelPromise newPromise() {
                return null;
            }

            @Override
            public ChannelProgressivePromise newProgressivePromise() {
                return null;
            }

            @Override
            public ChannelFuture newSucceededFuture() {
                return null;
            }

            @Override
            public ChannelFuture newFailedFuture(Throwable cause) {
                return null;
            }

            @Override
            public ChannelPromise voidPromise() {
                return null;
            }

            @Override
            public ChannelFuture bind(SocketAddress localAddress) {
                return null;
            }

            @Override
            public ChannelFuture connect(SocketAddress remoteAddress) {
                return null;
            }

            @Override
            public ChannelFuture connect(SocketAddress remoteAddress,
                    SocketAddress localAddress) {
                return null;
            }

            @Override
            public ChannelFuture disconnect() {
                return null;
            }

            @Override
            public ChannelFuture close() {
                return null;
            }

            @Override
            public ChannelFuture deregister() {
                return null;
            }

            @Override
            public ChannelFuture bind(SocketAddress localAddress,
                    ChannelPromise promise) {
                return null;
            }

            @Override
            public ChannelFuture connect(SocketAddress remoteAddress,
                    ChannelPromise promise) {
                return null;
            }

            @Override
            public ChannelFuture connect(SocketAddress remoteAddress,
                    SocketAddress localAddress, ChannelPromise promise) {
                return null;
            }

            @Override
            public ChannelFuture disconnect(ChannelPromise promise) {
                return null;
            }

            @Override
            public ChannelFuture close(ChannelPromise promise) {
                return null;
            }

            @Override
            public ChannelFuture deregister(ChannelPromise promise) {
                return null;
            }

            @Override
            public Channel read() {
                return null;
            }

            @Override
            public ChannelFuture write(Object msg) {
                output.set(msg);
                return null;
            }

            @Override
            public ChannelFuture write(Object msg, ChannelPromise promise) {
                output.set(msg);
                return null;
            }

            @Override
            public Channel flush() {
                return null;
            }

            @Override
            public ChannelFuture writeAndFlush(Object msg,
                    ChannelPromise promise) {
                output.set(msg);
                return null;
            }

            @Override
            public ChannelFuture writeAndFlush(Object msg) {
                output.set(msg);
                return null;
            }
            
            @Override
            public <T> boolean hasAttr(AttributeKey<T> key) {
                return false;
            }

            @Override
            public ChannelId id() {
                return null;
            }

            @Override
            public long bytesBeforeUnwritable() {
                return 0;
            }

            @Override
            public long bytesBeforeWritable() {
                return 0;
            }};
    }
    
    @Test
    public final void testTradeForReplaceResponseSuccess() {
        final AtomicReference<Object> output = new AtomicReference<>();
        final HttpTrade trade = new DefaultHttpTrade(dummyChannel(output), 
                Observable.<HttpObject>empty());
        
        assertTrue(trade.isActive());
        
        final SubscriberHolder<HttpObject> subsholder1 = new SubscriberHolder<>();
        final Subscription subscription1 = trade.outboundResponse(Observable.create(subsholder1));
    
        assertTrue(trade.isActive());
        assertNotNull(subscription1);
        assertEquals(1, subsholder1.getSubscriberCount());
        
        final SubscriberHolder<HttpObject> subsholder2 = new SubscriberHolder<>();
        final Subscription subscription2 = trade.outboundResponse(Observable.create(subsholder2));
        
        assertTrue(trade.isActive());
        assertNotNull(subscription2);
        assertEquals(1, subsholder2.getSubscriberCount());
        
        final DefaultHttpRequest req1 = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        Nettys4Test.emitHttpObjects(subsholder1.getAt(0), req1);
        assertNull(output.get());
        
        final DefaultHttpRequest req2 = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        Nettys4Test.emitHttpObjects(subsholder2.getAt(0), req2);
        assertSame(req2, output.get());
    }

    @Test
    public final void testTradeForReplaceResponseFailure() {
        final AtomicReference<Object> output = new AtomicReference<>();
        final HttpTrade trade = new DefaultHttpTrade(dummyChannel(output), 
                Observable.<HttpObject>empty());
        
        assertTrue(trade.isActive());
        
        final SubscriberHolder<HttpObject> subsholder1 = new SubscriberHolder<>();
        final Subscription subscription1 = trade.outboundResponse(Observable.create(subsholder1));
    
        assertTrue(trade.isActive());
        assertNotNull(subscription1);
        assertEquals(1, subsholder1.getSubscriberCount());
        
        final DefaultHttpRequest req1 = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        Nettys4Test.emitHttpObjects(subsholder1.getAt(0), req1);
        assertSame(req1, output.get());
        
        final SubscriberHolder<HttpObject> subsholder2 = new SubscriberHolder<>();
        final Subscription subscription2 = trade.outboundResponse(Observable.create(subsholder2));
        
        assertTrue(trade.isActive());
        assertNull(subscription2);
        assertEquals(0, subsholder2.getSubscriberCount());
    }

    @Test
    public final void testTradeForReplaceResponseSuccessThenInvokeOnCompletedOnFirstResponse() {
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                Observable.<HttpObject>empty());
        
        assertTrue(trade.isActive());
        
        final SubscriberHolder<HttpObject> subsholder1 = new SubscriberHolder<>();
        final Subscription subscription1 = trade.outboundResponse(Observable.create(subsholder1));
    
        assertTrue(trade.isActive());
        assertNotNull(subscription1);
        assertEquals(1, subsholder1.getSubscriberCount());
        
        final SubscriberHolder<HttpObject> subsholder2 = new SubscriberHolder<>();
        final Subscription subscription2 = trade.outboundResponse(Observable.create(subsholder2));
        
        assertTrue(trade.isActive());
        assertNotNull(subscription2);
        assertEquals(1, subsholder2.getSubscriberCount());
        
        subsholder1.getAt(0).onCompleted();
        assertTrue(trade.isActive());
        
        subsholder2.getAt(0).onCompleted();
        assertFalse(trade.isActive());
    }
    
    @Test
    public final void testTradeForReplaceResponseSuccessThenInvokeOnErrorOnFirstResponse() {
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                Observable.<HttpObject>empty());
        
        assertTrue(trade.isActive());
        
        final SubscriberHolder<HttpObject> subsholder1 = new SubscriberHolder<>();
        final Subscription subscription1 = trade.outboundResponse(Observable.create(subsholder1));
    
        assertTrue(trade.isActive());
        assertNotNull(subscription1);
        assertEquals(1, subsholder1.getSubscriberCount());
        
        final SubscriberHolder<HttpObject> subsholder2 = new SubscriberHolder<>();
        final Subscription subscription2 = trade.outboundResponse(Observable.create(subsholder2));
        
        assertTrue(trade.isActive());
        assertNotNull(subscription2);
        assertEquals(1, subsholder2.getSubscriberCount());
        
        subsholder1.getAt(0).onError(new RuntimeException());
        assertTrue(trade.isActive());
        
        subsholder2.getAt(0).onError(new RuntimeException());
        assertFalse(trade.isActive());
    }
    
    @Test
    public final void testTradeForResponseAfterAbort() {
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                Observable.<HttpObject>empty());
        
        trade.abort();
        assertFalse(trade.isActive());
        
        final SubscriberHolder<HttpObject> subsholder = new SubscriberHolder<>();
        final Subscription subscription = trade.outboundResponse(Observable.create(subsholder));
        
        assertNull(subscription);
        assertEquals(0, subsholder.getSubscriberCount());
    }
    
    @Test
    public final void testTradeForReadyOutboundResponseAfterResponseOnNext() {
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                Observable.<HttpObject>empty());
        
        assertTrue(trade.isActive());
        
        assertTrue(trade.readyforOutboundResponse());
        
        final SubscriberHolder<HttpObject> subsholder1 = new SubscriberHolder<>();
        final Subscription subscription1 = trade.outboundResponse(Observable.create(subsholder1));
    
        assertNotNull(subscription1);
        assertTrue(trade.readyforOutboundResponse());
        
        final DefaultHttpRequest req1 = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        Nettys4Test.emitHttpObjects(subsholder1.getAt(0), req1);
        
        assertFalse(trade.readyforOutboundResponse());
    }
    
    @Test
    public final void testTradeForReadyOutboundResponseAfterResponseOnCompleted() {
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                Observable.<HttpObject>empty());
        
        assertTrue(trade.isActive());
        
        assertTrue(trade.readyforOutboundResponse());
        
        final SubscriberHolder<HttpObject> subsholder1 = new SubscriberHolder<>();
        final Subscription subscription1 = trade.outboundResponse(Observable.create(subsholder1));
    
        assertNotNull(subscription1);
        assertTrue(trade.readyforOutboundResponse());
        
        subsholder1.getAt(0).onCompleted();
        
        assertFalse(trade.readyforOutboundResponse());
    }
    
    
    @Test
    public final void testTradeForCompleteRoundAndWithCacheOperator() {
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
        
        final HttpMessageHolder holder = new HttpMessageHolder(0);
        final Observable<? extends HttpObject> inbound = 
            trade.addCloseHook(RxActions.<HttpTrade>toAction1(holder.release()))
            .inboundRequest()
            .compose(holder.assembleAndHold());
        
        final Observable<? extends HttpObject> cached = inbound.cache();
        //  force cached to subscribe upstream
        cached.subscribe(RxSubscribers.nopOnNext(), RxSubscribers.nopOnError());
        
        //  Error, will cause : exception when invoke visitor(org.jocean.http.util.RxNettys$2@722c517b), detail: 
        //      java.lang.ClassCastException: io.netty.handler.codec.http.DefaultHttpRequest cannot be cast to 
        //      io.netty.handler.codec.http.HttpContent
        //  inbound.subscribe();    double subscribe holder.assembleAndHold()
        
        requestObservable.connect();
        
        final FullHttpRequest recvreq = holder.httpMessageBuilder(RxNettys.BUILD_FULL_REQUEST).call();
        
        try {
            assertNotNull(recvreq);
        } finally {
            assertTrue(recvreq.release());
        }
    }

    private void callByteBufHolderBuilderOnceAndAssertDumpContentAndRefCnt(
            final Func0<? extends ByteBufHolder> byteBufHolderBuilder,
            final byte[] expectedContent,
            final int expectedRefCnt)
            throws IOException {
        final ByteBufHolder holder = byteBufHolderBuilder.call();
        
        try {
            final byte[] firstReadBytes = Nettys.dumpByteBufAsBytes(holder.content());
            
            assertTrue(Arrays.equals(expectedContent, firstReadBytes));
            
            final byte[] secondReadBytes = Nettys.dumpByteBufAsBytes(holder.content());
            
            assertTrue(Arrays.equals(EMPTY_BYTES, secondReadBytes));
        } finally {
            holder.release();
            assertEquals(expectedRefCnt, holder.refCnt());
        }
    }

    @Test
    public final void testTradeForRequestAndContentsSourceAndDumpFullRequests() throws IOException {
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
        
        final HttpMessageHolder holder = new HttpMessageHolder(0);
        final Observable<? extends HttpObject> inbound = 
            trade.addCloseHook(RxActions.<HttpTrade>toAction1(holder.release()))
            .inboundRequest()
            .compose(holder.assembleAndHold());
        
        inbound.subscribe(RxSubscribers.nopOnNext(), RxSubscribers.nopOnError());
        
        requestObservable.connect();
        
        final Func0<FullHttpRequest> fullRequestBuilder = 
                holder.httpMessageBuilder(RxNettys.BUILD_FULL_REQUEST);
        
        callByteBufHolderBuilderOnceAndAssertDumpContentAndRefCnt(
            fullRequestBuilder, REQ_CONTENT.getBytes(Charsets.UTF_8), 0);
        callByteBufHolderBuilderOnceAndAssertDumpContentAndRefCnt(
            fullRequestBuilder, REQ_CONTENT.getBytes(Charsets.UTF_8), 0);
    }

    @Test
    public final void testTradeForFullRequestSourceAndDumpFullRequests() throws IOException {
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", 
                    Nettys4Test.buildByteBuf(REQ_CONTENT));
        
        final ConnectableObservable<HttpObject> requestObservable = 
                Observable.<HttpObject>just(request).publish();
        
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                requestObservable);
        
        final HttpMessageHolder holder = new HttpMessageHolder(0);
        final Observable<? extends HttpObject> inbound = 
            trade.addCloseHook(RxActions.<HttpTrade>toAction1(holder.release()))
            .inboundRequest()
            .compose(holder.assembleAndHold());
        
        inbound.subscribe(RxSubscribers.nopOnNext(), RxSubscribers.nopOnError());
        
        requestObservable.connect();
        
        final Func0<FullHttpRequest> fullRequestBuilder = 
                holder.httpMessageBuilder(RxNettys.BUILD_FULL_REQUEST);
        
        //  expected refCnt, request -- 1 + HttpMessageHolder -- 1, total refcnt is 2
        callByteBufHolderBuilderOnceAndAssertDumpContentAndRefCnt(
            fullRequestBuilder, REQ_CONTENT.getBytes(Charsets.UTF_8), 2);
        
        callByteBufHolderBuilderOnceAndAssertDumpContentAndRefCnt(
            fullRequestBuilder, REQ_CONTENT.getBytes(Charsets.UTF_8), 2);
    }
    
    @Test
    public final void testTradeForRequestAndLastContentSourceAndDumpFullRequests() throws IOException {
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final LastHttpContent lastcontent = new DefaultLastHttpContent(Nettys4Test.buildByteBuf(REQ_CONTENT));
        
        final ConnectableObservable<HttpObject> requestObservable = 
                Observable.<HttpObject>just(request, lastcontent).publish();
        
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                requestObservable);
        
        final HttpMessageHolder holder = new HttpMessageHolder(0);
        final Observable<? extends HttpObject> inbound = 
            trade.addCloseHook(RxActions.<HttpTrade>toAction1(holder.release()))
            .inboundRequest()
            .compose(holder.assembleAndHold());
        
        inbound.subscribe(RxSubscribers.nopOnNext(), RxSubscribers.nopOnError());
        
        requestObservable.connect();
        
        final Func0<FullHttpRequest> fullRequestBuilder = 
                holder.httpMessageBuilder(RxNettys.BUILD_FULL_REQUEST);
        
        //  expected refCnt, request -- 1 + HttpMessageHolder -- 1, total refcnt is 2
        callByteBufHolderBuilderOnceAndAssertDumpContentAndRefCnt(
            fullRequestBuilder, REQ_CONTENT.getBytes(Charsets.UTF_8), 2);
        
        callByteBufHolderBuilderOnceAndAssertDumpContentAndRefCnt(
            fullRequestBuilder, REQ_CONTENT.getBytes(Charsets.UTF_8), 2);
    }

    @Test
    public final void testTradeForFullRequestSourceAndMultiInboundThenDumpFullRequestContent() throws IOException {
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", 
                    Nettys4Test.buildByteBuf(REQ_CONTENT));
        
        final ConnectableObservable<HttpObject> requestObservable = 
                Observable.<HttpObject>just(request).publish();
        
        final HttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                requestObservable);
        
        final AtomicReference<DefaultFullHttpRequest> ref1 = 
                new AtomicReference<DefaultFullHttpRequest>();
        trade.inboundRequest().subscribe(new Action1<HttpObject>() {
            @Override
            public void call(HttpObject httpobj) {
                ref1.set((DefaultFullHttpRequest)httpobj);
            }
        });
                
        final AtomicReference<DefaultFullHttpRequest> ref2 = 
                new AtomicReference<DefaultFullHttpRequest>();
            
        trade.inboundRequest().subscribe(new Action1<HttpObject>() {
            @Override
            public void call(HttpObject httpobj) {
                ref2.set((DefaultFullHttpRequest)httpobj);
            }
        });
        
        requestObservable.connect();
        
        final byte[] firstReadBytes = Nettys.dumpByteBufAsBytes(ref1.get().content());
        
        assertTrue(Arrays.equals(REQ_CONTENT.getBytes(Charsets.UTF_8), firstReadBytes));
        
        final byte[] secondReadBytes = Nettys.dumpByteBufAsBytes(ref2.get().content());
        
        assertTrue(Arrays.equals(REQ_CONTENT.getBytes(Charsets.UTF_8), secondReadBytes));
    }
}
