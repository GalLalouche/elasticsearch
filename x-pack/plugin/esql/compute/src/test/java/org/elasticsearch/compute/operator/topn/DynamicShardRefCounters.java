/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.topn;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.compute.data.DocVector;
import org.elasticsearch.core.AbstractRefCounted;
import org.elasticsearch.core.RefCounted;

import java.util.HashMap;
import java.util.Map;

class DynamicShardRefCounters implements DocVector.ShardRefCounters {
    private Map<Integer, LazyAbstractRefCounted> counters = new HashMap<>();

    @Override
    public LazyAbstractRefCounted get(int shardId) {
        return counters.computeIfAbsent(shardId, unused -> new LazyAbstractRefCounted() {
            @Override
            protected void closeInternal() {}
        });
    }

    public Iterable<LazyAbstractRefCounted> values() {
        return counters.values();
    }

    // FIXME(gal, NOCOMMIT) Copy pasting this for now until I figure out where to place it
    public abstract class LazyAbstractRefCounted implements RefCounted {
        private final SetOnce<AbstractRefCounted> refCounter = new SetOnce<>();
        private boolean isClosed = false;

        @Override
        public void incRef() {
            if (maybeCreate() == false) {
                refCounter.get().incRef();
            }
        }

        @Override
        public boolean tryIncRef() {
            return maybeCreate() || refCounter.get().tryIncRef();
        }

        private boolean maybeCreate() {
            return refCounter.trySet(new AbstractRefCounted() {
                @Override
                protected void closeInternal() {
                    LazyAbstractRefCounted.this.isClosed = true;
                    LazyAbstractRefCounted.this.closeInternal();
                }
            });
        }

        @Override
        public boolean decRef() {
            assert refCounter.get() != null;
            return refCounter.get().decRef();
        }

        @Override
        public boolean hasReferences() {
            AbstractRefCounted rc = refCounter.get();
            return rc != null && rc.hasReferences();
        }

        protected abstract void closeInternal();

        public boolean isClosed() {
            return isClosed;
        }
    }
}
