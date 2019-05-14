package org.jocean.netty.util;

import io.netty.util.ByteProcessor;

public class ByteProcessors {
    private ByteProcessors() {
        throw new IllegalStateException("No instances!");
    }

    /**
     * A {@link ByteProcessor} which finds the first occurrence of the specified byte array.
     */
    public static class IndexOfBytesProcessor implements ByteProcessor {
        public IndexOfBytesProcessor(final byte[] bytesToFind) {
            this._bytesToFind = bytesToFind;
        }

        @Override
        public boolean process(final byte value) {
            if (_bytesToFind[_currentMatchingIdx] == value) {
                _currentMatchingIdx++;
                if (_currentMatchingIdx >= _bytesToFind.length) {
                    return false;
                }
            }
            else {
                _currentMatchingIdx = 0;
            }
            return true;
        }

        private final byte[] _bytesToFind;
        private int _currentMatchingIdx = 0;
    }

    public static ByteProcessor indexOfBytes(final byte[] bytesToFind) {
        return new IndexOfBytesProcessor(bytesToFind);
    }
}
