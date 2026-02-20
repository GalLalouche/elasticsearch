/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import org.elasticsearch.common.util.ArrayUtils;

class GenUtils {
    private GenUtils() {/* static class */}

    @SuppressWarnings("unchecked")
    public static <T> T choose(SourceOfRandomness random, T first, T second, T... rest) {
        return random.choose(ArrayUtils.concat((T[]) new Object[] { first, second }, rest));
    }
}
