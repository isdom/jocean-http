package org.jocean.http.server.internal;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.FullMessage;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys4Test;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.rx.SubscriberHolder;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
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
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
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
        } catch (final Exception  e) {
        }

        return future;
    }

    @Test
    public final void testTradeUsingUnactiveChannel() {
        final Channel unactiveChannel = new EmbeddedChannel();
        unactiveChannel.disconnect().syncUninterruptibly();
        assertFalse(unactiveChannel.isActive());

        final HttpTrade trade = new DefaultHttpTrade(unactiveChannel);

        assertFalse(trade.isActive());

        final AtomicBoolean onClosed = new AtomicBoolean(false);
        trade.doOnEnd(new Action1<HttpTrade>(){
            @Override
            public void call(final HttpTrade trade) {
                onClosed.set(true);
            }});

        assertTrue(onClosed.get());
    }

    @Test
    public final void testDoOnClosedBeforeAndAfterOutboundResponse() {
        final HttpTrade trade = new DefaultHttpTrade(new EmbeddedChannel());

        final AtomicBoolean onClosed = new AtomicBoolean(false);
        trade.doOnEnd(new Action1<HttpTrade>(){
            @Override
            public void call(final HttpTrade trade) {
                onClosed.set(true);
            }});

        assertFalse(onClosed.get());
        assertTrue(trade.isActive());

        trade.outbound(Observable.<HttpObject>error(new RuntimeException("ResponseError")));

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
        trade.doOnEnd(new Action1<HttpTrade>(){
            @Override
            public void call(final HttpTrade trade) {
                onClosed.set(true);
            }});

        assertTrue(onClosed.get());
    }

    //  TODO: fix
    @Test
    public final void testTradeForCallAbortBeforeRequestPublish() {
        final DefaultFullHttpRequest request =
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST,
                "/", Nettys4Test.buildByteBuf("testcontent"));

        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);

        final TestSubscriber<FullMessage<HttpRequest>> reqSubscriber = new TestSubscriber<>();

        trade.inbound().subscribe(reqSubscriber);

        trade.close();
        assertTrue(!trade.isActive());
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

        final TestSubscriber<DisposableWrapper<FullHttpRequest>> reqSubscriber = new TestSubscriber<>();

        trade.inbound().compose(RxNettys.fullmessage2dwq(trade, true)).subscribe(reqSubscriber);

        writeToInboundAndFlush(channel, request);

        trade.close();
        assertFalse(trade.isActive());

        reqSubscriber.assertValueCount(1);
        reqSubscriber.assertValue(RxNettys.<FullHttpRequest>wrap4release(request));
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

        trade.close();
        assertTrue(!trade.isActive());

        final TestSubscriber<FullMessage<HttpRequest>> reqSubscriber = new TestSubscriber<>();
        trade.inbound().subscribe(reqSubscriber);

        reqSubscriber.assertTerminalEvent();
        reqSubscriber.assertError(Exception.class);
        reqSubscriber.assertValueCount(0);
    }

    /*
    @Test // TODO
    public final void testTradeForCallAbortAfterPartRequestThenPushError() {
        final DefaultHttpRequest request =
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents =
                Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);

        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);

        final TestSubscriber<DisposableWrapper<? extends HttpObject>> reqSubscriber = new TestSubscriber<>();
        trade.inbound()
        .compose(MessageUtil.AUTOSTEP2DWH)
        .subscribe(reqSubscriber);

        writeToInboundAndFlush(channel, request);
        writeToInboundAndFlush(channel, req_contents[0]);

        trade.close();
        assertFalse(trade.isActive());

        // TODO, fix no terminal event
//        reqSubscriber.assertTerminalEvent();
//        reqSubscriber.assertError(Exception.class);
        reqSubscriber.assertValueCount(2);
        reqSubscriber.assertValues(RxNettys.<HttpObject>wrap4release(request),
                RxNettys.<HttpObject>wrap4release(req_contents[0]));
    }
    */

    /*
    @Test // TODO
    public final void testTradeForMultiSubscribeRequestOnlyOneToSource() {
        final DefaultHttpRequest request =
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents =
                Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);

        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);

        final TestSubscriber<DisposableWrapper<? extends HttpObject>> reqSubscriber1 = new TestSubscriber<>();
        trade.inbound()
        .compose(MessageUtil.AUTOSTEP2DWH)
        .subscribe(reqSubscriber1);

        final TestSubscriber<DisposableWrapper<? extends HttpObject>> reqSubscriber2 = new TestSubscriber<>();
        trade.inbound()
        .compose(MessageUtil.AUTOSTEP2DWH)
        .subscribe(reqSubscriber2);

        final TestSubscriber<DisposableWrapper<? extends HttpObject>> reqSubscriber3 = new TestSubscriber<>();
        trade.inbound()
        .compose(MessageUtil.AUTOSTEP2DWH)
        .subscribe(reqSubscriber3);

        writeToInboundAndFlush(channel, request);
        writeToInboundAndFlush(channel, req_contents[0]);

        reqSubscriber1.assertValueCount(2);
        reqSubscriber1.assertValues(RxNettys.<HttpObject>wrap4release(request), RxNettys.<HttpObject>wrap4release(req_contents[0]));

        reqSubscriber2.assertValueCount(2);
        reqSubscriber2.assertValues(RxNettys.<HttpObject>wrap4release(request), RxNettys.<HttpObject>wrap4release(req_contents[0]));

        reqSubscriber3.assertValueCount(2);
        reqSubscriber3.assertValues(RxNettys.<HttpObject>wrap4release(request), RxNettys.<HttpObject>wrap4release(req_contents[0]));
    }
    */

    /*
    //  3 subscriber subscribe inbound request at different time,
    //  so push with different httpobject
    @Test // TODO
    public final void testTradeForMultiSubscribeRequestOnlyOneToSource2() {
        final DefaultHttpRequest request =
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents =
                Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);

        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);
//        trade.inboundHolder().setMaxBlockSize(-1);

        final TestSubscriber<DisposableWrapper<? extends HttpObject>> reqSubscriber1 = new TestSubscriber<>();
        trade.inbound()
        .compose(MessageUtil.AUTOSTEP2DWH)
        .subscribe(reqSubscriber1);

        writeToInboundAndFlush(channel, request);

        reqSubscriber1.assertValueCount(1);
        reqSubscriber1.assertValues(RxNettys.<HttpObject>wrap4release(request));

        final TestSubscriber<DisposableWrapper<? extends HttpObject>> reqSubscriber2 = new TestSubscriber<>();
        trade.inbound()
        .compose(MessageUtil.AUTOSTEP2DWH)
        .subscribe(reqSubscriber2);

        writeToInboundAndFlush(channel, req_contents[0]);

        reqSubscriber1.assertValueCount(2);
        reqSubscriber1.assertValues(RxNettys.<HttpObject>wrap4release(request), RxNettys.<HttpObject>wrap4release(req_contents[0]));

        reqSubscriber2.assertValueCount(2);
        reqSubscriber2.assertValues(RxNettys.<HttpObject>wrap4release(request), RxNettys.<HttpObject>wrap4release(req_contents[0]));

        final TestSubscriber<DisposableWrapper<? extends HttpObject>> reqSubscriber3 = new TestSubscriber<>();
        trade.inbound()
        .compose(MessageUtil.AUTOSTEP2DWH)
        .subscribe(reqSubscriber3);

        reqSubscriber1.assertValueCount(2);
        reqSubscriber1.assertValues(RxNettys.<HttpObject>wrap4release(request), RxNettys.<HttpObject>wrap4release(req_contents[0]));

        reqSubscriber2.assertValueCount(2);
        reqSubscriber2.assertValues(RxNettys.<HttpObject>wrap4release(request), RxNettys.<HttpObject>wrap4release(req_contents[0]));

        reqSubscriber3.assertValueCount(2);
        reqSubscriber3.assertValues(RxNettys.<HttpObject>wrap4release(request), RxNettys.<HttpObject>wrap4release(req_contents[0]));
    }
    */

    @Test
    public final void testTradeForCompleteRound() {
        final DefaultFullHttpRequest request =
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                HttpMethod.POST, "/", Nettys4Test.buildByteBuf("test content"));

        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);
