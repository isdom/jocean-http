package org.jocean.http.server.impl;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys4Test;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.rx.SubscriberHolder;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
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
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.observers.TestSubscriber;

public class DefaultHttpTradeTestCase {

    private static final byte[] EMPTY_BYTES = new byte[0];

    @SuppressWarnings("unused")
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTradeTestCase.class);
    
    private final String REQ_CONTENT = "testcontent";
    
    private static ChannelFuture writeToInboundAndFlush(
            final EmbeddedChannel channel,
            final Object msg) {
        ChannelFuture future = null;
        try {
            future = channel.writeOneInbound(ReferenceCountUtil.retain(msg));
            channel.flushInbound();
        } catch (Exception  e) {
        }
        
        return future;
    }
    
    @Test
    public final void testDoOnClosedBeforeAndAfterOutboundResponse() {
        final HttpTrade trade = new DefaultHttpTrade(new EmbeddedChannel());
        
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
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);
        channel.disconnect().syncUninterruptibly();
        
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
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, 
                "/", Nettys4Test.buildByteBuf("testcontent"));
        
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        
        trade.inbound().message().subscribe(reqSubscriber);
        
        trade.abort();
        assertFalse(trade.isActive());
        reqSubscriber.assertTerminalEvent();
        reqSubscriber.assertError(Exception.class);
        
        writeToInboundAndFlush(channel, request);
        
        reqSubscriber.assertValueCount(0);
    }

    @Test
    public final void testTradeForCallAbortAfterRequestPublish() {
        final DefaultFullHttpRequest request = 
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, 
                "/", Nettys4Test.buildByteBuf("testcontent"));
        
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        
        trade.inbound().message().subscribe(reqSubscriber);
        
        writeToInboundAndFlush(channel, request);
        
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
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, 
                "/", Nettys4Test.buildByteBuf("testcontent"));
        
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);
        
        writeToInboundAndFlush(channel, request);
        
        trade.abort();
        assertFalse(trade.isActive());
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        trade.inbound().message().subscribe(reqSubscriber);
        
        reqSubscriber.assertTerminalEvent();
        reqSubscriber.assertError(Exception.class);
        reqSubscriber.assertValueCount(0);
    }
    
    @Test
    public final void testTradeForCallAbortAfterPartRequestThenPushError() {
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = 
                Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel, -1);
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        trade.inbound().message().subscribe(reqSubscriber);
        
        writeToInboundAndFlush(channel, request);
        writeToInboundAndFlush(channel, req_contents[0]);
        
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
        final HttpContent[] req_contents = 
                Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel, -1);
        
        final TestSubscriber<HttpObject> reqSubscriber1 = new TestSubscriber<>();
        trade.inbound().message().subscribe(reqSubscriber1);
        
        final TestSubscriber<HttpObject> reqSubscriber2 = new TestSubscriber<>();
        trade.inbound().message().subscribe(reqSubscriber2);
        
        final TestSubscriber<HttpObject> reqSubscriber3 = new TestSubscriber<>();
        trade.inbound().message().subscribe(reqSubscriber3);
        
        writeToInboundAndFlush(channel, request);
        writeToInboundAndFlush(channel, req_contents[0]);
        
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
        final HttpContent[] req_contents = 
                Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel, -1);
        
        final TestSubscriber<HttpObject> reqSubscriber1 = new TestSubscriber<>();
        trade.inbound().message().subscribe(reqSubscriber1);
        
        writeToInboundAndFlush(channel, request);
        
        reqSubscriber1.assertValueCount(1);
        reqSubscriber1.assertValues(request);
        
        final TestSubscriber<HttpObject> reqSubscriber2 = new TestSubscriber<>();
        trade.inbound().message().subscribe(reqSubscriber2);
        
        writeToInboundAndFlush(channel, req_contents[0]);
        
        reqSubscriber1.assertValueCount(2);
        reqSubscriber1.assertValues(request, req_contents[0]);
        
        reqSubscriber2.assertValueCount(2);
        reqSubscriber2.assertValues(request, req_contents[0]);
        
        final TestSubscriber<HttpObject> reqSubscriber3 = new TestSubscriber<>();
        trade.inbound().message().subscribe(reqSubscriber3);
        
        reqSubscriber1.assertValueCount(2);
        reqSubscriber1.assertValues(request, req_contents[0]);
        
        reqSubscriber2.assertValueCount(2);
        reqSubscriber2.assertValues(request, req_contents[0]);
        
        reqSubscriber3.assertValueCount(2);
        reqSubscriber3.assertValues(request, req_contents[0]);
    }
    
    @Test
    public final void testTradeForCompleteRound() {
        final DefaultFullHttpRequest request = 
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, 
                HttpMethod.POST, "/", Nettys4Test.buildByteBuf("test content"));
        
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel, -1);
        
        writeToInboundAndFlush(channel, request);
        
        assertTrue(trade.isActive());
        
        final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        
        trade.outboundResponse(Observable.<HttpObject>just(response));
        
        assertFalse(trade.isActive());
    }

    @Test
    public final void testTradeForCompleteRoundWithMultiContentRequest() {
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = 
                Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);
        
        Observable.<HttpObject>concat(
            Observable.<HttpObject>just(request),
            Observable.<HttpObject>from(req_contents),
            Observable.<HttpObject>just(LastHttpContent.EMPTY_LAST_CONTENT)
        ).subscribe(obj -> writeToInboundAndFlush(channel, obj));

