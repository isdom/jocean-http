/**
 *
 */
package org.jocean.netty.util;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ByteProcessor;
import io.netty.util.CharsetUtil;

/**
 * @author isdom
 *
 */
public class BufsInputStreamTestCase {

    /**
     * Test method for {@link org.jocean.netty.util.BufsInputStream#forEachByte(io.netty.util.ByteProcessor)}.
     */
    @Test
    public final void testForEachByte1() {
        final BufsInputStream<ByteBuf> is = new BufsInputStream<>(b -> b, b -> {});

        is.appendBuf(Unpooled.wrappedBuffer(",123".getBytes(CharsetUtil.UTF_8)));

        assertEquals(0, is.forEachByte(ByteProcessor.FIND_COMMA));
    }

    /**
     * Test method for {@link org.jocean.netty.util.BufsInputStream#forEachByte(io.netty.util.ByteProcessor)}.
     */
    @Test
    public final void testForEachByte2() {
        final BufsInputStream<ByteBuf> is = new BufsInputStream<>(b -> b, b -> {});

        is.appendBufs(Arrays.asList(
                Unpooled.wrappedBuffer("012".getBytes(CharsetUtil.UTF_8)),
                Unpooled.wrappedBuffer(",123".getBytes(CharsetUtil.UTF_8))
                ));

        assertEquals(3, is.forEachByte(ByteProcessor.FIND_COMMA));
    }

    /**
     * Test method for {@link org.jocean.netty.util.BufsInputStream#forEachByte(io.netty.util.ByteProcessor)}.
     */
    @Test
    public final void testForEachByte3() {
        final BufsInputStream<ByteBuf> is = new BufsInputStream<>(b -> b, b -> {});

        is.appendBufs(Arrays.asList(
                Unpooled.wrappedBuffer("012".getBytes(CharsetUtil.UTF_8)),
                Unpooled.wrappedBuffer("123".getBytes(CharsetUtil.UTF_8)),
                Unpooled.wrappedBuffer("456".getBytes(CharsetUtil.UTF_8))
                ));

        assertEquals(-1, is.forEachByte(ByteProcessor.FIND_COMMA));
    }

    /**
     * Test method for {@link org.jocean.netty.util.BufsInputStream#forEachByte(io.netty.util.ByteProcessor)}.
     */
    @Test
    public final void testForEachByte4() {
        final BufsInputStream<ByteBuf> is = new BufsInputStream<>(b -> b, b -> {});

        assertEquals(-1, is.forEachByte(ByteProcessor.FIND_COMMA));
    }
}
