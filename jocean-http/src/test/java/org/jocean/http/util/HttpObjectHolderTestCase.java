package org.jocean.http.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jocean.http.util.HttpObjectHolder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys4Test;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.rx.RxActions;
import org.jocean.idiom.rx.RxSubscribers;
import org.jocean.idiom.rx.SubscriberHolder;
import org.junit.Test;

import com.google.common.base.Charsets;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import rx.Observable;
import rx.functions.Action1;
import rx.observables.ConnectableObservable;
import rx.observers.TestSubscriber;

public class HttpObjectHolderTestCase {

    @Test
    public final void tesHttpObjectHolderForDisableAssembleOnlyCached() throws Exception {
        final String REQ_CONTENT = "testcontent";
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        // max block size == -1 means disable assemble
        final HttpObjectHolder holder = new HttpObjectHolder(-1);
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        final List<HttpObject> reqs = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(request);
            this.addAll(Arrays.asList(req_contents));
            this.add(LastHttpContent.EMPTY_LAST_CONTENT);
        }};
        
        Observable.<HttpObject>from(reqs)
            .compose(holder.assembleAndHold())
            .subscribe(reqSubscriber);
        
        reqSubscriber.assertValueCount(reqs.size());
        reqSubscriber.assertValues(reqs.toArray(new HttpObject[0]));
        reqSubscriber.assertNoErrors();
        reqSubscriber.assertCompleted();
    }
    
    @Test
    public final void tesHttpObjectHolderForDisableAssembleAndInvokeBindHttpObjsSuccess() throws Exception {
        final String REQ_CONTENT = "testcontent";
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final HttpObjectHolder holder = new HttpObjectHolder(-1);
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        final List<HttpObject> reqs = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(request);
            this.addAll(Arrays.asList(req_contents));
            this.add(LastHttpContent.EMPTY_LAST_CONTENT);
        }};
        
        Observable.<HttpObject>from(reqs)
            .compose(holder.assembleAndHold())
            .subscribe(reqSubscriber);
        
        final FullHttpRequest fullreq = holder.bindHttpObjects(RxNettys.BUILD_FULL_REQUEST).call();
        
        assertNotNull(fullreq);
        
        assertEquals(REQ_CONTENT, new String(Nettys.dumpByteBufAsBytes(fullreq.content()), Charsets.UTF_8));
    }
    
    @Test
    public final void tesHttpObjectHolderForDisableAssembleInvokeBindHttpObjsAfterRelease() throws Exception {
        final String REQ_CONTENT = "testcontent";
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final HttpObjectHolder holder = new HttpObjectHolder(-1);
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        final List<HttpObject> reqs = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(request);
            this.addAll(Arrays.asList(req_contents));
            this.add(LastHttpContent.EMPTY_LAST_CONTENT);
        }};
        
        Observable.<HttpObject>from(reqs)
            .compose(holder.assembleAndHold())
            .subscribe(reqSubscriber);
        
        holder.release().call();
        
        final FullHttpRequest fullreq = holder.bindHttpObjects(RxNettys.BUILD_FULL_REQUEST).call();
        
        assertNull(fullreq);
    }
    
    @Test
    public final void testHttpObjectHolderForAssembleWithMaxBlockSize16() throws Exception {
        final String REQ_CONTENT = "testcontent";
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        final ConnectableObservable<HttpObject> requestObservable = 
                Observable.<HttpObject>from(new ArrayList<HttpObject>() {
                    private static final long serialVersionUID = 1L;
                {
                    this.add(request);
                    this.addAll(Arrays.asList(req_contents));
                    this.add(LastHttpContent.EMPTY_LAST_CONTENT);
                }}).publish();
        
        final HttpObjectHolder holder = new HttpObjectHolder(16);
        
        requestObservable.compose(holder.assembleAndHold()).subscribe();
        
        requestObservable.connect();
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        assertEquals(0, holder.currentBlockSize());
        assertEquals(0, holder.currentBlockCount());
        assertEquals(3, holder.cachedHttpObjectCount());
        
        final FullHttpRequest fullrequest = holder.bindHttpObjects(RxNettys.BUILD_FULL_REQUEST).call();
        assertNotNull(fullrequest);
        
        //  注意：因为 cached request 中 HttpContent 被重组为 2 个 CompositeByteBuf
        //  所以 retainFullHttpRequest 只会增加 CompositeByteBuf 本身的引用计数，
        //  而不会增加 CompositeByteBuf 中子元素 ByteBuf 的引用计数，因此 req_contents
        //  中的 各子元素 ByteBuf 引用计数不变, 还是2
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        final String reqcontent = 
                new String(Nettys.dumpByteBufAsBytes(fullrequest.content()), Charsets.UTF_8);
        assertEquals(REQ_CONTENT, reqcontent);
        
        fullrequest.release();
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        holder.release().call();
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
    }
    
    @Test
    public final void testHttpObjectHolderForAssembleWithMaxBlockSize8() throws Exception {
        final String REQ_CONTENT = "testcontent";
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        final ConnectableObservable<HttpObject> requestObservable = 
                Observable.<HttpObject>from(new ArrayList<HttpObject>() {
                    private static final long serialVersionUID = 1L;
                {
                    this.add(request);
                    this.addAll(Arrays.asList(req_contents));
                    this.add(LastHttpContent.EMPTY_LAST_CONTENT);
                }}).publish();
        
        final HttpObjectHolder holder = new HttpObjectHolder(8);
        
        requestObservable.compose(holder.assembleAndHold()).subscribe();
        
        requestObservable.connect();
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        assertEquals(0, holder.currentBlockSize());
        assertEquals(0, holder.currentBlockCount());
        assertEquals(4, holder.cachedHttpObjectCount());
        
        final FullHttpRequest fullrequest = holder.bindHttpObjects(RxNettys.BUILD_FULL_REQUEST).call();
        assertNotNull(fullrequest);
        
        //  注意：因为 cached request 中 HttpContent 被重组为 2 个 CompositeByteBuf
        //  所以 retainFullHttpRequest 只会增加 CompositeByteBuf 本身的引用计数，
        //  而不会增加 CompositeByteBuf 中子元素 ByteBuf 的引用计数，因此 req_contents
        //  中的 各子元素 ByteBuf 引用计数不变, 还是2
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        final String reqcontent = 
                new String(Nettys.dumpByteBufAsBytes(fullrequest.content()), Charsets.UTF_8);
        assertEquals(REQ_CONTENT, reqcontent);
        
        fullrequest.release();
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        holder.release().call();
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
    }
    
    @Test
    public final void testHttpObjectHolderForAssembleWithMaxBlockSize1() 
            throws Exception {
        final String REQ_CONTENT = "testcontent";
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        final ConnectableObservable<HttpObject> requestObservable = 
                Observable.<HttpObject>from(new ArrayList<HttpObject>() {
                    private static final long serialVersionUID = 1L;
                {
                    this.add(request);
                    this.addAll(Arrays.asList(req_contents));
                    this.add(LastHttpContent.EMPTY_LAST_CONTENT);
                }}).publish();
        
        final HttpObjectHolder holder = new HttpObjectHolder(1);
        requestObservable.compose(holder.assembleAndHold()).subscribe();
        
        requestObservable.connect();
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        assertEquals(0, holder.currentBlockSize());
        assertEquals(0, holder.currentBlockCount());
        assertEquals(13, holder.cachedHttpObjectCount());
        
        final FullHttpRequest fullrequest = holder.bindHttpObjects(RxNettys.BUILD_FULL_REQUEST).call();
        assertNotNull(fullrequest);
        
        //  注意：因为 cached request 的maxBlockSize = 1, 因此 HttpContent 均原样加入 reqHttpObjects 中
        //  所以 retainFullHttpRequest 会增加 req_contents
        //  中的 各子元素 ByteBuf 引用计数, 增加为 3
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(3, c.refCnt());
            }});
        
        final String reqcontent = 
                new String(Nettys.dumpByteBufAsBytes(fullrequest.content()), Charsets.UTF_8);
        assertEquals(REQ_CONTENT, reqcontent);
        
        fullrequest.release();
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        holder.release().call();
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
    }
    
    @Test
    public final void testHttpObjectHolderForAssembleWithMaxBlockSize8AndResendRequestAfterComplete() throws Exception {
        final String REQ_CONTENT = "testcontent";
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        final SubscriberHolder<HttpObject> subscholder = new SubscriberHolder<>();
        
        final HttpObjectHolder holder = new HttpObjectHolder(8);
        Observable.create(subscholder).compose(holder.assembleAndHold()).subscribe();
        
        assertEquals(1, subscholder.getSubscriberCount());
        
        Nettys4Test.emitHttpObjects(subscholder.getAt(0), request);
        Nettys4Test.emitHttpObjects(subscholder.getAt(0), req_contents);
        Nettys4Test.emitHttpObjects(subscholder.getAt(0), LastHttpContent.EMPTY_LAST_CONTENT);
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        assertEquals(0, holder.currentBlockSize());
        assertEquals(0, holder.currentBlockCount());
        assertEquals(4, holder.cachedHttpObjectCount());
        
        final FullHttpRequest fullrequest = holder.bindHttpObjects(RxNettys.BUILD_FULL_REQUEST).call();
        assertNotNull(fullrequest);
        
        //  注意：因为 cached request 中 HttpContent 被重组为 2 个 CompositeByteBuf
        //  所以 retainFullHttpRequest 只会增加 CompositeByteBuf 本身的引用计数，
        //  而不会增加 CompositeByteBuf 中子元素 ByteBuf 的引用计数，因此 req_contents
        //  中的 各子元素 ByteBuf 引用计数不变, 还是2
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        final String reqcontent = 
                new String(Nettys.dumpByteBufAsBytes(fullrequest.content()), Charsets.UTF_8);
        assertEquals(REQ_CONTENT, reqcontent);
        
        fullrequest.release();
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        holder.release().call();
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        Nettys4Test.emitHttpObjects(subscholder.getAt(0), request);
        Nettys4Test.emitHttpObjects(subscholder.getAt(0), req_contents);
        Nettys4Test.emitHttpObjects(subscholder.getAt(0), LastHttpContent.EMPTY_LAST_CONTENT);
        
        assertEquals(0, holder.currentBlockSize());
        assertEquals(0, holder.currentBlockCount());
        assertEquals(0, holder.cachedHttpObjectCount());
    }
    
    @Test
    public final void testHttpObjectHolderForAssembleAndRequestPartError() throws Exception {
        final String REQ_CONTENT = "testcontent";
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        final HttpObjectHolder holder = new HttpObjectHolder(4);
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
        ).compose(holder.assembleAndHold()).subscribe(RxSubscribers.nopOnNext(), RxSubscribers.nopOnError());
        
        /*
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        assertEquals(0, holder.currentBlockSize());
        assertEquals(0, holder.currentBlockCount());
        assertEquals(0, holder.cachedHttpObjectCount());
        */
        
        final FullHttpRequest fullrequest = holder.bindHttpObjects(RxNettys.BUILD_FULL_REQUEST).call();
        assertNull(fullrequest);
    }
}
