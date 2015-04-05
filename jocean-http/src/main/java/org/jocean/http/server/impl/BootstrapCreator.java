package org.jocean.http.server.impl;

import java.io.Closeable;

import io.netty.bootstrap.ServerBootstrap;

public interface BootstrapCreator extends Closeable {
    public ServerBootstrap newBootstrap();
}
