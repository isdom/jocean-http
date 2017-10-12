package org.jocean.http.util;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
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

import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.IllegalReferenceCountException;
import rx.Observable;
import rx.functions.Action1;
import rx.observers.TestSubscriber;

public class RxNettysTestCase {

    final String REQ_CONTENT = "testcontent";

    @Test
    public final void test_httpobjs2fullreq_success() throws Exception {
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
                assertEquals(1, c.content().refCnt());
            }});
        
        final FullHttpRequest fullreq = Observable.just(reqs).map(RxNettys.httpobjs2fullreq()).toBlocking().single();
        
        assertNotNull(fullreq);
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.content().refCnt());
            }});
        assertEquals(REQ_CONTENT, new String(Nettys.dumpByteBufAsBytes(fullreq.content()), Charsets.UTF_8));
        
        fullreq.release();
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
    }
    
    @Test
    public final void test_httpobjs2fullreq_success2() throws Exception {
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Nettys4Test.buildByteBuf(REQ_CONTENT));
        
        assertEquals(1, request.refCnt());
        
        final FullHttpRequest fullreq = Observable.just(Arrays.<HttpObject>asList(request))
                .map(RxNettys.httpobjs2fullreq()).toBlocking().single();
        assertNotNull(fullreq);
        assertEquals(REQ_CONTENT, new String(Nettys.dumpByteBufAsBytes(fullreq.content()), Charsets.UTF_8));
        assertEquals(2, request.refCnt());
        
        fullreq.release();
        assertEquals(1, request.refCnt());
    }
    
    @Test
    public final void test_httpobjs2fullreq_misshttpreq() throws Exception {
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final List<HttpObject> reqs = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.addAll(Arrays.asList(req_contents));
            this.add(LastHttpContent.EMPTY_LAST_CONTENT);
        }};
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
        
        final TestSubscriber<FullHttpRequest> subscriber = new TestSubscriber<>();
        Observable.just(reqs)
        .map(RxNettys.httpobjs2fullreq())
        .subscribe(subscriber);
        
        subscriber.assertNoValues();
        subscriber.assertNotCompleted();
        subscriber.assertError(RuntimeException.class);
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
    }
    
    @Test
    public final void test_httpobjs2fullreq_misslastcontent() throws Exception {
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final List<HttpObject> reqs = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(request);
            this.addAll(Arrays.asList(req_contents));
        }};
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
        
        final TestSubscriber<FullHttpRequest> subscriber = new TestSubscriber<>();
        Observable.just(reqs)
        .map(RxNettys.httpobjs2fullreq())
        .subscribe(subscriber);
        
        subscriber.assertNoValues();
        subscriber.assertNotCompleted();
        subscriber.assertError(RuntimeException.class);
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
    }
    
    @Test
    public final void test_httpobjs2fullreq_firstcontentdisposed() throws Exception {
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
                assertEquals(1, c.content().refCnt());
            }});
        
        // release [0]'s content
        req_contents[0].release();
        
        final TestSubscriber<FullHttpRequest> subscriber = new TestSubscriber<>();
        Observable.just(reqs)
        .map(RxNettys.httpobjs2fullreq())
        .subscribe(subscriber);
        
        subscriber.assertNoValues();
        subscriber.assertNotCompleted();
        subscriber.assertError(IllegalReferenceCountException.class);
        
        RxActions.applyArrayBy(Arrays.copyOfRange(req_contents, 1, req_contents.length), 
                new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
    }
    
    @Test
    public final void test_httpobjs2fullreq_latercontentdisposed() throws Exception {
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
                assertEquals(1, c.content().refCnt());
            }});
        
        // release [0]'s content
        req_contents[req_contents.length-1].release();
        
        final TestSubscriber<FullHttpRequest> subscriber = new TestSubscriber<>();
        Observable.just(reqs)
        .map(RxNettys.httpobjs2fullreq())
        .subscribe(subscriber);
        
        subscriber.assertNoValues();
        subscriber.assertNotCompleted();
        subscriber.assertError(IllegalReferenceCountException.class);
        
        RxActions.applyArrayBy(Arrays.copyOfRange(req_contents, 0, req_contents.length - 1), 
                new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
    }
    
    @Test
    public final void test_httpobjs2fullresp_success() throws Exception {
        final DefaultHttpResponse response = 
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        
        final HttpContent[] resp_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final List<HttpObject> resps = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(response);
            this.addAll(Arrays.asList(resp_contents));
            this.add(LastHttpContent.EMPTY_LAST_CONTENT);
        }};
        
        RxActions.applyArrayBy(resp_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
        
        final FullHttpResponse fullresp = Observable.just(resps).map(RxNettys.httpobjs2fullresp()).toBlocking().single();
        
        assertNotNull(fullresp);
        RxActions.applyArrayBy(resp_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.content().refCnt());
            }});
        assertEquals(REQ_CONTENT, new String(Nettys.dumpByteBufAsBytes(fullresp.content()), Charsets.UTF_8));
        
        fullresp.release();
        
        RxActions.applyArrayBy(resp_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
    }
    
    @Test
    public final void test_httpobjs2fullresp_success2() throws Exception {
        final FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, OK,
                Nettys4Test.buildByteBuf(REQ_CONTENT));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        
        assertEquals(1, response.refCnt());
        
        final FullHttpResponse fullresp = Observable.just(Arrays.<HttpObject>asList(response))
                .map(RxNettys.httpobjs2fullresp()).toBlocking().single();
        assertNotNull(fullresp);
        assertEquals(REQ_CONTENT, new String(Nettys.dumpByteBufAsBytes(fullresp.content()), Charsets.UTF_8));
        assertEquals(2, response.refCnt());
        
        fullresp.release();
        assertEquals(1, response.refCnt());
    }
    
    @Test
    public final void test_httpobjs2fullresp_misshttpresp() throws Exception {
        final HttpContent[] resp_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final List<HttpObject> resps = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.addAll(Arrays.asList(resp_contents));
            this.add(LastHttpContent.EMPTY_LAST_CONTENT);
        }};
        
        RxActions.applyArrayBy(resp_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
        
        final TestSubscriber<FullHttpResponse> subscriber = new TestSubscriber<>();
        Observable.just(resps)
        .map(RxNettys.httpobjs2fullresp())
        .subscribe(subscriber);
        
        subscriber.assertNoValues();
        subscriber.assertNotCompleted();
        subscriber.assertError(RuntimeException.class);
        
        RxActions.applyArrayBy(resp_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
    }
    
    @Test
    public final void test_httpobjs2fullresp_misslastcontent() throws Exception {
        final DefaultHttpResponse response = 
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        final HttpContent[] resp_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final List<HttpObject> resps = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(response);
            this.addAll(Arrays.asList(resp_contents));
        }};
        
        RxActions.applyArrayBy(resp_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
        
        final TestSubscriber<FullHttpResponse> subscriber = new TestSubscriber<>();
        Observable.just(resps)
        .map(RxNettys.httpobjs2fullresp())
        .subscribe(subscriber);
        
        subscriber.assertNoValues();
        subscriber.assertNotCompleted();
        subscriber.assertError(RuntimeException.class);
        
        RxActions.applyArrayBy(resp_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
    }
    
    @Test
    public final void test_httpobjs2fullresp_firstcontentdisposed() throws Exception {
        final DefaultHttpResponse response = 
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        final HttpContent[] resp_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final List<HttpObject> resps = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(response);
            this.addAll(Arrays.asList(resp_contents));
            this.add(LastHttpContent.EMPTY_LAST_CONTENT);
        }};
        
        RxActions.applyArrayBy(resp_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
        
        // release [0]'s content
        resp_contents[0].release();
        
        final TestSubscriber<FullHttpResponse> subscriber = new TestSubscriber<>();
        Observable.just(resps)
        .map(RxNettys.httpobjs2fullresp())
        .subscribe(subscriber);
        
        subscriber.assertNoValues();
        subscriber.assertNotCompleted();
        subscriber.assertError(IllegalReferenceCountException.class);
        
        RxActions.applyArrayBy(Arrays.copyOfRange(resp_contents, 1, resp_contents.length), 
                new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
    }
    
    @Test
    public final void test_httpobjs2fullresp_latercontentdisposed() throws Exception {
        final DefaultHttpResponse response = 
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        final HttpContent[] resp_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final List<HttpObject> resps = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(response);
            this.addAll(Arrays.asList(resp_contents));
            this.add(LastHttpContent.EMPTY_LAST_CONTENT);
        }};
        
        RxActions.applyArrayBy(resp_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
        
        // release [0]'s content
        resp_contents[resp_contents.length-1].release();
        
        final TestSubscriber<FullHttpResponse> subscriber = new TestSubscriber<>();
        Observable.just(resps)
        .map(RxNettys.httpobjs2fullresp())
        .subscribe(subscriber);
        
        subscriber.assertNoValues();
        subscriber.assertNotCompleted();
        subscriber.assertError(IllegalReferenceCountException.class);
        
        RxActions.applyArrayBy(Arrays.copyOfRange(resp_contents, 0, resp_contents.length - 1), 
                new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.content().refCnt());
            }});
    }
    
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
    public final void  testSplitFullHttpMessageForRequest() throws Exception {
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", 
                        Nettys4Test.buildByteBuf(REQ_CONTENT));
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        Observable.just(request).flatMap(RxNettys.splitFullHttpMessage())
            .subscribe(reqSubscriber);
        
        reqSubscriber.assertValueCount(2);
        final FullHttpRequest fullreq = RxNettys.BUILD_FULL_REQUEST.call(
                reqSubscriber.getOnNextEvents().toArray(new HttpObject[0]));
        assertNotNull(fullreq);
        assertEquals(REQ_CONTENT, new String(Nettys.dumpByteBufAsBytes(fullreq.content()), Charsets.UTF_8));
    }

    @Test
    public final void  testSplitFullHttpMessageForResponse() {
        final FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, OK, 
                Nettys4Test.buildByteBuf(REQ_CONTENT));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

        final TestSubscriber<HttpObject> respSubscriber = new TestSubscriber<>();
        Observable.just(response).flatMap(RxNettys.splitFullHttpMessage())
            .subscribe(respSubscriber);
        
        respSubscriber.assertValueCount(2);
