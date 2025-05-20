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
import org.elasticsearch.core.RefCounted;
import org.elasticsearch.core.Releasables;

import java.util.List;

class ResultBuilderForDoc implements ResultBuilder {
    private final BlockFactory blockFactory;
    private final int[] shards;
    private final int[] segments;
    private final int[] docs;
    private int position;
    private RefCounted[] shardCounters;

    ResultBuilderForDoc(BlockFactory blockFactory, int positions) {
        // TODO use fixed length builders
        this.blockFactory = blockFactory;
        this.shards = new int[positions];
        this.segments = new int[positions];
        this.docs = new int[positions];
        this.shardCounters = new RefCounted[positions];
    }

    @Override
    public void decodeKey(BytesRef keys) {
        throw new AssertionError("_doc can't be a key");
    }

    @Override
    public void decodeValue(BytesRef values) {
        throw new AssertionError("TODO(gal) NOCOMMIT");
    }

    // FIXME(gal, NOCOMMIT) More yuckness
    public void decodeValue(BytesRef values, DocVector docVector) {
        shards[position] = TopNEncoder.DEFAULT_UNSORTABLE.decodeInt(values);
        segments[position] = TopNEncoder.DEFAULT_UNSORTABLE.decodeInt(values);
        docs[position] = TopNEncoder.DEFAULT_UNSORTABLE.decodeInt(values);
        shardCounters[position] = docVector.shardRefCounters.get(shards[position]);
        position++;
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
            var hasSingleUniqueCounter = true;
            for (int i = 0; i < position; i++) {
                if (shardCounters[i] != shardCounters[0]) {
                    hasSingleUniqueCounter = false;
                    break;
                }
            }
            var docsBlock = new DocVector(
                hasSingleUniqueCounter
                    ? new DocVector.SingleShardCounter(shardCounters[0])
                    : new DocVector.ShardRefCountedList(List.of(shardCounters)),
                shardsVector,
                segmentsVector,
                docsVector,
                null
            ).asBlock();
            success = true;
            return docsBlock;
        } finally {
            if (success == false) {
                Releasables.closeExpectNoException(shardsVector, segmentsVector);
            }
        }
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
