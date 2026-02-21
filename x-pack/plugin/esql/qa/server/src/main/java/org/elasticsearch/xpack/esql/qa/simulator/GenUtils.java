/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import com.pholser.junit.quickcheck.random.SourceOfRandomness;

class GenUtils {
    private GenUtils() { /* static class */ }

    /**
     * Picks uniformly at random from at least two options. The two mandatory parameters guarantee
     * callers always provide a meaningful choice (i.e. no single-element "random" selection).
     */
    @SafeVarargs
    public static <T> T choose(SourceOfRandomness random, T first, T second, T... rest) {
        // nextInt bounds are inclusive on both ends, so total choices = 2 + rest.length
        int idx = random.nextInt(0, 1 + rest.length);
        return idx == 0 ? first : idx == 1 ? second : rest[idx - 2];
    }
}
