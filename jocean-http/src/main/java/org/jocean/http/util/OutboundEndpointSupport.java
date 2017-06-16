package org.jocean.http.util;

import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.http.OutboundEndpoint;
import org.jocean.http.TrafficCounter;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSelector;
import org.jocean.idiom.rx.Action1_N;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Actions;

public class OutboundEndpointSupport implements OutboundEndpoint {

    private static final Logger LOG =
            LoggerFactory.getLogger(OutboundEndpointSupport.class);
    
    public OutboundEndpointSupport(
            final InterfaceSelector selector,
            final Channel channel,
            final Transformer<Object, Object> transformer,
            final TrafficCounter trafficCounter,
            final Action1<Action0> onTerminate) {
        this._channel = channel;
        this._transformer = transformer;
        this._trafficCounter = trafficCounter;
        
        Nettys.applyToChannel(
            onTerminate, 
            channel, 
            APPLY.ON_CHANNEL_WRITABILITYCHANGED,
            new Action0() {
                @Override
                public void call() {
                    _onWritabilityChangeds.foreachComponent(_CALL_WRITABILITYCHGED, OutboundEndpointSupport.this);
                }});
        this._op = selector.build(Op.class, OP_WHEN_ACTIVE, OP_WHEN_UNACTIVE);
    }
    
    @Override
    public void setFlushPerWrite(final boolean isFlushPerWrite) {
        this._isFlushPerWrite = isFlushPerWrite;
    }
    
    @Override
    public void setWriteBufferWaterMark(final int low, final int high) {
        this._op.setWriteBufferWaterMark(this, low, high);
    }
    
    private boolean queryWritable() {
        return _channel.isWritable();
    }

    @Override
    public boolean isWritable() {
        return _op.isWritable(this);
    }

    @Override
    public Action0 doOnWritabilityChanged(final Action1<OutboundEndpoint> onWritabilityChanged) {
        return _op.addWritabilityChanged(this, onWritabilityChanged);
    }
    
    @Override
    public Action0 doOnSended(final Action1<Object> onSended) {
        return _op.addOnSended(this, onSended);
    }
    
    @Override
    public long outboundBytes() {
        return _trafficCounter.outboundBytes();
    }

    @Override
    public Subscription message(final Observable<? extends Object> message) {
        return _op.setMessage(this, message);
    }
    
    private static final Action1_N<Action1<OutboundEndpoint>> _CALL_WRITABILITYCHGED = 
        new Action1_N<Action1<OutboundEndpoint>>() {
        @Override
        public void call(final Action1<OutboundEndpoint> onWritabilityChanged, final Object...args) {
            try {
                onWritabilityChanged.call((OutboundEndpoint)args[0]);
            } catch (Exception e) {
                LOG.warn("exception when outbound({}) invoke onWritabilityChanged({}), detail: {}",
                    args[0], 
                    onWritabilityChanged, 
                    ExceptionUtils.exception2detail(e));
            }
        }};
        
    private static final Action1_N<Action1<Object>> _CALL_ONSENDED = 
        new Action1_N<Action1<Object>>() {
        @Override
        public void call(final Action1<Object> onSended, final Object... args) {
            try {
                onSended.call(args[0]);
            } catch (Exception e) {
                LOG.warn("exception when invoke onSended({}) with msg({}), detail: {}",
                    onSended, 
                    args[0], 
                    ExceptionUtils.exception2detail(e));
            }
        }};
            
    private final Op _op;
    
    private static final Op OP_WHEN_ACTIVE = new Op() {
        @Override
        public void setWriteBufferWaterMark(final OutboundEndpointSupport support,
                final int low, final int high) {
            support._channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(low, high));
        }
        
        @Override
        public boolean isWritable(final OutboundEndpointSupport support) {
            return support.queryWritable();
        }
        
        @Override
        public Action0 addOnSended(final OutboundEndpointSupport support,
                final Action1<Object> onSended) {
            support._onSendeds.addComponent(onSended);
            return new Action0() {
                @Override
                public void call() {
                    support._onSendeds.removeComponent(onSended);
                }};
        }

