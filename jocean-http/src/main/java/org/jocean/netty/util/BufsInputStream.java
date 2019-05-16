package org.jocean.netty.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.util.ByteProcessor;
import rx.functions.Action1;
import rx.functions.Actions;
import rx.functions.Func1;

public class BufsInputStream<T> extends InputStream /*implements DataInput*/ {

    private static final Logger LOG = LoggerFactory.getLogger(BufsInputStream.class);

    private final List<T> _holders = new ArrayList<T>();
    private final Func1<T, ByteBuf> _holder2buf;
    private final Action1<T> _onreaded;

    public boolean _opened = true;
    public boolean _eos = false;

    private ByteBuf currentBuf() throws IOException {
        if (!this._opened) {
            throw new IOException("BufsInputStream has closed!");
        }
        while (!this._holders.isEmpty()) {
            final ByteBuf b = this._holder2buf.call(this._holders.get(0));
            if (!b.isReadable()) {
                LOG.debug("{} is not readable, push to onreaded", b);
                this._onreaded.call(this._holders.remove(0));
            } else {
                return b;
            }
        }
        if (!this._eos) {
            LOG.debug("no more data, throw NoDataException");
            throw new NoDataException();
        } else {
            LOG.debug("no more data AND end of stream");
            return null;
        }
    }

    public BufsInputStream(final Func1<T, ByteBuf> tobuf) {
        this(tobuf, null);
    }

    @SuppressWarnings("unchecked")
    public BufsInputStream(final Func1<T, ByteBuf> holder2buf, final Action1<T> onreaded) {
        this._holder2buf = holder2buf;
        this._onreaded = null != onreaded ? onreaded : (Action1<T>)Actions.empty();
    }

    public void appendBuf(final T holder) {
        this._holders.add(holder);
        LOG.debug("appendBuf: {}", holder);
    }

    public void appendBufs(final Collection<? extends T> holders) {
        this._holders.addAll(holders);
        LOG.debug("appendBufs with count {}", holders.size());
    }

    public void appendIterable(final Iterable<? extends T> holders) {
        int size = 0;
        for (final Iterator<? extends T> iter = holders.iterator(); iter.hasNext(); ) {
            appendBuf(iter.next());
            size++;
        }
        LOG.debug("appendBufs(Iterable) with count {}", size);
    }

    public void markEOS() {
        this._eos = true;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            // The Closable interface says "If the stream is already closed then invoking this method has no effect."
            if (_opened) {
                _opened = false;
                _holders.clear();
            }
        }
    }

    @Override
    public int available() {
        int available = 0;
        for (final T buf : this._holders) {
            available += this._holder2buf.call(buf).readableBytes();
        }
        return available;
    }

    @Override
    public void mark(final int readlimit) {
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public int read() throws IOException {
        final ByteBuf buf = currentBuf();
        return buf != null ? buf.readByte() & 0xff : -1;
    }

    @Override
    public int read(final byte[] b, int off, int len) throws IOException {
        int readed = 0;
        try {
            while (len > 0) {
                final ByteBuf buf = currentBuf();
                if (null == buf) {
                    // eos
                    return 0 == readed ? -1 : readed;
                }
                final int toread = Math.min(len, buf.readableBytes());
                buf.readBytes(b, off, toread);
                off += toread;
                len -= toread;
                readed += toread;
            }
        } catch (final IOException e) {
            if (readed == 0) {
                LOG.debug("BufsInputStream's read(b,off,len): {} without any bytes, re-throw it", e.getMessage());
                throw e;
            }
        }
        LOG.debug("BufsInputStream's read(b,off,len): read {} bytes", readed);
        return readed;
    }

    @Override
    public void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = 0;
        try {
            while (n > 0) {
                if (-1 != read()) {
                    n--;
                    skipped++;
                } else {
                    return skipped;
                }
            }
        } catch (final IOException e) {
            if (skipped == 0) {
                throw e;
            }
        }
        return skipped;
//        if (n > Integer.MAX_VALUE) {
//            return skipBytes(Integer.MAX_VALUE);
//        } else {
//            return skipBytes((int) n);
//        }
    }

    /**
     * Iterates over the readable bytes of this buffer with the specified {@code processor} in ascending order.
     *
     * @return {@code -1} if the processor iterated to or beyond the end of the readable bytes.
     *         The last-visited index If the {@link ByteProcessor#process(byte)} returned {@code false}.
     */
    public int forEachByte(final ByteProcessor processor) {
        int idx = 0;
        for (final T c :this._holders) {
            final ByteBuf b = this._holder2buf.call(c);
            if (b.isReadable()) {
                final int bIdx = b.forEachByte(processor);
                if (bIdx == -1) {
                    idx += b.readableBytes();
                }
                else {
                    return idx + bIdx - b.readerIndex();
                }
            }
        }

        return -1;
    }

    /*
    @Override
    public boolean readBoolean() throws IOException {
        checkAvailable(1);
        return read() != 0;
    }

    @Override
    public byte readByte() throws IOException {
        return currentBuf().readByte();
    }

    @Override
    public char readChar() throws IOException {
        return (char) readShort();
    }

    @Override
    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    @Override
    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public void readFully(final byte[] b) throws IOException {
        readFully(b, 0, b.length);
    }

    @Override
    public void readFully(final byte[] b, final int off, final int len) throws IOException {
        checkAvailable(len);
        read(b, off, len);
    }

    @Override
    public int readInt() throws IOException {
        checkAvailable(4);
        return buffer.readInt();
    }

    private final StringBuilder lineBuf = new StringBuilder();

    @Override
    public String readLine() throws IOException {
        lineBuf.setLength(0);

        loop: while (true) {
            if (!buffer.isReadable()) {
                return lineBuf.length() > 0 ? lineBuf.toString() : null;
            }

            final int c = buffer.readUnsignedByte();
            switch (c) {
                case '\n':
                    break loop;

                case '\r':
                    if (buffer.isReadable() && (char) buffer.getUnsignedByte(buffer.readerIndex()) == '\n') {
                        buffer.skipBytes(1);
                    }
                    break loop;

                default:
                    lineBuf.append((char) c);
            }
        }

        return lineBuf.toString();
    }

    @Override
    public long readLong() throws IOException {
        checkAvailable(8);
        return buffer.readLong();
    }

    @Override
    public short readShort() throws IOException {
        checkAvailable(2);
        return buffer.readShort();
    }

    @Override
    public String readUTF() throws IOException {
        return DataInputStream.readUTF(this);
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return readByte() & 0xff;
    }

    @Override
    public int readUnsignedShort() throws IOException {
        return readShort() & 0xffff;
    }

    @Override
    public int skipBytes(final int n) throws IOException {
        final int nBytes = Math.min(available(), n);
        buffer.skipBytes(nBytes);
        return nBytes;
    }

    @Override
    public void readFully(final byte[] b, final int off, final int len) throws IOException {
        checkAvailable(len);
        read(b, off, len);
    }

    private void checkAvailable(final int fieldSize) throws IOException {
        if (fieldSize < 0) {
            throw new IndexOutOfBoundsException("fieldSize cannot be a negative number");
        }
        if (fieldSize > available()) {
//            throw new EOFException("fieldSize is too long! Length is " + fieldSize
//                    + ", but maximum is " + available());
            throw new NoDataException();
        }
    }
    */
}
