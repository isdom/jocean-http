package org.jocean.http.client;

import org.jocean.http.Feature;

import io.netty.handler.codec.http.HttpRequest;
import rx.functions.Action1;

public class Outbound {
    private Outbound() {
        throw new IllegalStateException("No instances!");
    }

    public interface ApplyToRequest extends Action1<HttpRequest> {
    }
    
    public static final Feature ENABLE_MULTIPART = new Feature.AbstractFeature0() {
        @Override
        public String toString() {
            return "ENABLE_MULTIPART";
        }
    };
    
    public interface InteractionMeterFeature extends Feature, InteractionMeter {
    }
}