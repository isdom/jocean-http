package org.jocean.http.server.impl;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;

import java.io.IOException;

public abstract class AbstractBootstrapCreator implements BootstrapCreator {

    protected abstract void initializeBootstrap(final ServerBootstrap bootstrap);
    
    protected AbstractBootstrapCreator(
            final EventLoopGroup parentGroup,
            final EventLoopGroup childGroup) {
        this._parentGroup = parentGroup;
        this._childGroup = childGroup;
    }
    
    @Override
    public void close() throws IOException {
        this._parentGroup.shutdownGracefully();
        this._childGroup.shutdownGracefully();
    }

    @Override
    public ServerBootstrap newBootstrap() {
        final ServerBootstrap b = new ServerBootstrap()
            .group(this._parentGroup, this._childGroup);
        initializeBootstrap(b);
        return b;
    }
    
    private final EventLoopGroup _parentGroup;
    private final EventLoopGroup _childGroup;
}
