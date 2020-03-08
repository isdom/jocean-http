package org.jocean.rpc;

public interface RpcBuilder {
    public <RPC> RPC build(final Class<RPC> rpcType);
}
