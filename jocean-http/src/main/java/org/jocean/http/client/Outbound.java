package org.jocean.http.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;

import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys.ToOrdinal;
import org.jocean.http.util.Oneoff;
import org.jocean.http.util.ResponseSubscriberAware;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.rx.RxFunctions;

import rx.Subscriber;
import rx.Subscription;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.FuncN;
import rx.functions.Functions;

public class Outbound {
    private Outbound() {
        throw new IllegalStateException("No instances!");
    }

    public static final Feature[] EMPTY_FEATURES = new Feature[0];

    public interface Feature extends Func1<ChannelPipeline, ChannelHandler> {
    };
    
    public interface OneoffFeature extends Feature, Oneoff {
    };
    
    public interface FeaturesAware {
        public void setApplyFeatures(final Feature[] features);
    }
    
    public interface ApplyToRequest {
        public void applyToRequest(final HttpRequest request);
    }
    
    public static void applyNononeoffFeatures(
            final Channel channel,
            final Feature[] features) {
        final Feature applicable = 
                InterfaceUtils.compositeExcludeType(features, 
                        Feature.class, OneoffFeature.class);
        if (null!=applicable) {
            applicable.call(channel.pipeline());
        }
    }

    public static Subscription applyOneoffFeatures(
            final Channel channel,
            final Feature[] features) {
        final Func0<String[]> diff = Nettys.namesDifferenceBuilder(channel);
        final Feature applicable = 
                InterfaceUtils.compositeIncludeType(features, OneoffFeature.class);
        if (null!=applicable) {
            applicable.call(channel.pipeline());
        }
        return RxNettys.removeHandlersSubscription(channel, diff.call());
    }
    
    public static boolean isReadyForInteraction(final ChannelPipeline pipeline) {
        return (pipeline.names().indexOf(APPLY.READY4INTERACTION_NOTIFIER.name()) == -1);
    }
    
    private abstract static class CLS_DECOMPRESSOR implements OneoffFeature, ApplyToRequest {
    }
    
    public static final Feature ENABLE_LOGGING = new OneoffFeature() {
        @Override
        public ChannelHandler call(final ChannelPipeline pipeline) {
            return APPLY.LOGGING.applyTo(pipeline);
        }
    };
            
    public static final Feature ENABLE_DECOMPRESSOR = new CLS_DECOMPRESSOR() {
        @Override
        public ChannelHandler call(final ChannelPipeline pipeline) {
            return APPLY.CONTENT_DECOMPRESSOR.applyTo(pipeline);
        }
        
        @Override
        public void applyToRequest(final HttpRequest request) {
            HttpHeaders.addHeader(request,
                    HttpHeaders.Names.ACCEPT_ENCODING, 
                    HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE);
        }
    };
    
    public static final Feature ENABLE_MULTIPART = new Feature() {
        @Override
        public ChannelHandler call(final ChannelPipeline pipeline) {
            return  APPLY.CHUNKED_WRITER.applyTo(pipeline);
        }
    };
    
    public static final class ENABLE_SSL implements Feature {
        public ENABLE_SSL(final SslContext sslCtx) {
            this._sslCtx = sslCtx;
        }
        
        @Override
        public ChannelHandler call(final ChannelPipeline pipeline) {
            return APPLY.SSL.applyTo(pipeline, pipeline.channel(), this._sslCtx);
        }
        
        private final SslContext _sslCtx;
    }
    
    public static final class ENABLE_CLOSE_ON_IDLE implements OneoffFeature {
        public ENABLE_CLOSE_ON_IDLE(final int allIdleTimeout) {
            this._allIdleTimeout = allIdleTimeout;
        }
        
        @Override
        public ChannelHandler call(final ChannelPipeline pipeline) {
            return APPLY.CLOSE_ON_IDLE.applyTo(pipeline, this._allIdleTimeout);
        }
        
        private final int _allIdleTimeout;
    }
    
    public static final class ENABLE_PROGRESSIVE implements OneoffFeature, ResponseSubscriberAware {
        public ENABLE_PROGRESSIVE(final long minIntervalInMs) {
            this._minIntervalInMs = minIntervalInMs;
        }
        
        @Override
        public void setResponseSubscriber(Subscriber<Object> subscriber) {
            this._responseSubscriber = subscriber;
        }
        
        @Override
        public ChannelHandler call(final ChannelPipeline pipeline) {
            return APPLY.PROGRESSIVE.applyTo(pipeline, this._responseSubscriber, this._minIntervalInMs);
        }
        
        private final long _minIntervalInMs;
        private Subscriber<Object> _responseSubscriber;
    }
    
    public enum APPLY {
        LOGGING(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
        PROGRESSIVE(Functions.fromFunc(Nettys.PROGRESSIVE_FUNC2)),
        CLOSE_ON_IDLE(Functions.fromFunc(Nettys.CLOSE_ON_IDLE_FUNC1)),
        SSL(Functions.fromFunc(Nettys.SSL_FUNC2)),
        HTTPCLIENT(Nettys.HTTPCLIENT_CODEC_FUNCN),
        CONTENT_DECOMPRESSOR(Nettys.CONTENT_DECOMPRESSOR_FUNCN),
        CHUNKED_WRITER(Nettys.CHUNKED_WRITER_FUNCN),
        READY4INTERACTION_NOTIFIER(Functions.fromFunc(Nettys.READY4INTERACTION_NOTIFIER_FUNC2)),
        WORKER(Functions.fromFunc(Nettys.HTTPCLIENT_WORK_FUNC1)),
        LAST(null)
        ;
        public ChannelHandler applyTo(final ChannelPipeline pipeline, final Object ... args) {
            if (null==this._factory) {
                throw new UnsupportedOperationException("ChannelHandler's factory is null");
            }
            return Nettys.insertHandler(
                pipeline,
                this.name(), 
                this._factory.call(args), 
                TO_ORDINAL);
        }
    
        public static final ToOrdinal TO_ORDINAL = Nettys.ordinal(APPLY.class);
        
        private APPLY(final FuncN<ChannelHandler> factory) {
            this._factory = factory;
        }
    
        private final FuncN<ChannelHandler> _factory;
    }
}
