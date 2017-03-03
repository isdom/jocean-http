package org.jocean.http.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.idiom.rx.RxActions;
import org.jocean.idiom.rx.RxSubscribers;
import org.jocean.idiom.rx.SubscriberHolder;
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
import rx.functions.Func0;
import rx.functions.Func1;
import rx.observables.ConnectableObservable;
import rx.observers.TestSubscriber;

public class HttpMessageHolderTestCase {
    final String REQ_CONTENT = "testcontent";
    
    @Test(expected = RuntimeException.class)
    public final void testHttpMessageHolderForMultiSubscribe() {
        // max block size == -1 means disable assemble
        final HttpMessageHolder holder = new HttpMessageHolder();
        holder.setMaxBlockSize(-1);
        
        final FullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", 
                    Nettys4Test.buildByteBuf(REQ_CONTENT));
        
        
        final Observable<HttpObject> requestObservable = 
            Observable.<HttpObject>just(request)
                .compose(holder.<HttpObject>assembleAndHold());
        
        //  subscribe more than once
        final HttpObject h1 = requestObservable.toBlocking().single();
        assertSame(h1, request);
        
        requestObservable.toBlocking().single();
    }
    
    @Test
    public final void testHttpMessageHolderForDisableAssembleOnlyCached() {
        // max block size == -1 means disable assemble
        final HttpMessageHolder holder = new HttpMessageHolder();
        holder.setMaxBlockSize(-1);
        
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
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        Observable.<HttpObject>from(reqs)
            .compose(holder.<HttpObject>assembleAndHold())
            .subscribe(reqSubscriber);
        
        reqSubscriber.assertValueCount(reqs.size());
        reqSubscriber.assertValues(reqs.toArray(new HttpObject[0]));
        reqSubscriber.assertNoErrors();
        reqSubscriber.assertCompleted();
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
    }
    
    @Test
    public final void testHttpMessageHolderForFragmentedFeature() {
        // max block size == -1 means disable assemble
        final HttpMessageHolder holder = new HttpMessageHolder();
        holder.setMaxBlockSize(-1);
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
            Observable.concat(
                Observable.<HttpObject>just(request),
                Observable.<HttpObject>from(req_contents),
                Observable.<HttpObject>just(LastHttpContent.EMPTY_LAST_CONTENT)
            )
            .compose(holder.<HttpObject>assembleAndHold())
            .subscribe(reqSubscriber);
            
        final int size = holder.retainedByteBufSize();
        assertFalse(holder.isFragmented());
        assertNotNull(holder.httpMessageBuilder(RxNettys.BUILD_FULL_REQUEST).call());
         
        holder.releaseHttpContent(req_contents[0]);
        assertTrue(holder.isFragmented());
        assertNull(holder.httpMessageBuilder(RxNettys.BUILD_FULL_REQUEST).call());
        assertEquals(size -1, holder.retainedByteBufSize());
    }
    
    @Test
    public final void testHttpMessageHolderForPushFullRequestAfterRelease() {
        final HttpMessageHolder holder = new HttpMessageHolder();
        holder.setMaxBlockSize(-1);
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final SubscriberHolder<HttpObject> subscholder = new SubscriberHolder<>();
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        
        Observable.create(subscholder)
            .compose(holder.<HttpObject>assembleAndHold())
            .subscribe(reqSubscriber);
        
        assertEquals(1, subscholder.getSubscriberCount());

        holder.release().call();
        
        Nettys4Test.emitHttpObjects(subscholder.getAt(0), request);
        Nettys4Test.emitHttpObjects(subscholder.getAt(0), req_contents);
        Nettys4Test.emitHttpObjects(subscholder.getAt(0), LastHttpContent.EMPTY_LAST_CONTENT);
        
        // TODO:  completed or error ?
        reqSubscriber.assertCompleted();
        reqSubscriber.assertNoErrors();
        reqSubscriber.assertNoValues();
    }
    
    @Test
    public final void testHttpMessageHolderForRequestPartError() {
        final HttpMessageHolder holder = new HttpMessageHolder();
        holder.setMaxBlockSize(-1);
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        final List<HttpObject> partReq = new ArrayList<HttpObject>() {
            private static final long serialVersionUID = 1L;
        {
            this.add(request);
            for (int idx = 0; idx < 5; idx++) {
                this.add(req_contents[idx]);
            }
        }};
        
        Observable.concat(
            Observable.<HttpObject>from(partReq),
            Observable.<HttpObject>error(new RuntimeException("RequestPartError"))
        )
        .compose(holder.assembleAndHold())
        .subscribe(
            RxSubscribers.ignoreNext(),
            RxSubscribers.ignoreError());
        
        holder.httpMessageBuilder(new Func1<HttpObject[],Void>() {
            @Override
            public Void call(final HttpObject[] objs) {
                assertTrue(Arrays.equals(partReq.toArray(new HttpObject[0]), objs));
                return null;
            }}).call();
    }
    
    @Test
    public final void testHttpMessageHolderForDisableAssembleAndInvokeBindHttpObjsSuccess() {
        final HttpMessageHolder holder = new HttpMessageHolder();
        holder.setMaxBlockSize(-1);
        
        final AtomicReference<HttpObject[]> objsRef = new AtomicReference<>();
        final Func1<HttpObject[], Void> visitorHttpObjs = new Func1<HttpObject[], Void>() {
            @Override
            public Void call(final HttpObject[] objs) {
                objsRef.set(objs);
                return null;
            }};
        final Func0<Void> getobjs = holder.httpMessageBuilder(visitorHttpObjs);
        
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
        
        Observable.<HttpObject>from(reqs)
            .compose(holder.assembleAndHold())
            .subscribe();
        
        getobjs.call();
        assertTrue(Arrays.equals(reqs.toArray(new HttpObject[0]), objsRef.get()));
    }
    
    @Test
    public final void testHttpMessageHolderForDisableAssembleInvokeBindHttpObjsAfterRelease() {
        final HttpMessageHolder holder = new HttpMessageHolder();
        holder.setMaxBlockSize(-1);
        
        final AtomicReference<HttpObject[]> objsRef = new AtomicReference<>();
        final Func1<HttpObject[], Void> visitorHttpObjs = new Func1<HttpObject[], Void>() {
            @Override
            public Void call(final HttpObject[] objs) {
                objsRef.set(objs);
                return null;
            }};
        final Func0<Void> getobjs = holder.httpMessageBuilder(visitorHttpObjs);
        
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
        
        Observable.<HttpObject>from(reqs)
            .compose(holder.assembleAndHold())
            .subscribe();
        
        holder.release().call();
        getobjs.call();
        assertNull(objsRef.get());
    }
    
    @Test
    public final void testHttpMessageHolderForDisableAssembleGetAndInvokeBindHttpObjsAfterRelease() {
        final HttpMessageHolder holder = new HttpMessageHolder();
        holder.setMaxBlockSize(-1);
        
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
        
        Observable.<HttpObject>from(reqs)
            .compose(holder.assembleAndHold())
            .subscribe();
        
        holder.release().call();
        
        final AtomicReference<HttpObject[]> objsRef = new AtomicReference<>();
        final Func1<HttpObject[], Void> visitorHttpObjs = new Func1<HttpObject[], Void>() {
            @Override
            public Void call(final HttpObject[] objs) {
                objsRef.set(objs);
                return null;
            }};
        holder.httpMessageBuilder(visitorHttpObjs).call();
        assertNull(objsRef.get());
    }
    
    @Test
    public final void testHttpMessageHolderForAssembleWithMaxBlockSizeMoreThanZero() {
        testHttpMessageHolderForAssembleFor(16, 1);
        testHttpMessageHolderForAssembleFor(8, 1);
        testHttpMessageHolderForAssembleFor(4, 1);
        testHttpMessageHolderForAssembleFor(1, 1);
    }
    
    public final void testHttpMessageHolderForAssembleFor(final int MAX_BLOCK_SIZE, final int PIECE_SIZE) {
        final HttpMessageHolder holder = new HttpMessageHolder();
        holder.setMaxBlockSize(MAX_BLOCK_SIZE);
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), PIECE_SIZE);
        
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
        
        requestObservable
            .compose(holder.assembleAndHold())
            .subscribe();
        
        requestObservable.connect();
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        assertEquals(0, holder.currentBlockSize());
        assertEquals(0, holder.currentBlockCount());
        assertEquals(2 + (req_contents.length * PIECE_SIZE + MAX_BLOCK_SIZE -1) / MAX_BLOCK_SIZE, 
                holder.cachedHttpObjectCount());
        
        holder.release().call();
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
    }
}
