/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.lucene;

import org.elasticsearch.core.RefCounted;

import java.util.List;

public interface ShardRefCounted {
    RefCounted get(int shardId);

    record ShardRefCountedList(List<? extends RefCounted> refCounters) implements ShardRefCounted {
        @Override
        public RefCounted get(int shardId) {
            return refCounters.get(shardId);
        }
    }

    record SingleShardRefCounted(int index, RefCounted refCounted) implements ShardRefCounted {
        @Override
        public RefCounted get(int shardId) {
            if (shardId != index) {
                throw new IllegalArgumentException("Invalid shardId: " + shardId + ", expected: " + index);
            }
            return refCounted;
        }
    }

    static ShardRefCounted fromShardContext(ShardContext shardContext) {
        return new SingleShardRefCounted(shardContext.index(), shardContext);
    }

    ShardRefCounted ALWAYS_REFERENCED = shardId -> RefCounted.ALWAYS_REFERENCED;
}
