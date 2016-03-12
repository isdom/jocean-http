package org.jocean.http.server.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import io.netty.channel.local.LocalChannel;
import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.functions.Action0;

public class DefaultHttpTradeTestCase {

    @Test
    public final void testOnTradeClosedCalledWhenClosed() {
        final DefaultHttpTrade trade = new DefaultHttpTrade(new LocalChannel(), 
                Observable.<HttpObject>empty(),
                null);
        
        final AtomicBoolean onClosed = new AtomicBoolean(false);
        trade.addOnTradeClosed(new Action0(){

            @Override
            public void call() {
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
                Observable.<HttpObject>error(new RuntimeException("RequestError")), null);
        
        assertFalse(trade.isActive());
        
        final AtomicBoolean onClosed = new AtomicBoolean(false);
        trade.addOnTradeClosed(new Action0(){
            @Override
            public void call() {
                onClosed.set(true);
            }});
        
        assertTrue(onClosed.get());
    }
}
