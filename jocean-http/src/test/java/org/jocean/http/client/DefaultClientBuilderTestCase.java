package org.jocean.http.client;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.Iterator;

import org.jocean.http.client.impl.DefaultClientBuilder;
import org.jocean.http.server.HttpTestServer;
import org.jocean.http.server.HttpTestServerHandler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import rx.functions.Func1;

public class DefaultClientBuilderTestCase {

	@Test
	public void testConnected() throws Exception {
		final HttpTestServer server = new HttpTestServer(false, 13322);
		
		final HttpClient.Builder builder = new DefaultClientBuilder();
		final HttpClient client = 
				builder.build(new URI("http://127.0.0.1:13322"))
				.toBlocking().single();
		
		assertNotNull(client);
		
		server.stop();
	}

	@Rule  
    public ExpectedException thrown= ExpectedException.none();
	
	@Test
	public void testNotConnected() throws Exception {
		thrown.expect(RuntimeException.class);
		thrown.expectMessage("java.net.ConnectException: Connection refused:");
		
		final HttpClient.Builder builder = new DefaultClientBuilder();
		@SuppressWarnings("unused")
		final HttpClient client = 
				builder.build(new URI("http://127.0.0.1:13322"))
				.toBlocking().single();
	}
	
	@Test
	public void testSendRequestAndRecvResponse() throws Exception {
		final HttpTestServer server = new HttpTestServer(false, 13322);
		
		final HttpClient.Builder builder = new DefaultClientBuilder();
		final HttpClient client = 
				builder.build(new URI("http://127.0.0.1:13322"))
				.toBlocking().single();
		
		assertNotNull(client);
		
		final Iterator<HttpObject> itr = 
		client.sendRequest(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
		.map(new Func1<HttpObject, HttpObject>() {
			@Override
			public HttpObject call(final HttpObject obj) {
				//	retain obj for blocking
				return ReferenceCountUtil.retain(obj);
			}})
		.toBlocking().toIterable().iterator();
		
//		while (itr.hasNext()) {
//			System.out.println( itr.next() );
//		}
		final HttpResponse response = (HttpResponse)itr.next();
		
		assertNotNull(response);
		ReferenceCountUtil.release(response);
		
		final HttpContent content = (HttpContent)itr.next();
		assertTrue(content instanceof LastHttpContent);

		final InputStream is = new ByteBufInputStream(content.content());
		final byte[] bytes = new byte[is.available()];
		is.read(bytes);
		ReferenceCountUtil.release(content);
		
		assertTrue(Arrays.equals(bytes, HttpTestServerHandler.CONTENT));
		
		server.stop();
	}
}
