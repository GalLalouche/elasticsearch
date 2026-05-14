/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Max;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@RunWith(JUnit4.class)
public class SimulatorTestUtilsTests {
    static {
        SimulatorTestUtils.initLogging();
    }

    /** STATS s1 = MAX(d) BY g references {@code d} (agg field) and {@code g} (grouping). */
    @Test
    public void aggregateReferencesGroupingAndAggField() {
        EsRelation rel = relation(new SimSchema.SimColumn("d", DataType.INTEGER), new SimSchema.SimColumn("g", DataType.KEYWORD));
        Attribute d = attr(rel, "d");
        Attribute g = attr(rel, "g");
        Aggregate agg = new Aggregate(
            Source.EMPTY,
            rel,
            List.of(g),
            List.of(new Alias(Source.EMPTY, "s1", new Max(Source.EMPTY, d)), g)
        );
        assertThat(refNames(agg), equalTo(Set.of("d", "g")));
    }

    /**
     * Regression for the shrinker bug: with an upstream attribute named {@code s1},
     * "STATS s1 = MAX(s1)" must report a reference to {@code s1} so the shrinker
     * keeps the stage that introduces it.
     */
    @Test
    public void aggregateReferencesUpstreamAliasInAggField() {
        EsRelation rel = relation(new SimSchema.SimColumn("s1", DataType.INTEGER));
        Attribute s1 = attr(rel, "s1");
        Aggregate agg = new Aggregate(
            Source.EMPTY,
            rel,
            List.of(),
            List.of(new Alias(Source.EMPTY, "s1", new Max(Source.EMPTY, s1)))
        );
        assertThat(refNames(agg), equalTo(Set.of("s1")));
    }

    /** INLINE STATS delegates to its inner Aggregate for reference collection. */
    @Test
    public void inlineStatsDelegatesToInnerAggregate() {
        EsRelation rel = relation(new SimSchema.SimColumn("d", DataType.INTEGER));
        Attribute d = attr(rel, "d");
        InlineStats is = new InlineStats(
            Source.EMPTY,
            new Aggregate(Source.EMPTY, rel, List.of(), List.of(new Alias(Source.EMPTY, "s0", new Max(Source.EMPTY, d))))
        );
        assertThat(refNames(is), equalTo(Set.of("d")));
    }

    private static EsRelation relation(SimSchema.SimColumn... cols) {
        return (EsRelation) LogicalPlanGenerator.buildEsRelation(new SimSchema("sim_test", List.of(cols)));
    }

    private static Attribute attr(EsRelation rel, String name) {
        return rel.output().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    private static Set<String> refNames(LogicalPlan plan) {
        return SimulatorTestUtils.collectAttributeReferences(plan).stream().map(Attribute::name).collect(Collectors.toSet());
    }
}
