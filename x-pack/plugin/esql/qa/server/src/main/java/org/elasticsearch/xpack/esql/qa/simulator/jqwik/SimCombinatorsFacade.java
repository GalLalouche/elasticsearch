/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator.jqwik;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.RandomGenerator;

import java.util.List;

import static org.elasticsearch.xpack.esql.qa.simulator.jqwik.SimArbitrariesFacade.unshrinkable;

/**
 * Minimal implementation of jqwik's CombinatorsFacade for the simulator.
 * Only combine2 is implemented since that's all we use.
 */
public class SimCombinatorsFacade extends Combinators.CombinatorsFacade {

    @Override
    public <T1, T2> Combinators.Combinator2<T1, T2> combine2(Arbitrary<T1> a1, Arbitrary<T2> a2) {
        return new Combinators.Combinator2<>() {
            @Override
            public <R> Arbitrary<R> as(Combinators.F2<? super T1, ? super T2, ? extends R> combinator) {
                return new SimpleArbitrary<>(genSize -> {
                    RandomGenerator<T1> gen1 = a1.generator(genSize);
                    RandomGenerator<T2> gen2 = a2.generator(genSize);
                    return random -> {
                        T1 v1 = gen1.next(random).value();
                        T2 v2 = gen2.next(random).value();
                        return unshrinkable(combinator.apply(v1, v2));
                    };
                });
            }

            @Override
            public Combinators.Combinator2<T1, T2> filter(Combinators.F2<? super T1, ? super T2, Boolean> filter) {
                throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
            }
        };
    }

    // --- Unsupported operations below ---

    @Override
    public <T1, T2, T3> Combinators.Combinator3<T1, T2, T3> combine3(Arbitrary<T1> a1, Arbitrary<T2> a2, Arbitrary<T3> a3) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T1, T2, T3, T4> Combinators.Combinator4<T1, T2, T3, T4> combine4(
        Arbitrary<T1> a1,
        Arbitrary<T2> a2,
        Arbitrary<T3> a3,
        Arbitrary<T4> a4
    ) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T1, T2, T3, T4, T5> Combinators.Combinator5<T1, T2, T3, T4, T5> combine5(
        Arbitrary<T1> a1,
        Arbitrary<T2> a2,
        Arbitrary<T3> a3,
        Arbitrary<T4> a4,
        Arbitrary<T5> a5
    ) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T1, T2, T3, T4, T5, T6> Combinators.Combinator6<T1, T2, T3, T4, T5, T6> combine6(
        Arbitrary<T1> a1,
        Arbitrary<T2> a2,
        Arbitrary<T3> a3,
        Arbitrary<T4> a4,
        Arbitrary<T5> a5,
        Arbitrary<T6> a6
    ) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T1, T2, T3, T4, T5, T6, T7> Combinators.Combinator7<T1, T2, T3, T4, T5, T6, T7> combine7(
        Arbitrary<T1> a1,
        Arbitrary<T2> a2,
        Arbitrary<T3> a3,
        Arbitrary<T4> a4,
        Arbitrary<T5> a5,
        Arbitrary<T6> a6,
        Arbitrary<T7> a7
    ) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T1, T2, T3, T4, T5, T6, T7, T8> Combinators.Combinator8<T1, T2, T3, T4, T5, T6, T7, T8> combine8(
        Arbitrary<T1> a1,
        Arbitrary<T2> a2,
        Arbitrary<T3> a3,
        Arbitrary<T4> a4,
        Arbitrary<T5> a5,
        Arbitrary<T6> a6,
        Arbitrary<T7> a7,
        Arbitrary<T8> a8
    ) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }

    @Override
    public <T> Combinators.ListCombinator<T> combineList(List<? extends Arbitrary<T>> list) {
        throw new UnsupportedOperationException("Not implemented in minimal simulator facade");
    }
}
