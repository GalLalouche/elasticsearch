/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.simulator;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.providers.ArbitraryProvider;
import net.jqwik.api.providers.TypeUsage;

import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;

import java.util.Collections;
import java.util.Set;

public class LogicalPlanGenerator implements ArbitraryProvider {
    @Override
    public boolean canProvideFor(TypeUsage typeUsage) {
        return typeUsage.isOfType(LogicalPlan.class);
    }

    @Override
    public Set<Arbitrary<?>> provideFor(TypeUsage typeUsage, SubtypeProvider subtypeProvider) {
        var source = arbitrarySource();
        return Collections.singleton(Arbitraries.recursive(() -> arbitrarySource(), LogicalPlanGenerator::wrap, 0, 0));
    }

    private static Arbitrary<LogicalPlan> wrap(Arbitrary<LogicalPlan> child) {
        throw new AssertionError("TODO(gal) NOCOMMIT");
    }

    private static Arbitrary<LogicalPlan> arbitrarySource() {
        throw new AssertionError("TODO(gal) NOCOMMIT");
    }
}
