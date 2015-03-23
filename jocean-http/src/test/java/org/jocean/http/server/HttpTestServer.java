/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.jocean.http.server;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpHeaders.Values;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.Callable;

/**
 * An HTTP server that sends back the content of the received HTTP request
 * in a pretty plaintext form.
 */
public final class HttpTestServer {

    public static final byte[] CONTENT = { 'H', 'e', 'l', 'l', 'o', ' ', 'W', 'o', 'r', 'l', 'd' };
    
    public static final Callable<ChannelInboundHandler> DEFAULT_NEW_HANDLER = 
        new Callable<ChannelInboundHandler>() {
            @Override
            public ChannelInboundHandler call() throws Exception {
                return new HttpTestServerHandler() {
                    @Override
                    protected void channelRead0(
                            final ChannelHandlerContext ctx,
                            final HttpObject msg) throws Exception {
                        if (msg instanceof HttpRequest) {
                            HttpRequest req = (HttpRequest) msg;

                            if (HttpHeaders.is100ContinueExpected(req)) {
                                ctx.write(new DefaultFullHttpResponse(HTTP_1_1, CONTINUE));
                            }
                            boolean keepAlive = HttpHeaders.isKeepAlive(req);
                            FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, 
                                    Unpooled.wrappedBuffer(HttpTestServer.CONTENT));
                            response.headers().set(CONTENT_TYPE, "text/plain");
                            response.headers().set(CONTENT_LENGTH, response.content().readableBytes());

                            if (!keepAlive) {
                                ctx.write(response).addListener(ChannelFutureListener.CLOSE);
                            } else {
                                response.headers().set(CONNECTION, Values.KEEP_ALIVE);
                                ctx.write(response);
                            }
                        }
                    }};
        }};
    
    public HttpTestServer(final boolean enableSSL, final int port) throws Exception {
        this(enableSSL, 
            new InetSocketAddress(port), 
            new NioEventLoopGroup(1), 
            new NioEventLoopGroup(), 
            NioServerSocketChannel.class,
            DEFAULT_NEW_HANDLER);
    }
    
    public HttpTestServer(
            final boolean enableSSL, 
            final SocketAddress localAddress,
            final EventLoopGroup bossGroup, 
            final EventLoopGroup workerGroup,
            final Class<? extends ServerChannel> serverChannelType,
            final Callable<ChannelInboundHandler> newHandler) throws Exception {
        // Configure SSL.
        final SslContext sslCtx;
        if (enableSSL) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());
        } else {
            sslCtx = null;
        }

        // Configure the server.
        _bossGroup = bossGroup;
        _workerGroup = workerGroup;
        
        ServerBootstrap b = new ServerBootstrap();
        b.option(ChannelOption.SO_BACKLOG, 1024);
        b.group(_bossGroup, _workerGroup)
         .channel(serverChannelType)
         .handler(new LoggingHandler(LogLevel.INFO))
         .childHandler(new HttpTestServerInitializer(sslCtx, newHandler));

        b.bind(localAddress).sync();
    }
    
    public void stop() {
        _bossGroup.shutdownGracefully();
        _workerGroup.shutdownGracefully();
    }

    private final EventLoopGroup _bossGroup;
    private final EventLoopGroup _workerGroup;
    
    public static void main(String[] args) throws Exception {
        @SuppressWarnings("unused")
        final HttpTestServer server = new HttpTestServer(false, 8080);
    }
}