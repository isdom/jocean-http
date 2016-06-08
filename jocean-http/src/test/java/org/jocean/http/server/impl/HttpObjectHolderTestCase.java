package org.jocean.http.server.impl;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;

import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys4Test;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.Pair;
import org.jocean.idiom.rx.RxActions;
import org.jocean.idiom.rx.SubscriberHolder;
import org.junit.Test;

import com.google.common.base.Charsets;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.functions.Action1;
import rx.observables.ConnectableObservable;

public class HttpObjectHolderTestCase {

    @Test
    public final void test() {
        fail("Not yet implemented");
    }

    /*
    @Test
    public final void tesTradeForCompleteRoundWithMultiContentRequest() throws Exception {
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
        
        final DefaultHttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                requestObservable, null).cached(16);
        
        requestObservable.connect();
        
        assertTrue(trade.isActive());
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        assertEquals(0, trade.currentBlockSize());
        assertEquals(0, trade.currentBlockCount());
        assertEquals(3, trade.requestHttpObjCount());
        
        final FullHttpRequest fullrequest = trade.retainFullHttpRequest();
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
        
        final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        
        trade.outboundResponse(Observable.<HttpObject>just(response));
        
        assertFalse(trade.isActive());
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
    }
    
    @Test
    public final void tesTradeForCompleteRoundWithMultiContentRequestLessMaxBlockSize() throws Exception {
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
        
        final DefaultHttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                requestObservable, null).cached(8);
        
        requestObservable.connect();
        
        assertTrue(trade.isActive());
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        assertEquals(0, trade.currentBlockSize());
        assertEquals(0, trade.currentBlockCount());
        assertEquals(4, trade.requestHttpObjCount());
        
        final FullHttpRequest fullrequest = trade.retainFullHttpRequest();
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
        
        final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        
        trade.outboundResponse(Observable.<HttpObject>just(response));
        
        assertFalse(trade.isActive());
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
    }
    
    @Test
    public final void tesTradeForCompleteRoundWithMultiContentRequestAndMaxBlockSizeIs1() 
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
        
        final DefaultHttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                requestObservable, null).cached(1);
        
        requestObservable.connect();
        
        assertTrue(trade.isActive());
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        assertEquals(0, trade.currentBlockSize());
        assertEquals(0, trade.currentBlockCount());
        assertEquals(13, trade.requestHttpObjCount());
        
        final FullHttpRequest fullrequest = trade.retainFullHttpRequest();
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
        
        final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        
        trade.outboundResponse(Observable.<HttpObject>just(response));
        
        assertFalse(trade.isActive());
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
    }
    
    @Test
    public final void tesTradeForCompleteRoundWithMultiContentRequestAndResendRequestAfterComplete() throws Exception {
        final String REQ_CONTENT = "testcontent";
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        final SubscriberHolder<HttpObject> holder = new SubscriberHolder<>();
        
        final CachedHttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                Observable.create(holder), null).cached(8);
        
        assertEquals(1, holder.getSubscriberCount());
        
        Nettys4Test.emitHttpObjects(holder.getAt(0), request);
        Nettys4Test.emitHttpObjects(holder.getAt(0), req_contents);
        Nettys4Test.emitHttpObjects(holder.getAt(0), LastHttpContent.EMPTY_LAST_CONTENT);
        
        assertTrue(trade.isActive());
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(2, c.refCnt());
            }});
        
        assertEquals(0, trade.currentBlockSize());
        assertEquals(0, trade.currentBlockCount());
        assertEquals(4, trade.requestHttpObjCount());
        
        final FullHttpRequest fullrequest = trade.retainFullHttpRequest();
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
        
        final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        
        trade.outboundResponse(Observable.<HttpObject>just(response));
        
        assertFalse(trade.isActive());
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        Nettys4Test.emitHttpObjects(holder.getAt(0), request);
        Nettys4Test.emitHttpObjects(holder.getAt(0), req_contents);
        Nettys4Test.emitHttpObjects(holder.getAt(0), LastHttpContent.EMPTY_LAST_CONTENT);
        
        assertEquals(0, trade.currentBlockSize());
        assertEquals(0, trade.currentBlockCount());
        assertEquals(0, trade.requestHttpObjCount());
    }
    
    @Test
    public final void tesTradeForRequestPartError() throws Exception {
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
                ).publish();
        
        final CachedHttpTrade trade = new DefaultHttpTrade(Nettys4Test.dummyChannel(), 
                requestObservable, null).cached(4);
        
        requestObservable.connect();
        
        assertFalse(trade.isActive());
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        assertEquals(0, trade.currentBlockSize());
        assertEquals(0, trade.currentBlockCount());
        assertEquals(0, trade.requestHttpObjCount());
        
        final FullHttpRequest fullrequest = trade.retainFullHttpRequest();
        assertNull(fullrequest);
    }
    
    @Test
    public final void tesTradeForCompleteRoundWithMultiContentRequestAndMultiContentResponse() 
            throws Exception {
        
        final Pair<Channel,Channel> pairChannel = Nettys4Test.createLocalConnection4Http("test");
        
        final Channel client = pairChannel.first;
        final Channel server = pairChannel.second;
        
        final String REQ_CONTENT = "testcontent";
        
        final DefaultHttpRequest request = 
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        final HttpContent[] req_contents = Nettys4Test.buildContentArray(REQ_CONTENT.getBytes(Charsets.UTF_8), 1);
        request.headers().add(HttpHeaders.Names.CONTENT_LENGTH, req_contents.length);
        
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        //  注: 因为是 LocalChannel, CachedRequest 中会持有 client 发出的 ByteBuf 实例
        final CachedHttpTrade trade = new DefaultHttpTrade(server, 
                RxNettys.httpobjObservable(server), null).cached(8);
        
        assertTrue(trade.isActive());
        
        client.write(request);
        for (HttpContent c : req_contents) {
            client.write(ReferenceCountUtil.retain(c));
        }
        client.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        
        // http request has received by server's trade instance
        // bcs of cached request's Observable completed
        trade.inboundRequest().toBlocking().subscribe();
        
        assertEquals(0, trade.currentBlockSize());
        assertEquals(0, trade.currentBlockCount());
        
        final FullHttpRequest fullrequest = trade.retainFullHttpRequest();
        assertNotNull(fullrequest);
        
        final String reqcontent = 
                new String(Nettys.dumpByteBufAsBytes(fullrequest.content()), Charsets.UTF_8);
        assertEquals(REQ_CONTENT, reqcontent);
        
        fullrequest.release();
        
        final String RESP_CONTENT = "respcontent";
        final HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        final HttpContent[] resp_contents = Nettys4Test.buildContentArray(RESP_CONTENT.getBytes(Charsets.UTF_8), 1);
        response.headers().add(HttpHeaders.Names.CONTENT_LENGTH, resp_contents.length);

        RxActions.applyArrayBy(resp_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        final Observable<? extends HttpObject> clientObservable = 
                RxNettys.httpobjObservable(client).cache();
        
        clientObservable.subscribe();
        
        trade.outboundResponse(
            Observable.<HttpObject>from(new ArrayList<HttpObject>() {
                private static final long serialVersionUID = 1L;
            {
                this.add(response);
                this.addAll(Arrays.asList(resp_contents));
                this.add(LastHttpContent.EMPTY_LAST_CONTENT);
            }}));
        
        
        assertFalse(trade.isActive());
        
        //  Trade 结束，因从 CachedRequest 被销毁。因此 不再持有 req_contents 实例
        RxActions.applyArrayBy(req_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        //  ensure trade's response has been received by client
        clientObservable.toBlocking().subscribe();
        
        RxActions.applyArrayBy(resp_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
        
        RxActions.applyArrayBy(resp_contents, new Action1<HttpContent>() {
            @Override
            public void call(final HttpContent c) {
                assertEquals(1, c.refCnt());
            }});
    }
    */
}
