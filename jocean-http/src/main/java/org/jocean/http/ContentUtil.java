package org.jocean.http;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.ws.rs.core.MediaType;

import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import rx.Observable;
import rx.functions.Action1;
import rx.functions.Action2;

public class ContentUtil {

    private static final Logger LOG =
            LoggerFactory.getLogger(ContentUtil.class);

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

    private final static Action2<Object, OutputStream> _ASTEXT = new Action2<Object, OutputStream>() {
        @Override
        public void call(final Object bean, final OutputStream os) {
            try {
                os.write(bean.toString().getBytes(CharsetUtil.UTF_8));
            } catch (final IOException e) {
                LOG.warn("exception when serialize {} to text, detail: {}",
                        bean, ExceptionUtils.exception2detail(e));
            }
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
    public static final ContentEncoder TOTEXT = new ContentEncoder() {
        @Override
        public String contentType() {
            return MediaType.TEXT_PLAIN;
        }
        @Override
        public Action2<Object, OutputStream> encoder() {
            return _ASTEXT;
        }};

    public static final ContentEncoder TOHTML = new ContentEncoder() {
        @Override
        public String contentType() {
            return MediaType.TEXT_HTML;
        }
        @Override
        public Action2<Object, OutputStream> encoder() {
            return _ASTEXT;
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
