/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

import org.elasticsearch.index.IndexMode;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.expression.UnresolvedAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.plan.IndexPattern;
import org.elasticsearch.xpack.esql.plan.logical.Drop;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.UnresolvedRelation;

import java.util.List;

public class LogicalPlanGenerator {

    private static final List<String> ALL_COLUMNS = List.of("x", "y");

    public static Arbitrary<LogicalPlan> simDataPlans() {
        return Arbitraries.recursive(LogicalPlanGenerator::arbitrarySource, LogicalPlanGenerator::wrap, 1);
    }

    private static Arbitrary<LogicalPlan> arbitrarySource() {
        return Arbitraries.just(
            new UnresolvedRelation(Source.EMPTY, new IndexPattern(Source.EMPTY, "sim_data"), false, List.of(), IndexMode.STANDARD, null)
        );
    }

    private static Arbitrary<LogicalPlan> wrap(Arbitrary<LogicalPlan> child) {
        var columns = arbitraryNonEmptySubset();
        return Arbitraries.oneOf(wrapKeep(child, columns), wrapDrop(child, columns));
    }

    private static Arbitrary<List<String>> arbitraryNonEmptySubset() {
        return Arbitraries.of(ALL_COLUMNS).set().ofMinSize(1).ofMaxSize(ALL_COLUMNS.size()).map(set -> List.copyOf(set));
    }

    private static Arbitrary<LogicalPlan> wrapKeep(Arbitrary<LogicalPlan> child, Arbitrary<List<String>> columns) {
        return Combinators.combine(child, columns).as((plan, cols) -> {
            var attrs = cols.stream().map(name -> (NamedExpression) new UnresolvedAttribute(Source.EMPTY, name)).toList();
            return new Keep(Source.EMPTY, plan, attrs);
        });
    }

    private static Arbitrary<LogicalPlan> wrapDrop(Arbitrary<LogicalPlan> child, Arbitrary<List<String>> columns) {
        return Combinators.combine(child, columns).as((plan, cols) -> {
            var attrs = cols.stream().map(name -> (NamedExpression) new UnresolvedAttribute(Source.EMPTY, name)).toList();
            return new Drop(Source.EMPTY, plan, attrs);
        });
    }
}
