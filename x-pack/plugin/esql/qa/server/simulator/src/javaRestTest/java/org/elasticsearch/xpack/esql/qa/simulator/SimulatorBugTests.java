/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import org.elasticsearch.common.Strings;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.Order;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Count;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToUpper;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.Limit;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Deterministic tests that verify the simulator's bug injection infrastructure.
 * Each test constructs the known-minimal failing case for a specific {@link SimBug}
 * and calls {@link #assertDivergence} to confirm the correct and bugged simulators
 * produce different results.
 */
@RunWith(JUnit4.class)
public class SimulatorBugTests {
    static {
        SimulatorTestUtils.initLogging();
    }

    @Test
    public void bugAddIsSub() throws IOException {
        SimSchema schema = schema1IntCol();
        List<Map<String, Object>> data = List.of(Map.of("a", 5));
        EsRelation rel = buildEsRelation(schema);
        Attribute a = firstIntegerAttr(rel);
        LogicalPlan plan = new Eval(
            Source.EMPTY,
            rel,
            List.of(new Alias(Source.EMPTY, "z", new Add(Source.EMPTY, a, a, EsqlTestUtils.TEST_CFG)))
        );
        assertDivergence(schema, data, plan, SimBug.ADD_IS_SUB);
    }

    @Test
    public void bugKeepDropsFirst() throws IOException {
        SimSchema schema = schema1IntCol();
        List<Map<String, Object>> data = List.of(Map.of("a", 1));
        EsRelation rel = buildEsRelation(schema);
        List<NamedExpression> projections = rel.output().stream().map(attr -> (NamedExpression) attr).toList();
        LogicalPlan plan = new Keep(Source.EMPTY, rel, projections);
        assertDivergence(schema, data, plan, SimBug.KEEP_DROPS_FIRST);
    }

    @Test
    public void bugWhereInverted() throws IOException {
        SimSchema schema = schema1IntCol();
        List<Map<String, Object>> data = List.of(Map.of("a", 5));
        EsRelation rel = buildEsRelation(schema);
        Attribute a = firstIntegerAttr(rel);
        Expression cond = new GreaterThan(Source.EMPTY, a, new Literal(Source.EMPTY, 1, DataType.INTEGER));
        LogicalPlan plan = new Filter(Source.EMPTY, rel, cond);
        assertDivergence(schema, data, plan, SimBug.WHERE_INVERTED);
    }

    @Test
    public void bugSortReversed() throws IOException {
        SimSchema schema = schema1IntCol();
        List<Map<String, Object>> data = List.of(Map.of("a", 1), Map.of("a", 2));
        EsRelation rel = buildEsRelation(schema);
        Attribute a = firstIntegerAttr(rel);
        Order order = new Order(Source.EMPTY, a, Order.OrderDirection.ASC, Order.NullsPosition.ANY);
        OrderBy orderBy = new OrderBy(Source.EMPTY, rel, List.of(order));
        LogicalPlan plan = new Limit(Source.EMPTY, new Literal(Source.EMPTY, 1, DataType.INTEGER), orderBy);
        assertDivergence(schema, data, plan, SimBug.SORT_REVERSED);
    }

    @Test
    public void bugLimitOffByOne() throws IOException {
        SimSchema schema = schema1IntCol();
        List<Map<String, Object>> data = List.of(Map.of("a", 1), Map.of("a", 2));
        EsRelation rel = buildEsRelation(schema);
        LogicalPlan plan = new Limit(Source.EMPTY, new Literal(Source.EMPTY, 1, DataType.INTEGER), rel);
        assertDivergence(schema, data, plan, SimBug.LIMIT_OFF_BY_ONE);
    }

    @Test
    public void bugStatsCountOffByOne() throws IOException {
        SimSchema schema = schema1IntCol();
        List<Map<String, Object>> data = List.of(Map.of("a", 1));
        EsRelation rel = buildEsRelation(schema);
        Attribute a = firstIntegerAttr(rel);
        Count count = new Count(Source.EMPTY, a);
        Alias alias = new Alias(Source.EMPTY, "s0", count);
        List<Expression> groupings = List.of(a);
        List<NamedExpression> aggregates = List.of(alias, a);
        LogicalPlan plan = new Aggregate(Source.EMPTY, rel, groupings, aggregates);
        assertDivergence(schema, data, plan, SimBug.STATS_COUNT_OFF_BY_ONE);
    }

    @Test
    public void bugInlineStatsDropsRows() throws IOException {
        SimSchema schema = schema1IntCol();
        List<Map<String, Object>> data = List.of(Map.of("a", 1), Map.of("a", 2));
        EsRelation rel = buildEsRelation(schema);
        Attribute a = firstIntegerAttr(rel);
        Count count = new Count(Source.EMPTY, a);
        Alias alias = new Alias(Source.EMPTY, "s0", count);
        List<NamedExpression> aggregates = List.of(alias);
        LogicalPlan plan = new InlineStats(Source.EMPTY, new Aggregate(Source.EMPTY, rel, List.of(), aggregates));
        assertDivergence(schema, data, plan, SimBug.INLINE_STATS_DROPS_ROWS);
    }

    @Test
    public void bugToUpperIsToLower() throws IOException {
        SimSchema schema = new SimSchema("sim_test", List.of(new SimSchema.SimColumn("a", DataType.KEYWORD)));
        List<Map<String, Object>> data = List.of(Map.of("a", "Hello"));
        EsRelation rel = buildEsRelation(schema);
        Attribute a = rel.output().get(0);
        LogicalPlan plan = new Eval(
            Source.EMPTY,
            rel,
            List.of(new Alias(Source.EMPTY, "z", new ToUpper(Source.EMPTY, a, EsqlTestUtils.TEST_CFG)))
        );
        assertDivergence(schema, data, plan, SimBug.TO_UPPER_IS_TO_LOWER);
    }

    private static SimSchema schema1IntCol() {
        return new SimSchema("sim_test", List.of(new SimSchema.SimColumn("a", DataType.INTEGER)));
    }

    private static EsRelation buildEsRelation(SimSchema schema) {
        return (EsRelation) LogicalPlanGenerator.buildEsRelation(schema);
    }

    private static Attribute firstIntegerAttr(EsRelation rel) {
        return rel.output().stream().filter(a -> a.dataType() == DataType.INTEGER).findFirst().orElseThrow();
    }

    private static void assertDivergence(SimSchema schema, List<Map<String, Object>> data, LogicalPlan plan, SimBug bug)
        throws IOException {
        Simulator.Result correctResult = new Simulator(schema, data).simulate(plan);
        Simulator.Result buggedResult = new Simulator(schema, data, bug).simulate(plan);
        if (correctResult.equals(buggedResult)) {
            throw new AssertionError(Strings.format("Expected divergence with bug %s but results were equal: %s", bug, correctResult));
        }
    }
}
