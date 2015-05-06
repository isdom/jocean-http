/**
 * 
 */
package org.jocean.http.client.impl;

import org.jocean.http.util.ChannelMarker;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * @author isdom
 *
 */
public class DefaultChannelMarker implements ChannelMarker {

    private static final Object OK = new Object();
    private static final AttributeKey<Object> CONNECTED = 
            AttributeKey.valueOf("__CONNECTED");
    
    /* (non-Javadoc)
     * @see org.jocean.http.client.impl.ChannelMarker#markChannelConnected(io.netty.channel.Channel)
     */
    @Override
    public void markChannelConnected(final Channel channel) {
        channel.attr(CONNECTED).set(OK);
    }

    /* (non-Javadoc)
     * @see org.jocean.http.client.impl.ChannelMarker#isChannelConnected(io.netty.channel.Channel)
     */
    @Override
    public boolean isChannelConnected(Channel channel) {
        return null != channel.attr(CONNECTED).get();
    }

}
