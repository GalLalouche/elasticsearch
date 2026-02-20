/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import com.pholser.junit.quickcheck.random.SourceOfRandomness;

class GenUtils {
    private GenUtils() {/* static class */}

    @SafeVarargs
    public static <T> T choose(SourceOfRandomness random, T first, T second, T... rest) {
        int idx = random.nextInt(0, 1 + rest.length);
        return idx == 0 ? first : idx == 1 ? second : rest[idx - 2];
    }
}
