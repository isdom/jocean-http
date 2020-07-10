package org.jocean.rpc;


/**
 * @author isdom
 *   Remove in the future, instead by @RpcFacade
 */
@Deprecated
interface RpcBuilder {
    public <RPC> RPC build(final Class<RPC> rpcType);
}
