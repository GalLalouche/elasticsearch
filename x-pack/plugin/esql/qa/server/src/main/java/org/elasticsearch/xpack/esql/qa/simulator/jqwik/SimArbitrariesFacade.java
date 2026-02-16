/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator.jqwik;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.RandomGenerator;
import net.jqwik.api.ShrinkingDistance;
import net.jqwik.api.Tuple;
import net.jqwik.api.arbitraries.BigDecimalArbitrary;
import net.jqwik.api.arbitraries.BigIntegerArbitrary;
import net.jqwik.api.arbitraries.ByteArbitrary;
import net.jqwik.api.arbitraries.CharacterArbitrary;
import net.jqwik.api.arbitraries.DoubleArbitrary;
import net.jqwik.api.arbitraries.FloatArbitrary;
import net.jqwik.api.arbitraries.IntegerArbitrary;
import net.jqwik.api.arbitraries.LongArbitrary;
import net.jqwik.api.arbitraries.MapArbitrary;
import net.jqwik.api.arbitraries.ShortArbitrary;
import net.jqwik.api.arbitraries.StringArbitrary;
import net.jqwik.api.arbitraries.TraverseArbitrary;
import net.jqwik.api.arbitraries.TypeArbitrary;
import net.jqwik.api.providers.TypeUsage;
import net.jqwik.api.stateful.ActionSequenceArbitrary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Minimal implementation of jqwik's ArbitrariesFacade, providing just enough
 * functionality for the simulator's LogicalPlanGenerator without pulling in
 * the full jqwik-engine.
 */
public class SimArbitrariesFacade extends Arbitraries.ArbitrariesFacade {

    @Override
    public <T> Arbitrary<T> just(T value) {
        return new SimpleArbitrary<>(genSize -> random -> unshrinkable(value));
    }

    @Override
    public <T> Arbitrary<T> oneOf(Collection<? extends Arbitrary<? extends T>> choices) {
        List<? extends Arbitrary<? extends T>> list = choices instanceof List
            ? (List<? extends Arbitrary<? extends T>>) choices
            : new ArrayList<>(choices);
        return new SimpleArbitrary<>(genSize -> {
            @SuppressWarnings("unchecked")
            List<RandomGenerator<T>> generators = list.stream()
                .map(a -> (RandomGenerator<T>) (RandomGenerator<?>) a.generator(genSize))
                .toList();
            return random -> {
                int idx = random.nextInt(generators.size());
                return generators.get(idx).next(random);
            };
        });
    }

    @Override
    public <T> Arbitrary<T> of(Collection<? extends T> values) {
        List<? extends T> list = values instanceof List ? (List<? extends T>) values : new ArrayList<>(values);
        return new SimpleArbitrary<>(genSize -> random -> {
            int idx = random.nextInt(list.size());
            @SuppressWarnings("unchecked")
            T val = (T) list.get(idx);
            return unshrinkable(val);
        });
    }

    @Override
    public Arbitrary<Character> of(char[] chars) {
        return new SimpleArbitrary<>(genSize -> random -> unshrinkable(chars[random.nextInt(chars.length)]));
    }

    @Override
    public <T> Arbitrary<T> recursive(
        Supplier<? extends Arbitrary<T>> base,
        Function<? super Arbitrary<T>, ? extends Arbitrary<T>> recursion,
        int minDepth,
        int maxDepth
    ) {
        // For our use case depth is always 1, so just apply recursion once to base
        return new SimpleArbitrary<>(genSize -> {
            int depth = minDepth == maxDepth ? minDepth : minDepth + new java.util.Random().nextInt(maxDepth - minDepth + 1);
            Arbitrary<T> current = base.get();
            for (int i = 0; i < depth; i++) {
                current = recursion.apply(current);
            }
            RandomGenerator<T> gen = current.generator(genSize);
            return gen::next;
        });
    }

    static <T> net.jqwik.api.Shrinkable<T> unshrinkable(T value) {
        return new net.jqwik.api.Shrinkable<>() {
            @Override
            public T value() {
                return value;
            }

            @Override
            public Stream<net.jqwik.api.Shrinkable<T>> shrink() {
                return Stream.empty();
            }

            @Override
            public ShrinkingDistance distance() {
                return ShrinkingDistance.of(0);
            }
        };
    }

    // --- Unsupported operations below (not needed for simulator) ---

    @Override
    public <T> Arbitrary<T> frequencyOf(List<? extends Tuple.Tuple2<Integer, ? extends Arbitrary<T>>> list) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <M> ActionSequenceArbitrary<M> sequences(net.jqwik.api.Arbitrary<? extends net.jqwik.api.stateful.Action<M>> arbitrary) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public IntegerArbitrary integers() {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public LongArbitrary longs() {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public BigIntegerArbitrary bigIntegers() {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public FloatArbitrary floats() {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public BigDecimalArbitrary bigDecimals() {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public DoubleArbitrary doubles() {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public ByteArbitrary bytes() {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public ShortArbitrary shorts() {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public StringArbitrary strings() {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public CharacterArbitrary chars() {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<T> defaultFor(Class<T> aClass, Class<?>[] classes) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<T> defaultFor(TypeUsage typeUsage, Function<? super TypeUsage, ? extends Arbitrary<T>> function) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<T> lazy(Supplier<? extends Arbitrary<T>> supplier) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> TypeArbitrary<T> forType(Class<T> aClass) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <K, V> MapArbitrary<K, V> maps(Arbitrary<K> arbitrary, Arbitrary<V> arbitrary1) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <K, V> Arbitrary<Map.Entry<K, V>> entries(Arbitrary<K> arbitrary, Arbitrary<V> arbitrary1) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<T> lazyOf(List<? extends Supplier<? extends Arbitrary<T>>> list) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> TraverseArbitrary<T> traverse(Class<T> aClass, TraverseArbitrary.Traverser traverser) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<T> create(Supplier<T> supplier) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<List<T>> shuffle(List<T> list) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<T> fromGenerator(IntFunction<? extends RandomGenerator<T>> intFunction) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Arbitrary<T> frequency(List<? extends Tuple.Tuple2<Integer, T>> list) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }
}
