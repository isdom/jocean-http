package org.jocean.redis.impl;

import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.redis.RedisArrayAggregator;
import io.netty.handler.codec.redis.RedisBulkStringAggregator;
import io.netty.handler.codec.redis.RedisDecoder;
import io.netty.handler.codec.redis.RedisEncoder;
import rx.functions.FuncN;

class RedisFuncs {
    private RedisFuncs() {
        throw new IllegalStateException("No instances!");
    }

    static final FuncN<ChannelHandler> REDIS_DECODER_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new RedisDecoder();
        }};

    static final FuncN<ChannelHandler> REDIS_BULKSTRING_AGGREGATOR_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new RedisBulkStringAggregator();
        }};

    static final FuncN<ChannelHandler> REDIS_ARRAY_AGGREGATOR_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new RedisArrayAggregator();
        }};

    static final FuncN<ChannelHandler> REDIS_ENCODER_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new RedisEncoder();
        }};
}
