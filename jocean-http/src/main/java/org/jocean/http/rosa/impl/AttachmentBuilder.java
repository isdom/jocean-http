package org.jocean.http.rosa.impl;

import org.jocean.http.rosa.SignalClient.Attachment;

import io.netty.handler.codec.http.multipart.HttpData;
import rx.functions.Func1;

public interface AttachmentBuilder extends Func1<Attachment, HttpData> {

}
