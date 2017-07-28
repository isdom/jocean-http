package org.jocean.http;

import rx.Observable;

public interface WritePolicy {
    public void applyTo(final Outboundable outboundable);

    public interface Outboundable {
        public void setFlushPerWrite(final boolean isFlushPerWrite);
        public void setWriteBufferWaterMark(final int low, final int high);
        public Observable<Boolean> writability();
        public Observable<Object> sended();
    }
}