        @Override
        public Action0 addWritabilityChanged(final OutboundEndpointSupport support,
                final Action1<OutboundEndpoint> onWritabilityChanged) {
            support._onWritabilityChangeds.addComponent(onWritabilityChanged);
            return new Action0() {
                @Override
                public void call() {
                    support._onWritabilityChangeds.removeComponent(onWritabilityChanged);
                }};
        }

        @Override
        public Subscription setMessage(final OutboundEndpointSupport support,
                Observable<? extends Object> message) {
            if (support._isOutboundSetted.compareAndSet(false, true)) {
                if ( null != support._transformer) {
                    message = message.compose(support._transformer);
                }
                return message.subscribe(
                    new Action1<Object>() {
                        @Override
                        public void call(final Object msg) {
                            support._op.messageOnNext(support, msg);
                        }},
                    RxSubscribers.ignoreError(),
                    new Action0() {
                        @Override
                        public void call() {
                            support._op.messageOnCompleted(support);
                        }});
            } else {
                LOG.warn("initiator({}) 's outbound message has setted, ignore this message({})",
                        this, message);
                return null;
            }
        }

        @Override
        public void messageOnNext(final OutboundEndpointSupport support, Object msg) {
            support.outboundOnNext0(msg);
        }

//        @Override
//        public void messageOnError(final OutboundEndpointSupport support, Throwable e) {
//            support.outboundOnError0(e);
//        }

        @Override
        public void messageOnCompleted(final OutboundEndpointSupport support) {
            support.outboundOnCompleted0();
        }};
    
    private static final Op OP_WHEN_UNACTIVE = new Op() {
        @Override
        public void setWriteBufferWaterMark(final OutboundEndpointSupport support, 
                int low, int high) {
        }
        
        @Override
        public boolean isWritable(final OutboundEndpointSupport support) {
            return false;
        }
        
        @Override
        public Action0 addOnSended(OutboundEndpointSupport support,
                Action1<Object> onSended) {
            return Actions.empty();
        }

        @Override
        public Action0 addWritabilityChanged(OutboundEndpointSupport support,
                Action1<OutboundEndpoint> onWritabilityChanged) {
            return Actions.empty();
        }

        @Override
        public Subscription setMessage(OutboundEndpointSupport support,
                Observable<? extends Object> message) {
            return null;
        }

        @Override
        public void messageOnNext(OutboundEndpointSupport support, Object msg) {
        }

//        @Override
//        public void messageOnError(OutboundEndpointSupport support, Throwable e) {
//        }

        @Override
        public void messageOnCompleted(OutboundEndpointSupport support) {
        }
    };
    
    protected interface Op {
        public void setWriteBufferWaterMark(final OutboundEndpointSupport support,
                final int low, final int high);
        public boolean isWritable(final OutboundEndpointSupport support);
        public Action0 addOnSended(final OutboundEndpointSupport support, 
                final Action1<Object> onSended);
        public Action0 addWritabilityChanged(final OutboundEndpointSupport support,
                final Action1<OutboundEndpoint> onWritabilityChanged);
        public Subscription setMessage(final OutboundEndpointSupport support,
                final Observable<? extends Object> message); 
        public void messageOnNext(final OutboundEndpointSupport support, 
                final Object msg);
//        public void messageOnError(final OutboundEndpointSupport support, 
//                final Throwable e);
        public void messageOnCompleted(final OutboundEndpointSupport support);
    }
    
    private void outboundOnNext0(final Object msg) {
        final ChannelFuture future = this._isFlushPerWrite
            ? this._channel.writeAndFlush(ReferenceCountUtil.retain(msg))
            : this._channel.write(ReferenceCountUtil.retain(msg))
            ;
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future)
                    throws Exception {
                _onSendeds.foreachComponent(_CALL_ONSENDED, msg);
            }});
    }

    private void outboundOnCompleted0() {
        this._channel.flush();
    }
    
    private volatile boolean _isFlushPerWrite = false;
    
    private final COWCompositeSupport<Action1<Object>> _onSendeds = 
            new COWCompositeSupport<>();
        
    private final COWCompositeSupport<Action1<OutboundEndpoint>> _onWritabilityChangeds = 
            new COWCompositeSupport<>();
        
    private final Channel _channel;
    private final AtomicBoolean _isOutboundSetted = new AtomicBoolean(false);
    private final TrafficCounter _trafficCounter;
    private final Transformer<Object, Object> _transformer;
}
