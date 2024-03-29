package org.jocean.http;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.core.MediaType;

import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.CharsetUtil;
import rx.Observable;
import rx.functions.Action1;
import rx.functions.Action2;
import rx.functions.Func2;

public class ContentUtil {

    private static final Logger LOG = LoggerFactory.getLogger(ContentUtil.class);

    private ContentUtil() {
        throw new IllegalStateException("No instances!");
    }

    private final static Action2<Object, OutputStream> _TOXML = (bean, os) -> MessageUtil.serializeToXml(bean, os);

    private final static Action2<Object, OutputStream> _TOJSON = (bean, os) -> MessageUtil.serializeToJson(bean, os);

    private final static Action2<Object, OutputStream> _TOTEXT = (bean, os) -> {
            try {
                os.write(bean.toString().getBytes(CharsetUtil.UTF_8));
            } catch (final IOException e) {
                LOG.warn("exception when serialize {} to text, detail: {}", bean, ExceptionUtils.exception2detail(e));
            }
        };

    private final static Action2<Object, OutputStream> _TOKV = (bean, os) -> {
            try {
                // TBD: parse bean's properties
                os.write(bean.toString().getBytes(CharsetUtil.UTF_8));
            } catch (final IOException e) {
                LOG.warn("exception when serialize {} to kv, detail: {}", bean, ExceptionUtils.exception2detail(e));
            }
        };

    public static final ContentEncoder TOXML = new ContentEncoder() {
        @Override
        public String contentType() {
            return MediaType.APPLICATION_XML;
        }

        @Override
        public Action2<Object, OutputStream> encoder() {
            return _TOXML;
        }};

    public static final ContentEncoder TOJSON = new ContentEncoder() {
        @Override
        public String contentType() {
            return MediaType.APPLICATION_JSON;
        }
        @Override
        public Action2<Object, OutputStream> encoder() {
            return _TOJSON;
        }};

    public static final ContentEncoder TOTEXT = new ContentEncoder() {
        @Override
        public String contentType() {
            return MediaType.TEXT_PLAIN;
        }
        @Override
        public Action2<Object, OutputStream> encoder() {
            return _TOTEXT;
        }};

    public static final ContentEncoder TOHTML = new ContentEncoder() {
        @Override
        public String contentType() {
            return MediaType.TEXT_HTML;
        }
        @Override
        public Action2<Object, OutputStream> encoder() {
            return _TOTEXT;
        }};

    public static final ContentEncoder TOFORM_URLENCODED = new ContentEncoder() {
        @Override
        public String contentType() {
            return MediaType.APPLICATION_FORM_URLENCODED;
        }
        @Override
        public Action2<Object, OutputStream> encoder() {
            return _TOTEXT;
        }};

    private final static Func2<InputStream, Class<?>, Object> _ASJSON = (is, type) -> MessageUtil.unserializeAsJson(is, type);

    public static final ContentDecoder ASJSON = new ContentDecoder() {
        @Override
        public String contentType() {
            return MediaType.APPLICATION_JSON;
        }
        @Override
        public Func2<InputStream, Class<?>, Object> decoder() {
            return _ASJSON;
        }};

    private final static Func2<InputStream, Class<?>, Object> _ASXML = (is, type) -> MessageUtil.unserializeAsXml(is, type);

    public static final ContentDecoder ASXML = new ContentDecoder() {
        @Override
        public String contentType() {
            return MediaType.APPLICATION_XML;
        }
        @Override
        public Func2<InputStream, Class<?>, Object> decoder() {
            return _ASXML;
        }};

    public static final ContentDecoder ASTEXTXML = new ContentDecoder() {
        @Override
        public String contentType() {
            return MediaType.TEXT_XML;
        }
        @Override
        public Func2<InputStream, Class<?>, Object> decoder() {
            return _ASXML;
        }};


    private final static Func2<InputStream, Class<?>, Object> _ASTEXT = (is, type) -> MessageUtil.parseContentAsString(is);

    public static final ContentDecoder ASTEXT = new ContentDecoder() {
        @Override
        public String contentType() {
            return MediaType.TEXT_PLAIN;
        }
        @Override
        public Func2<InputStream, Class<?>, Object> decoder() {
            return _ASTEXT;
        }};

    public static <CODEC extends WithContentType> CODEC selectCodec(final String[] mimeTypes, final CODEC[] codecs) {
        for (final String type : mimeTypes) {
            if (null != type) {
                for (final CODEC codec : codecs) {
                    if (null != codec && type.startsWith(codec.contentType())) {
                        return codec;
                    }
                }
            }
        }
        return null;
    }

    public static final ContentEncoder[] DEFAULT_ENCODERS = new ContentEncoder[]{
            ContentUtil.TOJSON,
            ContentUtil.TOXML,
            ContentUtil.TOTEXT,
            ContentUtil.TOHTML,
            ContentUtil.TOFORM_URLENCODED
            };

    public static final ContentDecoder[] DEFAULT_DECODERS = new ContentDecoder[]{
            ContentUtil.ASJSON,
            ContentUtil.ASXML,
            ContentUtil.ASTEXTXML
            };

    public static Observable<? extends MessageBody> tobody(final String contentType, final File file) {
        try (final InputStream is = new FileInputStream(file)) {
            final int length = is.available();
            final byte[] bytes = new byte[length];
            is.read(bytes);
            return Observable.just(new MessageBody() {
                @Override
                public HttpHeaders headers() {
                    return EmptyHttpHeaders.INSTANCE;
                }
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
                        public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                            final List<DisposableWrapper<ByteBuf>> dwbs = new ArrayList<>();
                            dwbs.add(DisposableWrapperUtil.wrap(Unpooled.wrappedBuffer(bytes), (Action1<ByteBuf>) null));
                            return dwbs;
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
