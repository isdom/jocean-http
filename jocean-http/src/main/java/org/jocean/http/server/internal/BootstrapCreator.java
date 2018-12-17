package org.jocean.http.server.internal;

import java.io.Closeable;

import io.netty.bootstrap.ServerBootstrap;

public interface BootstrapCreator extends Closeable {
    public ServerBootstrap newBootstrap();
}
