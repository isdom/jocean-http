package org.jocean.http;

import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpObject;

/**
 * @author isdom
 * Flag Interface for flush when sending
 */
public interface DoFlush {
    public static class Util {
        private static final FlushOnly FLUSH_ONLY = new FlushOnly();
        
        public static HttpObject flushOnly() {
            return FLUSH_ONLY;
        }
        
        private static class FlushOnly implements HttpObject, DoFlush {
            @Override
            public DecoderResult decoderResult() {
                return null;
            }

            @Override
            public void setDecoderResult(DecoderResult result) {
            }

            @Override
            public DecoderResult getDecoderResult() {
                return null;
            }
        }
    }
}
