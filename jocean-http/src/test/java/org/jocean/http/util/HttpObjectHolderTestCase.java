package org.jocean.http.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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

import io.netty.handler.codec.http.DefaultHttpRequest;
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

public class HttpObjectHolderTestCase {
    final String REQ_CONTENT = "testcontent";
    
    @Test
    public final void testHttpObjectHolderForDisableAssembleOnlyCached() {
        // max block size == -1 means disable assemble
        final HttpObjectHolder holder = new HttpObjectHolder(-1);
        
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
            .compose(holder.assembleAndHold())
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
    public final void testHttpObjectHolderForPushFullRequestAfterRelease() {
        final HttpObjectHolder holder = new HttpObjectHolder(-1);
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        final SubscriberHolder<HttpObject> subscholder = new SubscriberHolder<>();
        final TestSubscriber<HttpObject> reqSubscriber = new TestSubscriber<>();
        
        Observable.create(subscholder)
            .compose(holder.assembleAndHold())
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
    public final void testHttpObjectHolderForRequestPartError() {
        final HttpObjectHolder holder = new HttpObjectHolder(-1);
        
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
        .subscribe(RxSubscribers.nopOnNext(), RxSubscribers.nopOnError());
        
        holder.bindHttpObjects(new Func1<HttpObject[],Void>() {
            @Override
            public Void call(final HttpObject[] objs) {
                assertTrue(Arrays.equals(partReq.toArray(new HttpObject[0]), objs));
                return null;
            }}).call();
    }
    
    @Test
    public final void testHttpObjectHolderForDisableAssembleAndInvokeBindHttpObjsSuccess() {
        final HttpObjectHolder holder = new HttpObjectHolder(-1);
        final AtomicReference<HttpObject[]> objsRef = new AtomicReference<>();
        final Func1<HttpObject[], Void> visitorHttpObjs = new Func1<HttpObject[], Void>() {
            @Override
            public Void call(final HttpObject[] objs) {
                objsRef.set(objs);
                return null;
            }};
        final Func0<Void> getobjs = holder.bindHttpObjects(visitorHttpObjs);
        
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
    public final void testHttpObjectHolderForDisableAssembleInvokeBindHttpObjsAfterRelease() {
        final HttpObjectHolder holder = new HttpObjectHolder(-1);
        final AtomicReference<HttpObject[]> objsRef = new AtomicReference<>();
        final Func1<HttpObject[], Void> visitorHttpObjs = new Func1<HttpObject[], Void>() {
            @Override
            public Void call(final HttpObject[] objs) {
                objsRef.set(objs);
                return null;
            }};
        final Func0<Void> getobjs = holder.bindHttpObjects(visitorHttpObjs);
        
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
    public final void testHttpObjectHolderForDisableAssembleGetAndInvokeBindHttpObjsAfterRelease() {
        final HttpObjectHolder holder = new HttpObjectHolder(-1);
        
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
        holder.bindHttpObjects(visitorHttpObjs).call();
        assertNull(objsRef.get());
    }
    
    @Test
    public final void testHttpObjectHolderForAssembleWithMaxBlockSizeMoreThanZero() {
        testHttpObjectHolderForAssembleFor(16, 1);
        testHttpObjectHolderForAssembleFor(8, 1);
        testHttpObjectHolderForAssembleFor(4, 1);
        testHttpObjectHolderForAssembleFor(1, 1);
    }
    
    public final void testHttpObjectHolderForAssembleFor(final int MAX_BLOCK_SIZE, final int PIECE_SIZE) {
        final HttpObjectHolder holder = new HttpObjectHolder(MAX_BLOCK_SIZE);
        
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
