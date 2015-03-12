package org.jocean.http.client.impl;

import static org.junit.Assert.assertTrue;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelException;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.Feature;
import org.jocean.http.server.HttpTestServer;
import org.jocean.http.server.HttpTestServerHandler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import rx.Observable;
import rx.functions.Func1;

public class DefaultHttpClientTestCase {

	private byte[] responseAsBytes(final Iterator<HttpObject> itr)
			throws IOException {
		final CompositeByteBuf composite = Unpooled.compositeBuffer();
		try {
			while (itr.hasNext()) {
				final HttpObject obj = itr.next();
				if (obj instanceof HttpContent) {
					composite.addComponent(((HttpContent)obj).content());
				}
				System.out.println(obj);
			}
			composite.setIndex(0, composite.capacity());
			
			@SuppressWarnings("resource")
			final InputStream is = new ByteBufInputStream(composite);
			final byte[] bytes = new byte[is.available()];
			is.read(bytes);
			return bytes;
		} finally {
			ReferenceCountUtil.release(composite);
		}
	}

	@Test
	public void testHttpHappyPath() throws Exception {
		final HttpTestServer server = new HttpTestServer(
				false, 
				new LocalAddress("test"), 
				new LocalEventLoopGroup(1), 
				new LocalEventLoopGroup(),
				LocalServerChannel.class);

		try ( final HttpClient client = new DefaultHttpClient(
				new LocalEventLoopGroup(1), LocalChannel.class, Feature.EnableLOG) ) {
		
			final Iterator<HttpObject> itr = 
				client.sendRequest(
						new LocalAddress("test"), 
						Observable.just(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
				.map(new Func1<HttpObject, HttpObject>() {
					@Override
					public HttpObject call(final HttpObject obj) {
						//	retain obj for blocking
						return ReferenceCountUtil.retain(obj);
					}})
				.toBlocking().toIterable().iterator();
			
			final byte[] bytes = responseAsBytes(itr);
			
			assertTrue(Arrays.equals(bytes, HttpTestServerHandler.CONTENT));
		} finally {
			server.stop();
		}
	}

	@Test
	public void testHttpsHappyPath() throws Exception {
		final HttpTestServer server = new HttpTestServer(
				true, 
				new LocalAddress("test"), 
				new LocalEventLoopGroup(1), 
				new LocalEventLoopGroup(),
				LocalServerChannel.class);

		try ( final HttpClient client = new DefaultHttpClient(
				new LocalEventLoopGroup(1), LocalChannel.class) ) {
		
			final Iterator<HttpObject> itr = 
				client.sendRequest(
						new LocalAddress("test"), 
						Observable.just(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")),
						Feature.EnableSSL)
				.map(new Func1<HttpObject, HttpObject>() {
					@Override
					public HttpObject call(final HttpObject obj) {
						//	retain obj for blocking
						return ReferenceCountUtil.retain(obj);
					}})
				.toBlocking().toIterable().iterator();
			
			final byte[] bytes = responseAsBytes(itr);
			
			assertTrue(Arrays.equals(bytes, HttpTestServerHandler.CONTENT));
		} finally {
			server.stop();
		}
	}
	
	@Rule  
    public ExpectedException thrown= ExpectedException.none();
	
	@Test
	public void testNotConnected() throws Exception {
		thrown.expect(ChannelException.class);
		
		//	NOT setup server for local channel
		try ( final HttpClient client = new DefaultHttpClient(
				new LocalEventLoopGroup(1), LocalChannel.class) ) {
		
			client.sendRequest(
					new LocalAddress("test"), 
					Observable.just(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")))
				.toBlocking().single();
		}
	}

	@Test
	public void testHttpsNotConnected() throws Exception {
		thrown.expect(ChannelException.class);
		
		//	NOT setup server for local channel
		try ( final HttpClient client = new DefaultHttpClient(
				new LocalEventLoopGroup(1), LocalChannel.class) ) {
		
			client.sendRequest(
					new LocalAddress("test"), 
					Observable.just(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")),
					Feature.EnableSSL)
				.toBlocking().single();
		}
	}

	@Test
	public void testHttpsNotShakehand() throws Exception {
		thrown.expect(RuntimeException.class);
		thrown.expectMessage("javax.net.ssl.SSLException:");
		
		final HttpTestServer server = new HttpTestServer(
				false, 
				new LocalAddress("test"), 
				new LocalEventLoopGroup(1), 
				new LocalEventLoopGroup(),
				LocalServerChannel.class);
		
		//	NOT setup server for local channel
		try ( final HttpClient client = new DefaultHttpClient(
				new LocalEventLoopGroup(1), LocalChannel.class) ) {
		
			client.sendRequest(
					new LocalAddress("test"), 
					Observable.just(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")),
					Feature.EnableSSL)
				.toBlocking().single();
		} finally {
			server.stop();
		}
	}
}
