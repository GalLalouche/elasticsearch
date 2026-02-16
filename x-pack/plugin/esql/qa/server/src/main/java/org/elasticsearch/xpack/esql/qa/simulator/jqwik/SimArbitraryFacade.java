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
import net.jqwik.api.arbitraries.ArrayArbitrary;
import net.jqwik.api.arbitraries.IteratorArbitrary;
import net.jqwik.api.arbitraries.ListArbitrary;
import net.jqwik.api.arbitraries.SetArbitrary;
import net.jqwik.api.arbitraries.StreamArbitrary;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.elasticsearch.xpack.esql.qa.simulator.jqwik.SimArbitrariesFacade.unshrinkable;

/**
 * Minimal implementation of jqwik's ArbitraryFacade for the simulator.
 */
public class SimArbitraryFacade extends Arbitrary.ArbitraryFacade {

    @Override
    public <T, U> Arbitrary<U> map(Arbitrary<T> arbitrary, Function<? super T, ? extends U> mapper) {
        return new SimpleArbitrary<>(genSize -> {
            RandomGenerator<T> gen = arbitrary.generator(genSize);
            return random -> unshrinkable(mapper.apply(gen.next(random).value()));
        });
    }

    @Override
    public <T> SetArbitrary<T> set(Arbitrary<T> arbitrary) {
        return new SimpleSetArbitrary<>(arbitrary);
    }

    @Override
    public <T> RandomGenerator<T> memoizedGenerator(Arbitrary<T> arbitrary, int genSize, boolean withEdgeCases) {
        return arbitrary.generator(genSize);
    }

    // --- Unsupported operations below ---

    @Override
    public <T> ListArbitrary<T> list(Arbitrary<T> arbitrary) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> StreamArbitrary<T> stream(Arbitrary<T> arbitrary) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> IteratorArbitrary<T> iterator(Arbitrary<T> arbitrary) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T, A> ArrayArbitrary<T, A> array(Arbitrary<T> arbitrary, Class<A> aClass) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Stream<T> sampleStream(Arbitrary<T> arbitrary) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<T> injectNull(Arbitrary<T> arbitrary, double v) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<T> filter(Arbitrary<T> arbitrary, Predicate<? super T> predicate, int i) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T, U> Arbitrary<U> flatMap(Arbitrary<T> arbitrary, Function<? super T, ? extends Arbitrary<U>> function) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<T> ignoreExceptions(Arbitrary<T> arbitrary, int i, Class<? extends Throwable>[] classes) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<T> dontShrink(Arbitrary<T> arbitrary) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<T> configureEdgeCases(Arbitrary<T> arbitrary, Consumer<? super EdgeCases.Config<T>> consumer) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<T> withoutEdgeCases(Arbitrary<T> arbitrary) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<T> fixGenSize(Arbitrary<T> arbitrary, int i) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<java.util.List<T>> collect(Arbitrary<T> arbitrary, Predicate<? super java.util.List<? extends T>> predicate) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    /**
     * Minimal SetArbitrary that generates random subsets.
     */
    private static class SimpleSetArbitrary<T> implements SetArbitrary<T> {
        private final Arbitrary<T> elementArbitrary;
        private int minSize = 0;
        private int maxSize = Integer.MAX_VALUE;

        SimpleSetArbitrary(Arbitrary<T> elementArbitrary) {
            this.elementArbitrary = elementArbitrary;
        }

        private SimpleSetArbitrary(Arbitrary<T> elementArbitrary, int minSize, int maxSize) {
            this.elementArbitrary = elementArbitrary;
            this.minSize = minSize;
            this.maxSize = maxSize;
        }

        @Override
        public SetArbitrary<T> ofMinSize(int minSize) {
            return new SimpleSetArbitrary<>(elementArbitrary, minSize, maxSize);
        }

        @Override
        public SetArbitrary<T> ofMaxSize(int maxSize) {
            return new SimpleSetArbitrary<>(elementArbitrary, minSize, maxSize);
        }

        @Override
        public RandomGenerator<Set<T>> generator(int genSize) {
            RandomGenerator<T> elemGen = elementArbitrary.generator(genSize);
            return random -> {
                int size = minSize == maxSize ? minSize : minSize + random.nextInt(maxSize - minSize + 1);
                Set<T> set = new HashSet<>();
                int attempts = 0;
                while (set.size() < size && attempts < size * 10) {
                    set.add(elemGen.next(random).value());
                    attempts++;
                }
                return unshrinkable(set);
            };
        }

        @Override
        public EdgeCases<Set<T>> edgeCases(int maxEdgeCases) {
            return EdgeCases.none();
        }

        @Override
        public SetArbitrary<T> withSizeDistribution(net.jqwik.api.RandomDistribution distribution) {
            return this;
        }

        @Override
        public <U> Arbitrary<Set<U>> mapEach(java.util.function.BiFunction<? super Set<? extends T>, ? super T, ? extends U> biFunction) {
            throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
        }

        @Override
        public <U> Arbitrary<Set<U>> flatMapEach(
            java.util.function.BiFunction<? super Set<? extends T>, ? super T, ? extends Arbitrary<U>> biFunction
        ) {
            throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
        }

        @Override
        public SetArbitrary<T> uniqueElements() {
            return this; // sets are already unique
        }

        @Override
        public SetArbitrary<T> uniqueElements(Function<? super T, ?> function) {
            return this;
        }

        @Override
        public <R> Arbitrary<R> reduce(R initial, java.util.function.BiFunction<R, ? super T, R> accumulator) {
            throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
        }
    }
}
