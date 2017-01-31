package org.jocean.http.rosa.wechat;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.POST;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jocean.http.Feature;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.rosa.SignalClient;
import org.jocean.http.rosa.impl.DefaultSignalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.Files;
import com.google.common.primitives.Bytes;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.CharsetUtil;
//import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_DISPOSITION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;

public class WechatUploadMediaDemo {
    final static String CONTENT_DISPOSITION = "Content-Disposition";
    
    private static UploadMediaResponse uploadMedia(
            final SignalClient signal,
            final URI wechaturi,
            final String accessToken,
            final String type) throws IOException {
        final UploadMediaRequest req = new UploadMediaRequest();
        req.setAccessToken(accessToken);
        req.setType(type);
        
//        final String path = "/media/upload?access_token=" + accessToken + "&type=" + type;
        // final File file1 = new File("/Users/isdom/4702b3d3084a3a716368266ebc8268d5.amr");
        final File file1 = new File("/Users/isdom/d0ae0814a4f183c46cb6572225043281-1.mp3");
        final String multipartDataBoundary = "wfWiEWrgEFA9A78512weF7106A";
        final String name = "media";
        
        final String filename = file1.getName();
        
        final String part = "--" + multipartDataBoundary + "\r\n" +
                        CONTENT_DISPOSITION + ": form-data; name=\""+ name + "\"; filename=\""+ filename 
                        +"\"" + "\r\n" +
                        CONTENT_LENGTH + ": " + file1.length() + "\r\n" +
//                        CONTENT_TYPE + ": image/jpeg" + "\r\n" +
                        CONTENT_TYPE + ": audio/mp3" + "\r\n" +
//                        CONTENT_TYPE + ": application/octet-stream" + "\r\n" +
                        CONTENT_TRANSFER_ENCODING + ": binary" + "\r\n" +
                        "\r\n";
        final String end = "\r\n--" + multipartDataBoundary + "--\r\n";
        
        req.setBody( 
            Bytes.concat(part.getBytes(CharsetUtil.UTF_8), 
                Files.toByteArray(file1), 
                end.getBytes(CharsetUtil.UTF_8))
                );
        
        req.setContentType("multipart/form-data; boundary=" + multipartDataBoundary);
        req.setContentLength(Integer.toString(req.getBody().length));
        
        final UploadMediaResponse resp = 
            signal.<UploadMediaResponse>defineInteraction(req,
                Feature.ENABLE_LOGGING,
                Feature.ENABLE_COMPRESSOR,
                new SignalClient.UsingUri(wechaturi),
                new SignalClient.UsingMethod(POST.class),
                new SignalClient.ConvertResponseTo(UploadMediaResponse.class)
                )
        .timeout(10, TimeUnit.SECONDS)
        .toBlocking().single();
        return resp;
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(WechatUploadMediaDemo.class);
    
    public static void main(String[] args) throws Exception {
//        final SslContext sslCtx = SslContextBuilder.forClient().build();
//        final Feature sslfeature = new Feature.ENABLE_SSL(sslCtx);
//        final SignalClient signal = new DefaultSignalClient(new DefaultHttpClient(sslfeature));
        final SignalClient signal = new DefaultSignalClient(new DefaultHttpClient());
        
        final URI uri = new URI("http://api.weixin.qq.com/cgi-bin");
        final String accessToken = "Zpi5Hco4LkYJqEhZ7GtAyjNuwIU25ybG719uqWWg6oXHhwLS-T9CngM2FMU5922w5MSpvG95OQyPs56xd_3DWY-1nod5asQB0cUTuELtqmXIzqrTlfbmJhWNbw9s4jxyPWChAEATAG";
        final UploadMediaResponse resp = uploadMedia(signal, uri, accessToken, "voice");
        Thread.sleep(1000);
        System.out.println("resp : " + resp);
        Thread.sleep(1000);
        
        /*
        final String accessToken = "pN7eZ9dN7fTLtMLi1ahyAhYo2cUTwi3HMGHtLwGmHCWCmsYDTXMqKgs8bnJngw8lB1MvJkrrWTkffeKAD1tVeN5t7TMsAK7ZSoHrc8RC08on5_201G69TW_urN7_imJbLHAdAGATPI";
        String url = "http://api.weixin.qq.com/cgi-bin/media/upload?access_token=" + accessToken + "&type=image";
        final HttpPost httpPost = new HttpPost(url);
        LOG.debug("post url:"+url);
//        httpPost.setHeader("User-Agent","SOHUWapRebot");
//        httpPost.setHeader("Accept-Language","zh-cn,zh;q=0.5");
//        httpPost.setHeader("Accept-Charset","GBK,utf-8;q=0.7,*;q=0.7");
//        httpPost.setHeader("Connection","keep-alive");
        
        final MultipartEntity mutiEntity = new MultipartEntity();
        final File file = new File("/Users/isdom/Documents/马天阳的资料/英语材料/Billy Is Hiding/small.jpg");
        mutiEntity.addPart("media", new FileBody(file));
         
        httpPost.setEntity(mutiEntity);
        final HttpClient client = HttpClients.createDefault();
        HttpResponse  httpResponse = client.execute(httpPost);
        HttpEntity httpEntity =  httpResponse.getEntity();
        String content = EntityUtils.toString(httpEntity);
        LOG.debug("resp: {}", content);
        */
    }
}
