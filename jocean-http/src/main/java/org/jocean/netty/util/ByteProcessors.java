package org.jocean.netty.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.ByteProcessor;

public class ByteProcessors {
    private ByteProcessors() {
        throw new IllegalStateException("No instances!");
    }

    private static final Logger LOG = LoggerFactory.getLogger(ByteProcessors.class);

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
                LOG.trace("[{}] == {}", _currentMatchingIdx - 1, value);
                if (_currentMatchingIdx >= _bytesToFind.length) {
                    return false;
                }
            }
            else {
                LOG.trace("[{}]({}) != {}", _currentMatchingIdx, _bytesToFind[_currentMatchingIdx], value);
                _currentMatchingIdx = 0;
                if (_bytesToFind[_currentMatchingIdx] == value) {
                    _currentMatchingIdx++;
                    LOG.trace("[{}] == {}", _currentMatchingIdx - 1, value);
                    if (_currentMatchingIdx >= _bytesToFind.length) {
                        return false;
                    }
                }
            }
            return true;
        }

        public int matchedCount() {
            return _currentMatchingIdx;
        }

        private final byte[] _bytesToFind;
        private int _currentMatchingIdx = 0;
    }

    public static IndexOfBytesProcessor indexOfBytes(final byte[] bytesToFind) {
        return new IndexOfBytesProcessor(bytesToFind);
    }
}