//        new Action1<HttpObject>() {
//        @Override
//        public void call(final HttpObject obj) {
//            writeToInboundAndFlush(channel, obj);
//        }}
        
        assertTrue(trade.isActive());
        
        final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        
        trade.outboundResponse(Observable.<HttpObject>just(response));
        
        assertFalse(trade.isActive());
    }
    
    @Test
    public final void testTradeForRequestPartError() {
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = 
                Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final List<HttpObject> part_req = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(request);
            for (int idx = 0; idx < 5; idx++) {
                this.add(req_contents[idx]);
            }
        }};
        
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel, -1);
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        trade.inbound().message().subscribe(reqSubscriber);
        
        Observable.<HttpObject>concat(
            Observable.<HttpObject>just(request),
            Observable.<HttpObject>just(req_contents[0]),
            Observable.<HttpObject>just(req_contents[1]),
            Observable.<HttpObject>just(req_contents[2]),
            Observable.<HttpObject>just(req_contents[3]),
            Observable.<HttpObject>just(req_contents[4])
        ).map(obj -> writeToInboundAndFlush(channel, obj))
        .last()
        .toBlocking()
        .single()
        .syncUninterruptibly();
        
        channel.disconnect().syncUninterruptibly();
        
        assertFalse(trade.isActive());
        reqSubscriber.assertTerminalEvent();
        //  java.lang.AssertionError: Exceptions differ; expected: java.lang.RuntimeException: RequestPartError, 
        //      actual: java.lang.RuntimeException: trade unactived
        reqSubscriber.assertError(Exception.class);
        reqSubscriber.assertNotCompleted();
        reqSubscriber.assertValues(part_req.toArray(new HttpObject[0]));
    }

    /*// remove outboundResponse second method: outboundResponse(
            final Observable<? extends HttpObject> response,
            final Action1<Throwable> onError) 
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
    */
    
    /*
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
    */
    
    /* Not Support Response Replace
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
    */
    
    @Test
    public final void testTradeForResponseAfterAbort() {
        final HttpTrade trade = new DefaultHttpTrade(new EmbeddedChannel());
        
        trade.abort();
        assertFalse(trade.isActive());
        
        final SubscriberHolder<HttpObject> subsholder = new SubscriberHolder<>();
        final Subscription subscription = trade.outboundResponse(Observable.create(subsholder));
        
        assertNull(subscription);
        assertEquals(0, subsholder.getSubscriberCount());
    }
    
    @Test
    public final void testTradeForReadyOutboundResponseAfterResponseOnNext() {
        final HttpTrade trade = new DefaultHttpTrade(new EmbeddedChannel());
        
        assertTrue(trade.isActive());
        
        assertTrue(trade.readyforOutboundResponse());
        
        final SubscriberHolder<HttpObject> subsholder1 = new SubscriberHolder<>();
        final Subscription subscription1 = trade.outboundResponse(Observable.create(subsholder1));
    
        assertNotNull(subscription1);
        assertFalse(trade.readyforOutboundResponse());
        
        final DefaultHttpRequest req1 = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        Nettys4Test.emitHttpObjects(subsholder1.getAt(0), req1);
        
        assertFalse(trade.readyforOutboundResponse());
    }
    
    @Test
    public final void testTradeForReadyOutboundResponseAfterResponseOnCompleted() {
        final HttpTrade trade = new DefaultHttpTrade(new EmbeddedChannel());
        
        assertTrue(trade.isActive());
        
        assertTrue(trade.readyforOutboundResponse());
        
        final SubscriberHolder<HttpObject> subsholder1 = new SubscriberHolder<>();
        final Subscription subscription1 = trade.outboundResponse(Observable.create(subsholder1));
    
        assertNotNull(subscription1);
        assertFalse(trade.readyforOutboundResponse());
        
        subsholder1.getAt(0).onCompleted();
        
        assertFalse(trade.readyforOutboundResponse());
    }
    
    
    @Test
    public final void testTradeForCompleteRoundAndWithCacheOperator() {
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = 
                Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);
        
        Observable.<HttpObject>concat(
            Observable.<HttpObject>just(request),
            Observable.<HttpObject>from(req_contents),
            Observable.<HttpObject>just(LastHttpContent.EMPTY_LAST_CONTENT)
        ).map(obj -> writeToInboundAndFlush(channel, obj))
        .last()
        .toBlocking()
        .single()
        .syncUninterruptibly();
        
        //  Error, will cause : exception when invoke visitor(org.jocean.http.util.RxNettys$2@722c517b), detail: 
        //      java.lang.ClassCastException: io.netty.handler.codec.http.DefaultHttpRequest cannot be cast to 
        //      io.netty.handler.codec.http.HttpContent
        //  inbound.subscribe();    double subscribe holder.assembleAndHold()
        
        final FullHttpRequest recvreq = trade.inbound().messageHolder()
                .httpMessageBuilder(RxNettys.BUILD_FULL_REQUEST).call();
        
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
        final HttpContent[] req_contents = 
                Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);
        
        Observable.<HttpObject>concat(
            Observable.<HttpObject>just(request),
            Observable.<HttpObject>from(req_contents),
            Observable.<HttpObject>just(LastHttpContent.EMPTY_LAST_CONTENT)
        ).map(obj -> writeToInboundAndFlush(channel, obj))
        .last()
        .toBlocking()
        .single()
        .syncUninterruptibly();
        
        final Func0<FullHttpRequest> fullRequestBuilder = 
            trade.inbound().messageHolder().httpMessageBuilder(RxNettys.BUILD_FULL_REQUEST);
        
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
        
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);
        
        writeToInboundAndFlush(channel, request);
        
        final Func0<FullHttpRequest> fullRequestBuilder = 
            trade.inbound().messageHolder().httpMessageBuilder(RxNettys.BUILD_FULL_REQUEST);
        
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
        
        final LastHttpContent lastcontent = 
            new DefaultLastHttpContent(Nettys4Test.buildByteBuf(REQ_CONTENT));
        
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);
        
        writeToInboundAndFlush(channel, request);
        writeToInboundAndFlush(channel, lastcontent);
        
        final Func0<FullHttpRequest> fullRequestBuilder = 
            trade.inbound().messageHolder().httpMessageBuilder(RxNettys.BUILD_FULL_REQUEST);
        
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
        
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);
        
        final AtomicReference<DefaultFullHttpRequest> ref1 = 
                new AtomicReference<DefaultFullHttpRequest>();
        trade.inbound().message().subscribe(new Action1<HttpObject>() {
            @Override
            public void call(HttpObject httpobj) {
                ref1.set((DefaultFullHttpRequest)httpobj);
            }
        });
                
        final AtomicReference<DefaultFullHttpRequest> ref2 = 
                new AtomicReference<DefaultFullHttpRequest>();
            
        trade.inbound().message().subscribe(new Action1<HttpObject>() {
            @Override
            public void call(HttpObject httpobj) {
                ref2.set((DefaultFullHttpRequest)httpobj);
            }
        });
        
        writeToInboundAndFlush(channel, request);
        
        final byte[] firstReadBytes = Nettys.dumpByteBufAsBytes(ref1.get().content());
        
        assertTrue(Arrays.equals(REQ_CONTENT.getBytes(Charsets.UTF_8), firstReadBytes));
        
        final byte[] secondReadBytes = Nettys.dumpByteBufAsBytes(ref2.get().content());
        
        assertTrue(Arrays.equals(REQ_CONTENT.getBytes(Charsets.UTF_8), secondReadBytes));
    }
}
