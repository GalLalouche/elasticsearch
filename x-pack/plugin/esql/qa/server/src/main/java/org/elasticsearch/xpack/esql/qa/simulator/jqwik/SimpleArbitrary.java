/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator.jqwik;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.EdgeCases;
import net.jqwik.api.RandomGenerator;

import java.util.function.IntFunction;

/**
 * Minimal Arbitrary implementation backed by a function that creates
 * a RandomGenerator for a given genSize.
 */
class SimpleArbitrary<T> implements Arbitrary<T> {
    private final IntFunction<RandomGenerator<T>> generatorFactory;

    SimpleArbitrary(IntFunction<RandomGenerator<T>> generatorFactory) {
        this.generatorFactory = generatorFactory;
    }

    @Override
    public RandomGenerator<T> generator(int genSize) {
        return generatorFactory.apply(genSize);
    }

    @Override
    public EdgeCases<T> edgeCases(int maxEdgeCases) {
        return EdgeCases.none();
    }
}
