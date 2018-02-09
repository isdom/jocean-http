package org.jocean.netty.util;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.CharsetUtil;
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
public class ByteBufArrayOutputStream extends OutputStream implements DataOutput {

    private static final Logger LOG
        = LoggerFactory.getLogger(ByteBufArrayOutputStream.class);
    
    private static final Func0<ByteBuf> _DEFAULT_NEW_BUFFER = new Func0<ByteBuf>() {
        @Override
        public ByteBuf call() {
            return PooledByteBufAllocator.DEFAULT.buffer(8192, 8192);
        }};
    
    private final Func0<ByteBuf> _newBuffer;
    private final List<ByteBuf> _bufs = new ArrayList<>();
    
    private boolean _opened = true;
    private final DataOutputStream utf8out = new DataOutputStream(this);

    public ByteBufArrayOutputStream() {
        this(_DEFAULT_NEW_BUFFER);
    }
    
    /**
     * Creates a new stream which writes data to the specified {@code buffer}.
     */
    public ByteBufArrayOutputStream(final Func0<ByteBuf> newBuffer) {
        if (newBuffer == null) {
            throw new NullPointerException("newBuffer");
        }
        this._newBuffer = newBuffer;
    }

    private ByteBuf lastBuf() {
        if (!_opened) {
            throw new RuntimeException("ByteBufArrayOutputStream has closed!");
        }
        if (_bufs.isEmpty()) {
            return addBuf();
        }
        final ByteBuf lastbuf = _bufs.get(_bufs.size()-1);
        return lastbuf.isWritable() ? lastbuf : addBuf();
    }
    
    private ByteBuf addBuf() {
        final ByteBuf newbuf = this._newBuffer.call();
        _bufs.add(newbuf);
        return newbuf;
    }

    @Override
    public synchronized void write(final byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            final ByteBuf buffer = lastBuf();
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
        lastBuf().writeByte(b);
    }

    @Override
    public synchronized void writeBoolean(final boolean v) throws IOException {
        lastBuf().writeBoolean(v);
    }

    @Override
    public synchronized void writeByte(final int v) throws IOException {
        lastBuf().writeByte(v);
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
     * Returns the buffer where this stream is writing data.
     */
    public synchronized ByteBuf[] buffers() {
        if (!_opened) {
            throw new RuntimeException("ByteBufArrayOutputStream has closed!");
        }
        try {
            return _bufs.toArray(new ByteBuf[0]);
        } finally {
            _bufs.clear();
        }
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
        // release and remove all valid _bufs
        if (_opened) {
            try {
                final ByteBuf[] bufs = buffers();
                if (bufs.length > 0) {
                    LOG.warn("{} bufs left when stream closed, release these bufs", bufs.length);
                    // release all
                    for (ByteBuf b : bufs) {
                        b.release();
                    }
                }
            } finally {
                _opened = false;
            }
        }
        
        super.close();
    }
}
