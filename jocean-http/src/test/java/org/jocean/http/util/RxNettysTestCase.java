package org.jocean.http.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jocean.idiom.rx.RxActions;
import org.junit.Test;

import com.google.common.base.Charsets;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import rx.Observable;
import rx.functions.Action1;
import rx.observers.TestSubscriber;

public class RxNettysTestCase {

    final String REQ_CONTENT = "testcontent";

    @Test
    public final void test_BUILD_FULL_REQUEST_ForSingleFullRequest() {
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Nettys4Test.buildByteBuf(REQ_CONTENT));
        
        assertEquals(1, request.refCnt());
        
        final FullHttpRequest fullreq = RxNettys.BUILD_FULL_REQUEST.call(new HttpObject[]{request});
        assertNotNull(fullreq);
        assertSame(request, fullreq);
        assertEquals(2, request.refCnt());
    }
    
    @Test
    public final void test_BUILD_FULL_REQUEST_ForFullRequestAsArray() throws Exception {
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final List<HttpObject> reqs = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(request);
            this.addAll(Arrays.asList(req_contents));
            this.add(LastHttpContent.EMPTY_LAST_CONTENT);
        }};
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        final FullHttpRequest fullreq = RxNettys.BUILD_FULL_REQUEST.call(reqs.toArray(new HttpObject[0]));
        assertNotNull(fullreq);
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        assertEquals(REQ_CONTENT, new String(Nettys.dumpByteBufAsBytes(fullreq.content()), Charsets.UTF_8));
    }

    @Test
    public final void test_BUILD_FULL_REQUEST_WhenNoLastContent() {
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final List<HttpObject> reqs = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(request);
            this.addAll(Arrays.asList(req_contents));
        }};
        
        final FullHttpRequest fullreq = RxNettys.BUILD_FULL_REQUEST.call(reqs.toArray(new HttpObject[0]));
        assertNull(fullreq);
    }

    @Test
    public final void test_BUILD_FULL_REQUEST_WhenNoRequest() {
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final List<HttpObject> reqs = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.addAll(Arrays.asList(req_contents));
            this.add(LastHttpContent.EMPTY_LAST_CONTENT);
        }};
        
        final FullHttpRequest fullreq = RxNettys.BUILD_FULL_REQUEST.call(reqs.toArray(new HttpObject[0]));
        assertNull(fullreq);
    }
    
    @Test
    public final void  testSplitFullHttpMessageForRequest() {
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Nettys4Test.buildByteBuf(REQ_CONTENT));
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        Observable.just(request).flatMap(RxNettys.splitFullHttpMessage())
            .subscribe(reqSubscriber);
        
        reqSubscriber.assertValueCount(2);
        final FullHttpRequest fullreq = RxNettys.BUILD_FULL_REQUEST.call(reqSubscriber.getOnNextEvents().toArray(new HttpObject[0]));
        assertNotNull(fullreq);
    }
}
