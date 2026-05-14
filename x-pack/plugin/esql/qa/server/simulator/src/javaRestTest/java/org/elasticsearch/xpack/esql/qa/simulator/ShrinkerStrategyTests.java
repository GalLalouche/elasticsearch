/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import com.pholser.junit.quickcheck.random.SourceOfRandomness;

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
import java.util.Map;
import java.util.Random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/** Deterministic tests for the property-test shrinker's strategies. */
@RunWith(JUnit4.class)
public class ShrinkerStrategyTests {
    static {
        SimulatorTestUtils.initLogging();
    }

    @Test
    public void dropsEntireByClauseWithSingleGrouping() {
        EsRelation rel = relation(new SimSchema.SimColumn("d", DataType.INTEGER), new SimSchema.SimColumn("g", DataType.KEYWORD));
        Attribute d = attr(rel, "d");
        Attribute g = attr(rel, "g");
        Aggregate root = new Aggregate(
            Source.EMPTY,
            rel,
            List.of(g),
            List.of(new Alias(Source.EMPTY, "s1", new Max(Source.EMPTY, d)), g)
        );

        List<List<String>> groupingNamesAfterShrink = groupingShapesOf(shrink(rel, root));

        assertThat(groupingNamesAfterShrink, hasItem(List.of()));
    }

    @Test
    public void dropsEachGroupingIndividuallyAndDropsAll() {
        EsRelation rel = relation(
            new SimSchema.SimColumn("d", DataType.INTEGER),
            new SimSchema.SimColumn("g0", DataType.KEYWORD),
            new SimSchema.SimColumn("g1", DataType.KEYWORD)
        );
        Attribute d = attr(rel, "d");
        Attribute g0 = attr(rel, "g0");
        Attribute g1 = attr(rel, "g1");
        Aggregate root = new Aggregate(
            Source.EMPTY,
            rel,
            List.of(g0, g1),
            List.of(new Alias(Source.EMPTY, "s1", new Max(Source.EMPTY, d)), g0, g1)
        );

        List<List<String>> groupingShapes = groupingShapesOf(shrink(rel, root));

        assertThat(groupingShapes, hasItem(List.of()));
        assertThat(groupingShapes, hasItem(List.of("g0")));
        assertThat(groupingShapes, hasItem(List.of("g1")));
    }

    @Test
    public void noGroupingShrinkWhenByClauseEmpty() {
        EsRelation rel = relation(new SimSchema.SimColumn("d", DataType.INTEGER));
        Attribute d = attr(rel, "d");
        Aggregate root = new Aggregate(
            Source.EMPTY,
            rel,
            List.of(),
            List.of(new Alias(Source.EMPTY, "s1", new Max(Source.EMPTY, d)))
        );

        // Filter for Aggregate-rooted candidates only; Strategy 1 (drop outer) produces an EsRelation root.
        List<List<String>> groupingShapes = groupingShapesOf(shrink(rel, root));

        // The only Aggregate candidates have the original [] groupings; none are smaller (there's nothing smaller).
        for (List<String> shape : groupingShapes) {
            assertThat(shape, empty());
        }
    }

    @Test
    public void inlineStatsByClauseAlsoShrunk() {
        EsRelation rel = relation(new SimSchema.SimColumn("d", DataType.INTEGER), new SimSchema.SimColumn("g", DataType.KEYWORD));
        Attribute d = attr(rel, "d");
        Attribute g = attr(rel, "g");
        Aggregate inner = new Aggregate(
            Source.EMPTY,
            rel,
            List.of(g),
            List.of(new Alias(Source.EMPTY, "s1", new Max(Source.EMPTY, d)), g)
        );
        InlineStats root = new InlineStats(Source.EMPTY, inner);

        List<SimulatorPropertyIT.TestCase> candidates = shrink(rel, root);

        // The InlineStats wrapper must be preserved when shrinking groupings.
        boolean foundInlineStatsWithoutBy = candidates.stream().anyMatch(c -> {
            if (c.plan() instanceof InlineStats is) {
                return is.aggregate().groupings().isEmpty();
            }
            return false;
        });
        assertThat(foundInlineStatsWithoutBy, equalTo(true));
    }

    private static EsRelation relation(SimSchema.SimColumn... cols) {
        return (EsRelation) LogicalPlanGenerator.buildEsRelation(new SimSchema("sim_test", List.of(cols)));
    }

    private static Attribute attr(EsRelation rel, String name) {
        return rel.output().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    private static List<SimulatorPropertyIT.TestCase> shrink(EsRelation rel, LogicalPlan plan) {
        SimSchema schema = new SimSchema(rel.indexPattern(), rel.output().stream().map(a -> col(a)).toList());
        SimulatorPropertyIT.TestCase tc = new SimulatorPropertyIT.TestCase(schema, List.<Map<String, Object>>of(), plan, "");
        return new SimulatorPropertyIT.TestCaseGenerator().doShrink(new SourceOfRandomness(new Random(42L)), tc);
    }

    private static SimSchema.SimColumn col(Attribute a) {
        return new SimSchema.SimColumn(a.name(), a.dataType());
    }

    /** Extracts the grouping attribute names (in order) from each Aggregate/InlineStats candidate; skips others. */
    private static List<List<String>> groupingShapesOf(List<SimulatorPropertyIT.TestCase> candidates) {
        return candidates.stream().<List<String>>map(ShrinkerStrategyTests::groupingNames).filter(names -> names != null).toList();
    }

    private static List<String> groupingNames(SimulatorPropertyIT.TestCase tc) {
        Aggregate agg = tc.plan() instanceof InlineStats is
            ? is.aggregate()
            : tc.plan() instanceof Aggregate a ? a : null;
        if (agg == null) {
            return null;
        }
        return agg.groupings().stream().<String>map(g -> g instanceof Attribute attribute ? attribute.name() : g.toString()).toList();
    }
}
