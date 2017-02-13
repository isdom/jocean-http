package org.jocean.http.util;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.netty.BlobRepo.Blob;
import org.jocean.netty.util.ReferenceCountedHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Func1;

public class AsBlob implements Func1<HttpObject, Observable<? extends Blob>> {
    private static final Logger LOG =
        LoggerFactory.getLogger(AsBlob.class);

    private final ReferenceCountedHolder _holder;
    private final HttpMessageHolder _msgHolder;
    private final String _contentTypePrefix;
    
    private boolean _isMultipart = false;
    
    private HttpPostMultipartRequestDecoder _postDecoder = null;
    
    private static final HttpDataFactory HTTP_DATA_FACTORY =
            new DefaultHttpDataFactory(false);  // DO NOT use Disk
    
    public Action0 destroy() {
        return new Action0() {
            @Override
            public void call() {
                if (null != _postDecoder) {
                    // HttpPostRequestDecoder's destroy call HttpDataFactory.cleanRequestHttpDatas
                    //  so no need to cleanRequestHttpDatas outside
                    _postDecoder.destroy();
                    _postDecoder = null;
                }
            }
        };
    }
    
    public AsBlob(final String contentTypePrefix, 
            final ReferenceCountedHolder holder,
            final HttpMessageHolder msgHolder) {
        this._contentTypePrefix = contentTypePrefix;
        this._holder = holder;
        this._msgHolder = msgHolder;
    }
    
    public void setDiscardThreshold(final int discardThreshold) {
        if (null != this._postDecoder) {
            this._postDecoder.setDiscardThreshold(discardThreshold);
        }
    }

    public int currentUndecodedSize() {
        final HttpPostMultipartRequestDecoder postDecoder = this._postDecoder;
        if (null != postDecoder) {
            try {
                final Field chunkField = postDecoder.getClass().getDeclaredField("undecodedChunk");
                if (null != chunkField) {
                    chunkField.setAccessible(true);
                    final ByteBuf undecodedChunk = (ByteBuf)chunkField.get(postDecoder);
                    if (null != undecodedChunk) {
                        return undecodedChunk.capacity();
                    }
                } else {
                    LOG.warn("not found HttpPostMultipartRequestDecoder.undecodedChunk field");
                }
            } catch (Exception e) {
                LOG.warn("exception when get undecodedChunk, detail: {}",
                        ExceptionUtils.exception2detail(e));
            }
        }
        return 0;
    }

    @Override
    public Observable<? extends Blob> call(final HttpObject msg) {
        if (msg instanceof HttpRequest) {
            final HttpRequest request = (HttpRequest)msg;
            if ( request.method().equals(HttpMethod.POST)
                    && HttpPostRequestDecoder.isMultipart(request)) {
                _isMultipart = true;
                _postDecoder = new HttpPostMultipartRequestDecoder(
                        HTTP_DATA_FACTORY, request);
                try {
                    final Field chunkField = _postDecoder.getClass().getDeclaredField("undecodedChunk");
                    if (null != chunkField) {
                        chunkField.setAccessible(true);
                        chunkField.set(_postDecoder, PooledByteBufAllocator.DEFAULT.directBuffer());
                    } else {
                        LOG.warn("not found HttpPostMultipartRequestDecoder.undecodedChunk field");
                    }
                } catch (Exception e) {
                    LOG.warn("exception when set undecodedChunk to null, detail: {}",
                            ExceptionUtils.exception2detail(e));
                }
                
                LOG.info("{} isMultipart", msg);
            } else {
                _isMultipart = false;
                LOG.info("{} is !NOT! Multipart", msg);
            }
        }
        if (msg instanceof HttpContent && _isMultipart && (null != _postDecoder)) {
            return onNext4Multipart((HttpContent)msg);
        } else {
            return Observable.empty();
        }
    }

    private Observable<? extends Blob> onNext4Multipart(
            final HttpContent content) {
        try {
            _postDecoder.offer(content);
        } catch (ErrorDataDecoderException e) {
            LOG.warn("exception when postDecoder.offer, detail: {}", 
                    ExceptionUtils.exception2detail(e));
        } finally {
            if (null != this._msgHolder) {
                this._msgHolder.releaseHttpContent(content);
            }
        }
        final List<Blob> blobs = new ArrayList<>();
        try {
            while (_postDecoder.hasNext()) {
                final InterfaceHttpData data = _postDecoder.next();
                if (data != null) {
                    try {
                        final Blob blob = this._holder.hold(processHttpData(data));
                        if (null != blob) {
                            blobs.add(blob);
                            LOG.info("onNext4Multipart: add Blob {}", blob);
                        }
                    } finally {
                        data.release();
                    }
                }
            }
        } catch (EndOfDataDecoderException e) {
            LOG.warn("exception when postDecoder.hasNext, detail: {}", 
                    ExceptionUtils.exception2detail(e));
        }
        return blobs.isEmpty() ? Observable.<Blob>empty() : Observable.from(blobs);
    }

    private Blob processHttpData(final InterfaceHttpData data) {
        if (data.getHttpDataType().equals(
            InterfaceHttpData.HttpDataType.FileUpload)) {
            final FileUpload fileUpload = (FileUpload)data;
            
            //  if _contentTypePrefix is not null, try to match
            if (null != _contentTypePrefix 
                && !fileUpload.getContentType().startsWith(_contentTypePrefix)) {
                LOG.info("fileUpload's contentType is {}, NOT match prefix {}, so ignore",
                        fileUpload.getContentType(), _contentTypePrefix);
                return null;
            }
                
            LOG.info("processHttpData: fileUpload's content is {}", Nettys.dumpByteBufHolder(fileUpload));
            final String contentType = fileUpload.getContentType();
            final String filename = fileUpload.getFilename();
            final String name = fileUpload.getName();
            return buildBlob(fileUpload, contentType, filename, name);
        } else {
            LOG.info("InterfaceHttpData ({}) is NOT fileUpload, so ignore", data);
        }
        return null;
    }
    
    private static Blob buildBlob(final FileUpload fileUpload,
            final String contentType, 
            final String filename,
            final String name) {
        final int length = fileUpload.content().readableBytes();
        return new Blob() {
            @Override
            public String toString() {
                final StringBuilder builder = new StringBuilder();
                builder.append("Blob [name=").append(name())
                        .append(", filename=").append(filename())
                        .append(", contentType=").append(contentType())
                        .append(", content.length=").append(length)
                        .append("]");
                return builder.toString();
            }
            
            @Override
            public String contentType() {
                return contentType;
            }
            @Override
            public String name() {
                return name;
            }
            @Override
            public String filename() {
                return filename;
            }

            @Override
            public int refCnt() {
                return fileUpload.refCnt();
            }

            @Override
            public Blob retain() {
                fileUpload.retain();
                return this;
            }

            @Override
            public Blob retain(int increment) {
                fileUpload.retain(increment);
                return this;
            }

            @Override
            public Blob touch() {
                fileUpload.touch();
                return this;
            }

            @Override
            public Blob touch(Object hint) {
                fileUpload.touch(hint);
                return this;
            }

            @Override
            public boolean release() {
                return fileUpload.release();
            }

            @Override
            public boolean release(int decrement) {
                return fileUpload.release(decrement);
            }

            @Override
            public InputStream inputStream() {
                return new ByteBufInputStream(fileUpload.content().slice(), false);
            }

            @Override
            public int contentLength() {
                return length;
            }};
    }
}
