package org.jocean.http.server.impl;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.jocean.http.Feature;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.client.impl.TestChannelCreator;
import org.jocean.http.server.HttpServerBuilder;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action1;

public class HttpServerDemo {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(HttpServerDemo.class);

    public static void main(final String[] args) throws Exception {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        final SslContext sslCtx = // SslContext.newServerContext(ssc.certificate(), ssc.privateKey());
                SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();

        //  create for LocalChannel
        @SuppressWarnings("resource")
        final HttpServerBuilder server = new DefaultHttpServerBuilder(
                new AbstractBootstrapCreator(
                new DefaultEventLoopGroup(1), new DefaultEventLoopGroup()) {
            @Override
            protected void initializeBootstrap(final ServerBootstrap bootstrap) {
                bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
                bootstrap.channel(LocalServerChannel.class);
            }},
            Feature.ENABLE_LOGGING,
            new Feature.ENABLE_SSL(sslCtx)
            );
        
        @SuppressWarnings("unused")
        final Subscription subscription = 
        server.defineServer(new LocalAddress("test"))
            .subscribe(new Action1<HttpTrade>() {
                @Override
                public void call(final HttpTrade trade) {
                    trade.inboundRequest().subscribe(new Subscriber<HttpObject>() {
                        private final List<HttpObject> _reqHttpObjects = new ArrayList<>();
                        
                        @Override
                        public void onCompleted() {
                            final FullHttpRequest req = retainFullHttpRequest();
                            if (null!=req) {
                                try {
                                    final FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, 
                                            Unpooled.wrappedBuffer(Nettys.dumpByteBufAsBytes(req.content())));
                                    response.headers().set(CONTENT_TYPE, "text/plain");
                                    response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                                    trade.outboundResponse(Observable.<HttpObject>just(response));
                                } catch (IOException e) {
                                    e.printStackTrace();
                                } finally {
                                    req.release();
                                }
                            }
                        }
                        @Override
                        public void onError(Throwable e) {
                        }
                        private FullHttpRequest retainFullHttpRequest() {
                            if (this._reqHttpObjects.size()>0) {
                                if (this._reqHttpObjects.get(0) instanceof FullHttpRequest) {
                                    return ((FullHttpRequest)this._reqHttpObjects.get(0)).retain();
                                }
                                
                                final HttpRequest req = (HttpRequest)this._reqHttpObjects.get(0);
                                final ByteBuf[] bufs = new ByteBuf[this._reqHttpObjects.size()-1];
                                for (int idx = 1; idx<this._reqHttpObjects.size(); idx++) {
                                    bufs[idx-1] = ((HttpContent)this._reqHttpObjects.get(idx)).content().retain();
                                }
                                return new DefaultFullHttpRequest(
                                        req.protocolVersion(), 
                                        req.method(), 
                                        req.uri(), 
                                        Unpooled.wrappedBuffer(bufs));
                            } else {
                                return null;
                            }
                        }
                        @Override
                        public void onNext(final HttpObject msg) {
                            this._reqHttpObjects.add(ReferenceCountUtil.retain(msg));
                        }});
                }});
        
//        Services.lookupOrCreateTimerService("demo").schedule(new Runnable() {
//            @Override
//            public void run() {
//                subscription.unsubscribe();
//            }}, 10 * 1000);
        
        @SuppressWarnings("resource")
        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(),
                Feature.ENABLE_LOGGING,
                new Feature.ENABLE_SSL(SslContextBuilder.forClient().build()));
        
        while (true) {
            final ByteBuf content = Unpooled.buffer(0);
            content.writeBytes("test content".getBytes("UTF-8"));
            final DefaultFullHttpRequest request = 
                    new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", content);
            HttpHeaders.setContentLength(request, content.readableBytes());
            
            final Iterator<HttpObject> itr =
                client.defineInteraction(
                new LocalAddress("test"), 
                Observable.just(request))
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            LOG.info("recv Response: {}", new String(bytes, "UTF-8"));
            
            Thread.sleep(1000);
        }
    }
}
