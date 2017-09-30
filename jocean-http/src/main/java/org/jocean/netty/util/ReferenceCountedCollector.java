package org.jocean.netty.util;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.netty.util.ReferenceCounted;
import rx.functions.Action1;

public interface ReferenceCountedCollector {
    public void add(final ReferenceCounted referenceCounted);
    public void forAll(final Action1<ReferenceCounted> check);
    
    public static class Util {
        static private final ReferenceCountedCollector _EMPTY = new ReferenceCountedCollector() {
            @Override
            public void add(ReferenceCounted referenceCounted) {
            }
            @Override
            public void forAll(final Action1<ReferenceCounted> check) {
            }};
            
        public static ReferenceCountedCollector nullCollector() {
            return _EMPTY;
        }

        public static ReferenceCountedCollector defaultCollector() {
            final Queue<ReferenceCounted> refs = new ConcurrentLinkedQueue<>();
            return new ReferenceCountedCollector() {
                @Override
                public void add(final ReferenceCounted referenceCounted) {
                    refs.add(referenceCounted);
                }
                
                @Override
                public void forAll(final Action1<ReferenceCounted> check) {
                    for (ReferenceCounted referenceCounted : refs) {
                        check.call(referenceCounted);
                    }
                }};
        }
    }
}