//        trade.inboundHolder().setMaxBlockSize(-1);

        writeToInboundAndFlush(channel, request);

        assertTrue(trade.isActive());

        final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);

        trade.outbound(Observable.<HttpObject>just(response));

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

        trade.outbound(Observable.<HttpObject>just(response));

        assertFalse(trade.isActive());
    }

    /*
    @Test // TODO
    public final void testTradeForRequestPartError() {
        final DefaultHttpRequest request =
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents =
                Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);

        final List<DisposableWrapper<HttpObject>> part_req = new ArrayList<DisposableWrapper<HttpObject>>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(RxNettys.<HttpObject>wrap4release(request));
            for (int idx = 0; idx < 5; idx++) {
                this.add(RxNettys.<HttpObject>wrap4release(req_contents[idx]));
            }
        }};

        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);
//        trade.inboundHolder().setMaxBlockSize(-1);

        final TestSubscriber<DisposableWrapper<? extends HttpObject>> reqSubscriber = new TestSubscriber<>();
        trade.inbound()
        .compose(MessageUtil.AUTOSTEP2DWH)
        .subscribe(reqSubscriber);

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

        assertTrue(!trade.isActive());
        //  TODO, fix assert terminal event
//        reqSubscriber.assertTerminalEvent();
        //  java.lang.AssertionError: Exceptions differ; expected: java.lang.RuntimeException: RequestPartError,
        //      actual: java.lang.RuntimeException: trade unactived
//        reqSubscriber.assertError(Exception.class);
        reqSubscriber.assertNotCompleted();
        reqSubscriber.assertValues(part_req.toArray(new DisposableWrapper[0]));
    }
    */

    @Test
    public final void testTradeForResponseAfterAbort() {
        final HttpTrade trade = new DefaultHttpTrade(new EmbeddedChannel());

        trade.close();
        assertFalse(trade.isActive());

        final SubscriberHolder<HttpObject> subsholder = new SubscriberHolder<>();
        final Subscription subscription = trade.outbound(Observable.unsafeCreate(subsholder));

        assertNull(subscription);
        assertEquals(0, subsholder.getSubscriberCount());
    }

    @Test
    public final void testTradeForReadyOutboundResponseAfterResponseOnNext() {
        final HttpTrade trade = new DefaultHttpTrade(new EmbeddedChannel());

        assertTrue(trade.isActive());

        final SubscriberHolder<HttpObject> subsholder1 = new SubscriberHolder<>();
        final Subscription subscription1 = trade.outbound(Observable.unsafeCreate(subsholder1));

        assertNotNull(subscription1);

        final DefaultHttpRequest req1 =
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        Nettys4Test.emitHttpObjects(subsholder1.getAt(0), req1);
    }

    @Test
    public final void testTradeForReadyOutboundResponseAfterResponseOnCompleted() {
        final HttpTrade trade = new DefaultHttpTrade(new EmbeddedChannel());

        assertTrue(trade.isActive());

        final SubscriberHolder<HttpObject> subsholder1 = new SubscriberHolder<>();
        final Subscription subscription1 = trade.outbound(Observable.unsafeCreate(subsholder1));

        assertNotNull(subscription1);

        subsholder1.getAt(0).onCompleted();
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

        final FullHttpRequest recvreq = trade.inbound().compose(RxNettys.fullmessage2dwq(trade, true))
                .toBlocking().single().unwrap();

        assertNotNull(recvreq);

        trade.close();
    }

    private void callByteBufHolderBuilderOnceAndAssertDumpContentAndRefCnt(
            final ByteBufHolder holder,
            final byte[] expectedContent,
            final int expectedRefCnt)
            throws IOException {
        final byte[] firstReadBytes = Nettys.dumpByteBufAsBytes(holder.content());

        assertTrue(Arrays.equals(expectedContent, firstReadBytes));

        final byte[] secondReadBytes = Nettys.dumpByteBufAsBytes(holder.content());

        assertTrue(Arrays.equals(EMPTY_BYTES, secondReadBytes));
        assertEquals(expectedRefCnt, holder.refCnt());
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

        callByteBufHolderBuilderOnceAndAssertDumpContentAndRefCnt(
                trade.inbound().compose(RxNettys.fullmessage2dwq(trade, true)).toBlocking().single().unwrap(),
                REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        callByteBufHolderBuilderOnceAndAssertDumpContentAndRefCnt(
                trade.inbound().compose(RxNettys.fullmessage2dwq(trade, true)).toBlocking().single().unwrap(),
                REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
    }

    @Test
    public final void testTradeForFullRequestSourceAndDumpFullRequests() throws IOException {
        final DefaultFullHttpRequest request =
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/",
                Nettys4Test.buildByteBuf(REQ_CONTENT));

        final EmbeddedChannel channel = new EmbeddedChannel();
        final HttpTrade trade = new DefaultHttpTrade(channel);

        writeToInboundAndFlush(channel, request);

        //  expected refCnt, request -- 1 + HttpMessageHolder -- 1, total refcnt is 2
        // TODO, why 4 & 5 refCnt ?
        callByteBufHolderBuilderOnceAndAssertDumpContentAndRefCnt(
            trade.inbound().compose(RxNettys.fullmessage2dwq(trade, true)).toBlocking().single().unwrap(),
            REQ_CONTENT.getBytes(Charsets.UTF_8), 4);

        callByteBufHolderBuilderOnceAndAssertDumpContentAndRefCnt(
                trade.inbound().compose(RxNettys.fullmessage2dwq(trade, true)).toBlocking().single().unwrap(),
            REQ_CONTENT.getBytes(Charsets.UTF_8), 5);
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

//        final Func0<FullHttpRequest> fullRequestBuilder =
//            trade.inboundHolder().fullOf(RxNettys.BUILD_FULL_REQUEST);

        //  expected refCnt, request -- 1 + HttpMessageHolder -- 1, total refcnt is 2
        // TODO, why 4 & 5 refCnt ?
        callByteBufHolderBuilderOnceAndAssertDumpContentAndRefCnt(
                trade.inbound().compose(RxNettys.fullmessage2dwq(trade, true)).toBlocking().single().unwrap(),
                REQ_CONTENT.getBytes(Charsets.UTF_8), 4);

        callByteBufHolderBuilderOnceAndAssertDumpContentAndRefCnt(
                trade.inbound().compose(RxNettys.fullmessage2dwq(trade, true)).toBlocking().single().unwrap(),
                REQ_CONTENT.getBytes(Charsets.UTF_8), 5);
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
        trade.inbound().compose(RxNettys.fullmessage2dwq(trade, true))
        .subscribe(dwq -> ref1.set((DefaultFullHttpRequest)dwq.unwrap()));

        final AtomicReference<DefaultFullHttpRequest> ref2 =
                new AtomicReference<DefaultFullHttpRequest>();

        trade.inbound().compose(RxNettys.fullmessage2dwq(trade, true))
        .subscribe(dwq -> ref2.set((DefaultFullHttpRequest)dwq.unwrap()));

        writeToInboundAndFlush(channel, request);

        final byte[] firstReadBytes = Nettys.dumpByteBufAsBytes(ref1.get().content());

        assertTrue(Arrays.equals(REQ_CONTENT.getBytes(Charsets.UTF_8), firstReadBytes));

        final byte[] secondReadBytes = Nettys.dumpByteBufAsBytes(ref2.get().content());

        assertTrue(Arrays.equals(REQ_CONTENT.getBytes(Charsets.UTF_8), secondReadBytes));
    }
}
