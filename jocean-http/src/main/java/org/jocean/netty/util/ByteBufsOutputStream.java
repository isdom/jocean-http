package org.jocean.netty.util;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.CharsetUtil;
import rx.functions.Action1;
import rx.functions.Func0;

/**
 * An {@link OutputStream} which writes data to a {@link ByteBuf}.
 * <p>
 * A write operation against this stream will occur at the {@code writerIndex}
 * of its underlying buffer and the {@code writerIndex} will increase during
 * the write operation.
 * <p>
 * This stream implements {@link DataOutput} for your convenience.
 * The endianness of the stream is not always big endian but depends on
 * the endianness of the underlying buffer.
 *
 * @see ByteBufInputStream
 */
public class ByteBufsOutputStream extends OutputStream implements DataOutput {

    private static final Logger LOG
        = LoggerFactory.getLogger(ByteBufsOutputStream.class);
    
    private static final Func0<ByteBuf> _DEFAULT_NEW_BUFFER = new Func0<ByteBuf>() {
        @Override
        public ByteBuf call() {
            return PooledByteBufAllocator.DEFAULT.buffer(8192, 8192);
        }};
    
    private static final Action1<ByteBuf> _DEFAULT_ON_BUFFER = new Action1<ByteBuf>() {
        @Override
        public void call(final ByteBuf buf) {
            buf.release();
        }};
    private final Func0<ByteBuf> _newBuffer;
    private final AtomicReference<Action1<ByteBuf>> _onBufferRef = new AtomicReference<>();
    private ByteBuf _buf = null;
    
    private boolean _opened = true;
    private final DataOutputStream utf8out = new DataOutputStream(this);

    public ByteBufsOutputStream(final Action1<ByteBuf> onBuffer) {
        this(_DEFAULT_NEW_BUFFER, onBuffer);
    }
    
    /**
     * Creates a new stream which writes data to the specified {@code buffer}.
     */
    public ByteBufsOutputStream(final Func0<ByteBuf> newBuffer, final Action1<ByteBuf> onBuffer) {
        if (newBuffer == null) {
            throw new NullPointerException("newBuffer");
        }
        this._newBuffer = newBuffer;
        setOnBuffer(onBuffer);
    }
    
    public void setOnBuffer(final Action1<ByteBuf> onBuffer) {
        this._onBufferRef.set(onBuffer != null ? onBuffer : _DEFAULT_ON_BUFFER);
    }

    private ByteBuf currentBuf() {
        if (!_opened) {
            throw new RuntimeException("ByteBufArrayOutputStream has closed!");
        }
        if (null == this._buf) {
            return newBuf();
        }
        return this._buf.isWritable() ? this._buf : newBuf();
    }
    
    private ByteBuf newBuf() {
        flushBuf();
        this._buf = this._newBuffer.call();
        return this._buf;
    }

    private void flushBuf() {
        if (null != this._buf) {
            final ByteBuf old = this._buf;
            this._buf = null;
            final Action1<ByteBuf> onBuffer = this._onBufferRef.get();
            try {
                onBuffer.call(old);
            } catch (Exception e) {
                LOG.warn("exception when call onBuffer {}, detail: {}", onBuffer, ExceptionUtils.exception2detail(e));
            }
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        flushBuf();
    }

    @Override
    public synchronized void write(final byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            final ByteBuf buffer = currentBuf();
            final int size = Math.min(buffer.writableBytes(), len);
            buffer.writeBytes(b, off, size);
            off += size;
            len -= size;
        }
    }

    @Override
    public void write(final byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public synchronized void write(final int b) throws IOException {
        currentBuf().writeByte(b);
    }

    @Override
    public synchronized void writeBoolean(final boolean v) throws IOException {
        currentBuf().writeBoolean(v);
    }

    @Override
    public synchronized void writeByte(final int v) throws IOException {
        currentBuf().writeByte(v);
    }

    @Override
    public void writeBytes(final String s) throws IOException {
        write(s.getBytes(CharsetUtil.US_ASCII));
    }

    @Override
    public void writeChar(final int v) throws IOException {
        writeShort(v);
    }

    @Override
    public synchronized void writeChars(final String s) throws IOException {
        final int len = s.length();
        for (int i = 0 ; i < len ; i ++) {
            writeChar(s.charAt(i));
        }
    }

    @Override
    public void writeDouble(final double v) throws IOException {
        writeLong(Double.doubleToRawLongBits(v));
    }

    @Override
    public void writeFloat(final float v) throws IOException {
        writeInt(Float.floatToRawIntBits(v));
    }

    @Override
    public synchronized void writeInt(final int v) throws IOException {
        writeShort((short) (v >>> 16));
        writeShort((short) v);
    }

    @Override
    public synchronized void writeLong(final long v) throws IOException {
        writeInt((int) (v >>> 32));
        writeInt((int) v);
    }

    @Override
    public synchronized void writeShort(final int v) throws IOException {
        writeByte((byte) (v >>> 8));
        writeByte((byte) v);
    }

    @Override
    public synchronized void writeUTF(final String s) throws IOException {
        utf8out.writeUTF(s);
    }

    /**
     * Closes this output stream and releases any system resources
     * associated with this stream. The general contract of <code>close</code>
     * is that it closes the output stream. A closed stream cannot perform
     * output operations and cannot be reopened.
     * <p>
     *
     * @exception  IOException  if an I/O error occurs.
     */
    public synchronized void close() throws IOException {
        flushBuf();
        if (_opened) {
            _opened = false;
        }
        
        super.close();
    }
}
