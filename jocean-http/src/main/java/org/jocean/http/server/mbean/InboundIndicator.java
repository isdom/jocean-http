package org.jocean.http.server.mbean;

import io.netty.channel.ServerChannel;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.jocean.http.Feature;
import org.jocean.http.util.Nettys.ServerChannelAware;

public class InboundIndicator extends Feature.AbstractFeature0 
    implements InboundMXBean, ServerChannelAware {

    public InboundIndicator() {
        String hostname = "unknown";
        String hostip = "0.0.0.0";
        try {
            hostname = InetAddress.getLocalHost().getHostName();
            hostip = InetAddress.getByName(hostname).getHostAddress();
        } catch (UnknownHostException e) {
        }
        this._hostname = hostname;
        this._hostip = hostip;
    }
    
    @Override
    public String getHost() {
        return _hostname;
    }

    @Override
    public String getHostIp() {
        return this._hostip;
    }
    
    @Override
    public String getBindIp() {
        return this._bindip;
    }
    
    @Override
    public int getPort() {
        return this._port;
    }
    
    @Override
    public void setServerChannel(final ServerChannel serverChannel) {
        if (serverChannel.localAddress() instanceof InetSocketAddress) {
            final InetSocketAddress addr = (InetSocketAddress)serverChannel.localAddress();
            this._port = addr.getPort();
            this._bindip = null != addr.getAddress()
                    ? addr.getAddress().getHostAddress()
                    : "0.0.0.0";
        }
    }

    private final String _hostname;
    private final String _hostip;
    private volatile String _bindip;
    private volatile int _port = 0;
}
