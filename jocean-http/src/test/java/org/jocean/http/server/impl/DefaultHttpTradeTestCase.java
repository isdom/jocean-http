package org.jocean.http.server.impl;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.http.server.CachedRequest;
import org.jocean.http.server.HttpServer.HttpTrade;
import org.jocean.http.util.Nettys;
import org.jocean.idiom.rx.RxActions;
import org.jocean.idiom.rx.SubscriberHolder;
import org.junit.Test;

import com.google.common.base.Charsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action1;

public class DefaultHttpTradeTestCase {

    private static HttpContent[] buildContentArray(final byte[] srcBytes, final int bytesPerContent) {
        final List<HttpContent> contents = new ArrayList<>();
        
        int startInBytes = 0;
        while (startInBytes < srcBytes.length) {
            final ByteBuf content = Unpooled.buffer(bytesPerContent);
            final int len = Math.min(bytesPerContent, srcBytes.length-startInBytes);
            
            content.writeBytes(srcBytes, startInBytes, len);
            startInBytes += len;
            contents.add(new DefaultHttpContent(content));
        }
        return contents.toArray(new HttpContent[0]);
    }

    private static void emitFullRequest(final DefaultHttpRequest request,
            final HttpContent[] contents,
            final Subscriber<? super HttpObject> subscriber) {
        subscriber.onNext(request);
        for (HttpContent c : contents) {
            subscriber.onNext(c);
        }
        subscriber.onNext(LastHttpContent.EMPTY_LAST_CONTENT);
        subscriber.onCompleted();
    }
    
    @Test
    public final void testOnTradeClosedCalledWhenClosed() {
        final DefaultHttpTrade trade = new DefaultHttpTrade(new LocalChannel(), 
                Observable.<HttpObject>empty());
        
        final AtomicBoolean onClosed = new AtomicBoolean(false);
        trade.addOnTradeClosed(new Action1<HttpTrade>(){
            @Override
            public void call(final HttpTrade trade) {
                onClosed.set(true);
            }});
        
        assertFalse(onClosed.get());
        assertTrue(trade.isActive());
        
        Observable.<HttpObject>error(new RuntimeException("ResponseError"))
            .subscribe(trade.responseObserver());
        
        assertTrue(onClosed.get());
    }

    @Test
    public final void testInvokeAddOnTradeClosedCallAfterClosed() {
        final DefaultHttpTrade trade = new DefaultHttpTrade(new LocalChannel(), 
                Observable.<HttpObject>error(new RuntimeException("RequestError")));
        
        assertFalse(trade.isActive());
        
        final AtomicBoolean onClosed = new AtomicBoolean(false);
        trade.addOnTradeClosed(new Action1<HttpTrade>(){
            @Override
            public void call(final HttpTrade trade) {
                onClosed.set(true);
            }});
        
        assertTrue(onClosed.get());
    }

    @Test
    public final void tesTradeForCompleteRequestAndErrorResponse() throws Exception {
        final ByteBuf content = Unpooled.buffer(0);
        content.writeBytes("test content".getBytes(Charsets.UTF_8));
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", content);
        
        assertEquals(1, request.refCnt());
        
        final SubscriberHolder<HttpObject> holder = new SubscriberHolder<>();
        
        final DefaultHttpTrade trade = new DefaultHttpTrade(new LocalChannel(), 
                Observable.create(holder));
        
        CachedRequest cached = new CachedRequest(trade);
        
        assertEquals(1, holder.getSubscriberCount());
        
        holder.getAt(0).onNext(request);
        holder.getAt(0).onCompleted();
        
        assertTrue(trade.isActive());
        assertEquals(2, request.refCnt());
        
        final FullHttpRequest fullrequest = cached.retainFullHttpRequest();
        assertNotNull(fullrequest);
        
        Observable.<HttpObject>error(new RuntimeException("ResponseError"))
            .subscribe(trade.responseObserver());
        
        assertEquals(2, request.refCnt());
        
        fullrequest.release();
        
        assertFalse(trade.isActive());
        assertEquals(1, request.refCnt());
    }

    @Test
    public final void tesTradeForCompleteRound() throws Exception {
        final ByteBuf content = Unpooled.buffer(0);
        content.writeBytes("test content".getBytes(Charsets.UTF_8));
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", content);
        
        assertEquals(1, request.refCnt());
        
        final SubscriberHolder<HttpObject> holder = new SubscriberHolder<>();
        
        final DefaultHttpTrade trade = new DefaultHttpTrade(new LocalChannel(), 
                Observable.create(holder));
        
        CachedRequest cached = new CachedRequest(trade);
        
        assertEquals(1, holder.getSubscriberCount());
        
        holder.getAt(0).onNext(request);
        holder.getAt(0).onCompleted();
        
        assertTrue(trade.isActive());
        assertEquals(2, request.refCnt());
        
        final FullHttpRequest fullrequest = cached.retainFullHttpRequest();
        assertNotNull(fullrequest);
        
        final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        
        Observable.<HttpObject>just(response)
            .subscribe(trade.responseObserver());
        
        assertEquals(2, request.refCnt());
        
        fullrequest.release();
        
        assertFalse(trade.isActive());
        assertEquals(1, request.refCnt());
    }

