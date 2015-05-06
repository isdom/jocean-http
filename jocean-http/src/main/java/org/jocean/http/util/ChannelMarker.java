/**
 * 
 */
package org.jocean.http.util;

import io.netty.channel.Channel;

/**
 * @author isdom
 *
 */
public interface ChannelMarker {
    public void markChannelConnected(final Channel channel);
    public boolean isChannelConnected(final Channel channel);
}
