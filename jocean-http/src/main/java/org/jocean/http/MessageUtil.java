package org.jocean.http;

import java.util.List;

import org.jocean.http.util.Nettys;
import org.jocean.http.util.ParamUtil;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.functions.Func1;
import rx.functions.Func2;

public class MessageUtil {
    public static <T> Observable<? extends T> decodeAs(final MessageUnit mu, final Class<T> type) {
        if (null != mu.contentType()) {
            if (mu.contentType().startsWith(HttpHeaderValues.APPLICATION_JSON.toString())) {
                return decodeJsonAs(mu, type);
            } else if (mu.contentType().startsWith("application/xml") || mu.contentType().startsWith("text/xml")) {
                return decodeXmlAs(mu, type);
            }
        }
        return Observable.error(new RuntimeException("can't decodeAs type:" + type));
    }

    public static <T> Observable<? extends T> decodeJsonAs(final MessageUnit mu, final Class<T> type) {
        return decodeContentAs(mu.content(), new Func2<ByteBuf, Class<T>, T>() {
            @Override
            public T call(final ByteBuf buf, Class<T> clazz) {
                return ParamUtil.parseContentAsJson(buf, clazz);
            }
        }, type);
    }

    public static <T> Observable<? extends T> decodeXmlAs(final MessageUnit mu, final Class<T> type) {
        return decodeContentAs(mu.content(), new Func2<ByteBuf, Class<T>, T>() {
            @Override
            public T call(final ByteBuf buf, Class<T> clazz) {
                return ParamUtil.parseContentAsXml(buf, clazz);
            }
        }, type);
    }

    // @Override
    // public <T> Observable<? extends T> decodeFormAs(final MessageUnit mu,
    // final Class<T> type) {
    // return Observable.error(new UnsupportedOperationException());
    // }
    private static <T> Observable<? extends T> decodeContentAs(
            final Observable<? extends DisposableWrapper<ByteBuf>> content, final Func2<ByteBuf, Class<T>, T> func,
            final Class<T> type) {
        return content.map(DisposableWrapperUtil.<ByteBuf>unwrap()).toList().map(new Func1<List<ByteBuf>, T>() {
            @Override
            public T call(final List<ByteBuf> bufs) {
                final ByteBuf buf = Nettys.composite(bufs);
                try {
                    return func.call(buf, type);
                } finally {
                    ReferenceCountUtil.release(buf);
                }
            }
        });
    }
}
