package org.jocean.http.server.impl;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.server.CachedRequest;
import org.jocean.http.server.HttpServer.HttpTrade;
import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action1;

public class DefaultHttpTradeTestCase {

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
        content.writeBytes("test content".getBytes("UTF-8"));
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", content);
        
        assertEquals(1, request.refCnt());
        
        final AtomicReference<Subscriber<? super HttpObject>> subscriberRef = new AtomicReference<>();
        
        final DefaultHttpTrade trade = new DefaultHttpTrade(new LocalChannel(), 
                Observable.create(new OnSubscribe<HttpObject>() {
                    @Override
                    public void call(final Subscriber<? super HttpObject> t) {
                        subscriberRef.set(t);
                    }}));
        
        CachedRequest cached = new CachedRequest(trade);
        
        subscriberRef.get().onNext(request);
        subscriberRef.get().onCompleted();
        
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
        content.writeBytes("test content".getBytes("UTF-8"));
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", content);
        
        assertEquals(1, request.refCnt());
        
        final AtomicReference<Subscriber<? super HttpObject>> subscriberRef = new AtomicReference<>();
        
        final DefaultHttpTrade trade = new DefaultHttpTrade(new LocalChannel(), 
                Observable.create(new OnSubscribe<HttpObject>() {
                    @Override
                    public void call(final Subscriber<? super HttpObject> t) {
                        subscriberRef.set(t);
                    }}));
        
        CachedRequest cached = new CachedRequest(trade);
        
        subscriberRef.get().onNext(request);
        subscriberRef.get().onCompleted();
        
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
}