    @Test
    public final void tesTradeForCompleteRoundWithReassembRequest() throws Exception {
        final String REQ_CONTENT = "testcontent";
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] contents = buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        RxActions.applyArrayBy(contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        final SubscriberHolder<HttpObject> holder = new SubscriberHolder<>();
        
        final DefaultHttpTrade trade = new DefaultHttpTrade(new LocalChannel(), 
                Observable.create(holder));
        
        final CachedRequest cached = new CachedRequest(trade, 16);
        
        assertEquals(1, holder.getSubscriberCount());
        
        emitFullRequest(request, contents, holder.getAt(0));
        
        assertTrue(trade.isActive());
        RxActions.applyArrayBy(contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        assertEquals(0, cached.currentBlockSize());
        assertEquals(0, cached.currentBlockCount());
        assertEquals(3, cached.requestHttpObjCount());
        
        final FullHttpRequest fullrequest = cached.retainFullHttpRequest();
        assertNotNull(fullrequest);
        
        final String reqcontent = 
                new String(Nettys.dumpByteBufAsBytes(fullrequest.content()), Charsets.UTF_8);
        assertEquals(REQ_CONTENT, reqcontent);
        
        final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        
        Observable.<HttpObject>just(response)
            .subscribe(trade.responseObserver());
        
        RxActions.applyArrayBy(contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        fullrequest.release();
        
        assertFalse(trade.isActive());
        RxActions.applyArrayBy(contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
    }

    @Test
    public final void tesTradeForCompleteRoundWithReassembRequestTwice() throws Exception {
        final String REQ_CONTENT = "testcontent";
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] contents = buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        RxActions.applyArrayBy(contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        final SubscriberHolder<HttpObject> holder = new SubscriberHolder<>();
        
        final DefaultHttpTrade trade = new DefaultHttpTrade(new LocalChannel(), 
                Observable.create(holder));
        
        final CachedRequest cached = new CachedRequest(trade, 8);
        
        assertEquals(1, holder.getSubscriberCount());
        
        emitFullRequest(request, contents, holder.getAt(0));
        
        assertTrue(trade.isActive());
        RxActions.applyArrayBy(contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        assertEquals(0, cached.currentBlockSize());
        assertEquals(0, cached.currentBlockCount());
        assertEquals(4, cached.requestHttpObjCount());
        
        final FullHttpRequest fullrequest = cached.retainFullHttpRequest();
        assertNotNull(fullrequest);
        
        final String reqcontent = 
                new String(Nettys.dumpByteBufAsBytes(fullrequest.content()), Charsets.UTF_8);
        assertEquals(REQ_CONTENT, reqcontent);
        
        final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        
        Observable.<HttpObject>just(response)
            .subscribe(trade.responseObserver());
        
        RxActions.applyArrayBy(contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        fullrequest.release();
        
        assertFalse(trade.isActive());
        RxActions.applyArrayBy(contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
    }
    
    @Test
    public final void tesTradeForCompleteRoundWithReassembRequestAndReSendRequestAfterComplete() throws Exception {
        final String REQ_CONTENT = "testcontent";
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] contents = buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        RxActions.applyArrayBy(contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        final SubscriberHolder<HttpObject> holder = new SubscriberHolder<>();
        
        final DefaultHttpTrade trade = new DefaultHttpTrade(new LocalChannel(), 
                Observable.create(holder));
        
        final CachedRequest cached = new CachedRequest(trade, 8);
        
        assertEquals(1, holder.getSubscriberCount());
        
        emitFullRequest(request, contents, holder.getAt(0));
        
        assertTrue(trade.isActive());
        RxActions.applyArrayBy(contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        assertEquals(0, cached.currentBlockSize());
        assertEquals(0, cached.currentBlockCount());
        assertEquals(4, cached.requestHttpObjCount());
        
        final FullHttpRequest fullrequest = cached.retainFullHttpRequest();
        assertNotNull(fullrequest);
        
        final String reqcontent = 
                new String(Nettys.dumpByteBufAsBytes(fullrequest.content()), Charsets.UTF_8);
        assertEquals(REQ_CONTENT, reqcontent);
        
        final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        
        Observable.<HttpObject>just(response)
            .subscribe(trade.responseObserver());
        
        RxActions.applyArrayBy(contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        fullrequest.release();
        
        assertFalse(trade.isActive());
        RxActions.applyArrayBy(contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        emitFullRequest(request, contents, holder.getAt(0));
        
        assertEquals(0, cached.currentBlockSize());
        assertEquals(0, cached.currentBlockCount());
        assertEquals(0, cached.requestHttpObjCount());
    }
    
    @Test
    public final void tesTradeForRequestPartError() throws Exception {
        final String REQ_CONTENT = "testcontent";
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] contents = buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        RxActions.applyArrayBy(contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        final SubscriberHolder<HttpObject> holder = new SubscriberHolder<>();
        
        final DefaultHttpTrade trade = new DefaultHttpTrade(new LocalChannel(), 
                Observable.create(holder));
        
        final CachedRequest cached = new CachedRequest(trade, 4);
        
        assertEquals(1, holder.getSubscriberCount());
        
        holder.getAt(0).onNext(request);
        for (int idx = 0; idx < 5; idx++) {
            holder.getAt(0).onNext(contents[idx]);
        }
        holder.getAt(0).onError(new RuntimeException("RequestPartError"));
        
        assertFalse(trade.isActive());
        RxActions.applyArrayBy(contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        assertEquals(0, cached.currentBlockSize());
        assertEquals(0, cached.currentBlockCount());
        assertEquals(0, cached.requestHttpObjCount());
        
        final FullHttpRequest fullrequest = cached.retainFullHttpRequest();
        assertNull(fullrequest);
    }
}
