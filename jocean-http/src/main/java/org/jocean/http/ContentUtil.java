package org.jocean.http;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

import javax.ws.rs.core.MediaType;

import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import rx.Observable;
import rx.functions.Action1;
import rx.functions.Action2;

public class ContentUtil {

    private ContentUtil() {
        throw new IllegalStateException("No instances!");
    }

    private final static Action2<Object, OutputStream> _ASXML = new Action2<Object, OutputStream>() {
        @Override
        public void call(final Object bean, final OutputStream os) {
            MessageUtil.serializeToXml(bean, os);
        }};

    private final static Action2<Object, OutputStream> _ASJSON = new Action2<Object, OutputStream>() {
        @Override
        public void call(final Object bean, final OutputStream os) {
            MessageUtil.serializeToJson(bean, os);
        }};

    public static final ContentEncoder TOXML = new ContentEncoder() {
        @Override
        public String contentType() {
            return MediaType.APPLICATION_XML;
        }

        @Override
        public Action2<Object, OutputStream> encoder() {
            return _ASXML;
        }};
    public static final ContentEncoder TOJSON = new ContentEncoder() {
        @Override
        public String contentType() {
            return MediaType.APPLICATION_JSON;
        }
        @Override
        public Action2<Object, OutputStream> encoder() {
            return _ASJSON;
        }};

    public static Observable<? extends MessageBody> tobody(final String contentType, final File file) {
        try (final InputStream is = new FileInputStream(file)) {
            final int length = is.available();
            final byte[] bytes = new byte[length];
            is.read(bytes);
            return Observable.just(new MessageBody() {
                @Override
                public String contentType() {
                    return contentType;
                }
                @Override
                public int contentLength() {
                    return length;
                }
                @Override
                public Observable<? extends ByteBufSlice> content() {
                    return Observable.<ByteBufSlice>just(new ByteBufSlice() {
                        @Override
                        public Observable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                            return Observable.just(
                                    DisposableWrapperUtil.wrap(Unpooled.wrappedBuffer(bytes), (Action1<ByteBuf>) null));
                        }

                        @Override
                        public void step() {
                        }
                    });
                }});
        } catch (final Exception e) {
            return Observable.error(e);
        }
    }
}
