package org.jocean.http.util;

import rx.Observable;

public interface SendedMessageAware {
    public void setSendedMessage(final Observable<? extends Object> sendedMessage);
}
