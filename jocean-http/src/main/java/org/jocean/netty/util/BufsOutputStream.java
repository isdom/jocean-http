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
import io.netty.util.CharsetUtil;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

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
public class BufsOutputStream<T> extends OutputStream implements DataOutput {

    private static final Logger LOG = LoggerFactory.getLogger(BufsOutputStream.class);

    private final Func0<T> _allocator;
    private final Func1<T, ByteBuf> _tobuf;
    private final AtomicReference<Action1<T>> _outputRef = new AtomicReference<>(null);
    private T _data = null;

    private boolean _opened = true;
    private final DataOutputStream utf8out = new DataOutputStream(this);

    public BufsOutputStream(final Func0<T> allocator, final Func1<T, ByteBuf> tobuf) {
        this(allocator, tobuf, null);
    }

    /**
     * Creates a new stream which writes data to the specified {@code buffer}.
     */
    public BufsOutputStream(final Func0<T> allocator, final Func1<T, ByteBuf> tobuf, final Action1<T> output) {
        if (allocator == null || tobuf == null) {
            throw new NullPointerException("allocator || tobuf");
        }
        this._allocator = allocator;
        this._tobuf = tobuf;
        setOutput(output);
    }

    public void setOutput(final Action1<T> output) {
        this._outputRef.set(output);
    }

    private ByteBuf tobuf() {
        return this._tobuf.call(this._data);
    }

    private ByteBuf currentBuf() throws IOException {
        if (!this._opened) {
            throw new IOException("BufsOutputStream has closed!");
        }
        if (null == this._data) {
            return newBuf();
        } else {
            final ByteBuf buf = tobuf();
            return buf.isWritable() ? buf : newBuf();
        }
    }

    private ByteBuf newBuf() {
        flushData();
        this._data = this._allocator.call();
        return tobuf();
    }

    private void flushData() {
        if (null != this._data) {
            final T old = this._data;
            this._data = null;
            final Action1<T> output = this._outputRef.get();
            if (null != output) {
                try {
                    output.call(old);
                } catch (final Exception e) {
                    LOG.warn("exception when call output {}, detail: {}", output, ExceptionUtils.exception2detail(e));
                }
            } else {
                LOG.warn("current output is null, skip data: {}", old);
            }
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        flushData();
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
    @Override
    public synchronized void close() throws IOException {
        flushData();
        if (_opened) {
            _opened = false;
        }

        super.close();
    }
}