//        final FullHttpRequest fullreq = RxNettys.BUILD_FULL_REQUEST.call(
//                respSubscriber.getOnNextEvents().toArray(new HttpObject[0]));
//        assertNotNull(fullreq);
//        assertEquals(REQ_CONTENT, new String(Nettys.dumpByteBufAsBytes(fullreq.content()), Charsets.UTF_8));
    }

    @Test
    public final void test_BUILD_FULL_RESPONSE_ForSingleFullResponse() {
        final DefaultFullHttpResponse response = 
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Nettys4Test.buildByteBuf(REQ_CONTENT));
        
        assertEquals(1, response.refCnt());
        
        final FullHttpResponse fullresp = RxNettys.BUILD_FULL_RESPONSE.call(new HttpObject[]{response});
        assertNotNull(fullresp);
        assertSame(response, fullresp);
        assertEquals(2, response.refCnt());
    }

    @Test
    public final void test_BUILD_FULL_RESPONSE_ForFullResponseAsArray() throws Exception {
        final DefaultHttpResponse response = 
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        final HttpContent[] resp_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final List<HttpObject> reqs = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(response);
            this.addAll(Arrays.asList(resp_contents));
            this.add(LastHttpContent.EMPTY_LAST_CONTENT);
        }};
        
        RxActions.applyArrayBy(resp_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        final FullHttpResponse fullresp = RxNettys.BUILD_FULL_RESPONSE.call(reqs.toArray(new HttpObject[0]));
        assertNotNull(fullresp);
        RxActions.applyArrayBy(resp_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        assertEquals(REQ_CONTENT, new String(Nettys.dumpByteBufAsBytes(fullresp.content()), Charsets.UTF_8));
    }

    @Test
    public final void test_BUILD_FULL_RESPONSE_WhenNoLastContent() {
        final DefaultHttpResponse response = 
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        final HttpContent[] resp_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final List<HttpObject> resps = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(response);
            this.addAll(Arrays.asList(resp_contents));
        }};
        
        final FullHttpResponse fullresp = RxNettys.BUILD_FULL_RESPONSE.call(resps.toArray(new HttpObject[0]));
        assertNull(fullresp);
    }

    @Test
    public final void test_BUILD_FULLRESPONSE_WhenNoResponse() {
        final HttpContent[] resp_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final List<HttpObject> resps = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.addAll(Arrays.asList(resp_contents));
            this.add(LastHttpContent.EMPTY_LAST_CONTENT);
        }};
        
        final FullHttpResponse fullresp = RxNettys.BUILD_FULL_RESPONSE.call(resps.toArray(new HttpObject[0]));
        assertNull(fullresp);
    }

    @Test
    public final void testAsHttpRequestOp() {
        final TestSubscriber<HttpRequest> testSubscriber = new TestSubscriber<>();
        Observable.<HttpObject>just(new HttpObject() {
            @Override
            public DecoderResult decoderResult() {
                return null;
            }

            @Override
            public void setDecoderResult(DecoderResult result) {
            }

            @Override
            public DecoderResult getDecoderResult() {
                return null;
            }})
        .compose(RxNettys.asHttpRequest())
        .subscribe(testSubscriber);
        
        testSubscriber.assertNoValues();
        testSubscriber.assertNotCompleted();
        testSubscriber.assertError(RuntimeException.class);
    }

    @Test
    public final void testAsHttpResponseOp() {
        final TestSubscriber<HttpResponse> testSubscriber = new TestSubscriber<>();
        Observable.<HttpObject>just(new HttpObject() {
            @Override
            public DecoderResult decoderResult() {
                return null;
            }

            @Override
            public void setDecoderResult(DecoderResult result) {
            }

            @Override
            public DecoderResult getDecoderResult() {
                return null;
            }})
        .compose(RxNettys.asHttpResponse())
        .subscribe(testSubscriber);
        
        testSubscriber.assertNoValues();
        testSubscriber.assertNotCompleted();
        testSubscriber.assertError(RuntimeException.class);
    }
}
