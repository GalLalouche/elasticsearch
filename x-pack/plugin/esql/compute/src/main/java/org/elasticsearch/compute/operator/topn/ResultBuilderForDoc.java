/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.topn;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.DocVector;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.RefCounted;
import org.elasticsearch.core.Releasables;

import java.util.List;

class ResultBuilderForDoc implements ResultBuilder {
    private final BlockFactory blockFactory;
    private final int[] shards;
    private final int[] segments;
    private final int[] docs;
    private int position;
    private @Nullable RefCounted nextRefCounted;
    private final RefCounted[] refCounted;

    ResultBuilderForDoc(BlockFactory blockFactory, int positions) {
        // TODO use fixed length builders
        this.blockFactory = blockFactory;
        this.shards = new int[positions];
        this.segments = new int[positions];
        this.docs = new int[positions];
        this.refCounted = new RefCounted[positions];
    }

    @Override
    public void decodeKey(BytesRef keys) {
        throw new AssertionError("_doc can't be a key");
    }

    void setNextRefCounted(RefCounted nextRefCounted) {
        this.nextRefCounted = nextRefCounted;
        // Since rows can be closed before build is called, we need to increment the ref count to ensure the shard context isn't closed.
        this.nextRefCounted.mustIncRef();
    }

    @Override
    public void decodeValue(BytesRef values) {
        assert nextRefCounted != null : "setNextRefCounted must be set before decodeValue";
        shards[position] = TopNEncoder.DEFAULT_UNSORTABLE.decodeInt(values);
        segments[position] = TopNEncoder.DEFAULT_UNSORTABLE.decodeInt(values);
        docs[position] = TopNEncoder.DEFAULT_UNSORTABLE.decodeInt(values);
        refCounted[position] = nextRefCounted;
        position++;
        nextRefCounted = null;
    }

    @Override
    public Block build() {
        boolean success = false;
        IntVector shardsVector = null;
        IntVector segmentsVector = null;
        try {
            shardsVector = blockFactory.newIntArrayVector(shards, position);
            segmentsVector = blockFactory.newIntArrayVector(segments, position);
            var docsVector = blockFactory.newIntArrayVector(docs, position);
            var docsBlock = new DocVector(getShardRefCounters(), shardsVector, segmentsVector, docsVector, null).asBlock();
            success = true;
            return docsBlock;
        } finally {
            // The DocVector constructor already incremented the relevant RefCounted, so we can now decrement them since we incremented them
            // in setNextRefCounted.
            for (int i = 0; i < position; i++) {
                refCounted[i].decRef();
            }
            if (success == false) {
                Releasables.closeExpectNoException(shardsVector, segmentsVector);
            }
        }
    }

    private DocVector.ShardRefCounters getShardRefCounters() {
        var hasSingleUniqueCounter = true;
        for (int i = 1; i < position; i++) {
            if (refCounted[i] != refCounted[0]) {
                hasSingleUniqueCounter = false;
                break;
            }
        }

        return hasSingleUniqueCounter
            ? new DocVector.SingleShardCounter(refCounted[0])
            : new DocVector.ShardRefCountedList(List.of(refCounted));
    }

    @Override
    public String toString() {
        return "ValueExtractorForDoc";
    }

    @Override
    public void close() {
        // TODO memory accounting
    }
}
