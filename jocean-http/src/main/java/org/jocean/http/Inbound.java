package org.jocean.http;

public interface Inbound {

    public interface Intraffic {
        public long durationFromRead();
        public long durationFromBegin();
        public long inboundBytes();
    }

    public Intraffic intraffic();
}
