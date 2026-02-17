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
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.PerProperty;
import net.jqwik.api.lifecycle.PropertyExecutionResult;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.index.IndexSettings;
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

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Meta-tests that verify the simulator's bug injection infrastructure.
 * Each test activates a {@link SimBug}, generates random inputs, and asserts
 * that jqwik shrinks the counter-example to a deterministic minimum.
 */
public class SimulatorBugTests {
    static {
        LogConfigurator.configureESLogging();
        // Force IndexSettings to initialize before IndexMode to break circular class init dependency.
        var unused = IndexSettings.MODE;
    }

    record MetaTestCase(SimSchema schema, List<Map<String, Object>> data, LogicalPlan plan) {
        @Override
        public String toString() {
            return Strings.format("%s, %d rows, %s", schema, data.size(), LogicalPlanPrinter.print(plan));
        }
    }

    @Property(tries = 50)
    @PerProperty(AddIsSubLifecycle.class)
    void bugAddIsSub(@ForAll("addTestCases") MetaTestCase tc) throws IOException {
        assertNoDivergence(tc, SimBug.ADD_IS_SUB);
    }

    @Provide
    Arbitrary<MetaTestCase> addTestCases() {
        return schemasWithInteger().flatMap(schema -> SimDataGenerator.rows(schema).map(data -> {
            var rel = buildEsRelation(schema);
            var intCol = firstIntegerAttr(rel);
            var add = new Add(Source.EMPTY, intCol, intCol, EsqlTestUtils.TEST_CFG);
            return new MetaTestCase(schema, data, new Eval(Source.EMPTY, rel, List.of(new Alias(Source.EMPTY, "z", add))));
        }));
    }

    public static class AddIsSubLifecycle implements PerProperty.Lifecycle {
        @Override
        public void onSuccess() {
            throw new AssertionError("Expected property to fail: ADD_IS_SUB bug should cause divergence");
        }

        @Override
        public PropertyExecutionResult onFailure(PropertyExecutionResult result) {
            var shrunk = result.shrunkSample().orElseThrow(() -> new AssertionError("No shrunk sample"));
            var tc = (MetaTestCase) shrunk.parameters().get(0);
            assertMinimal(tc, " | EVAL z = a + a", 1);
            return result.mapToSuccessful();
        }
    }

    @Property(tries = 50)
    @PerProperty(KeepDropsFirstLifecycle.class)
    void bugKeepDropsFirst(@ForAll("keepTestCases") MetaTestCase tc) throws IOException {
        assertNoDivergence(tc, SimBug.KEEP_DROPS_FIRST);
    }

    @Provide
    Arbitrary<MetaTestCase> keepTestCases() {
        return SimSchemaGenerator.schemas().flatMap(schema -> SimDataGenerator.rows(schema).map(data -> {
            var rel = buildEsRelation(schema);
            var projections = rel.output().stream().map(a -> (NamedExpression) a).toList();
            return new MetaTestCase(schema, data, new Keep(Source.EMPTY, rel, projections));
        }));
    }

    public static class KeepDropsFirstLifecycle implements PerProperty.Lifecycle {
        @Override
        public void onSuccess() {
            throw new AssertionError("Expected property to fail: KEEP_DROPS_FIRST bug should cause divergence");
        }

        @Override
        public PropertyExecutionResult onFailure(PropertyExecutionResult result) {
            var shrunk = result.shrunkSample().orElseThrow(() -> new AssertionError("No shrunk sample"));
            var tc = (MetaTestCase) shrunk.parameters().get(0);
            assertMinimal(tc, " | KEEP a", 1);
            return result.mapToSuccessful();
        }
    }

    @Property(tries = 50)
    @PerProperty(WhereInvertedLifecycle.class)
    void bugWhereInverted(@ForAll("whereTestCases") MetaTestCase tc) throws IOException {
        assertNoDivergence(tc, SimBug.WHERE_INVERTED);
    }

    @Provide
    Arbitrary<MetaTestCase> whereTestCases() {
        return schemasWithInteger().flatMap(
            schema -> Combinators.combine(SimDataGenerator.rows(schema), Arbitraries.integers().between(1, 10)).as((data, threshold) -> {
                var rel = buildEsRelation(schema);
                var intCol = firstIntegerAttr(rel);
                var cond = new GreaterThan(Source.EMPTY, intCol, new Literal(Source.EMPTY, threshold, DataType.INTEGER));
                return new MetaTestCase(schema, data, new Filter(Source.EMPTY, rel, cond));
            })
        );
    }

    public static class WhereInvertedLifecycle implements PerProperty.Lifecycle {
        @Override
        public void onSuccess() {
            throw new AssertionError("Expected property to fail: WHERE_INVERTED bug should cause divergence");
        }

        @Override
        public PropertyExecutionResult onFailure(PropertyExecutionResult result) {
            var shrunk = result.shrunkSample().orElseThrow(() -> new AssertionError("No shrunk sample"));
            var tc = (MetaTestCase) shrunk.parameters().get(0);
            assertMinimal(tc, " | WHERE a > 1", 1);
            return result.mapToSuccessful();
        }
    }

    @Property(tries = 50)
    @PerProperty(SortReversedLifecycle.class)
    void bugSortReversed(@ForAll("sortTestCases") MetaTestCase tc) throws IOException {
        assertNoDivergence(tc, SimBug.SORT_REVERSED);
    }

    @Provide
    Arbitrary<MetaTestCase> sortTestCases() {
        return schemasWithInteger().flatMap(schema -> {
            var intColName = schema.columns().stream().filter(c -> c.type() == DataType.INTEGER).findFirst().orElseThrow().name();
            return SimDataGenerator.rows(schema)
                .filter(data -> data.size() >= 2 && data.stream().map(row -> row.get(intColName)).distinct().count() >= 2)
                .map(data -> {
                    var rel = buildEsRelation(schema);
                    var intCol = firstIntegerAttr(rel);
                    var order = new Order(Source.EMPTY, intCol, Order.OrderDirection.ASC, Order.NullsPosition.ANY);
                    var orderBy = new OrderBy(Source.EMPTY, rel, List.of(order));
                    return new MetaTestCase(schema, data, new Limit(Source.EMPTY, new Literal(Source.EMPTY, 1, DataType.INTEGER), orderBy));
                });
        });
    }

    public static class SortReversedLifecycle implements PerProperty.Lifecycle {
        @Override
        public void onSuccess() {
            throw new AssertionError("Expected property to fail: SORT_REVERSED bug should cause divergence");
        }

        @Override
        public PropertyExecutionResult onFailure(PropertyExecutionResult result) {
            var shrunk = result.shrunkSample().orElseThrow(() -> new AssertionError("No shrunk sample"));
            var tc = (MetaTestCase) shrunk.parameters().get(0);
            assertMinimal(tc, " | SORT a ASC | LIMIT 1", 2);
            return result.mapToSuccessful();
        }
    }

    @Property(tries = 50)
    @PerProperty(LimitOffByOneLifecycle.class)
    void bugLimitOffByOne(@ForAll("limitTestCases") MetaTestCase tc) throws IOException {
        assertNoDivergence(tc, SimBug.LIMIT_OFF_BY_ONE);
    }

    @Provide
    Arbitrary<MetaTestCase> limitTestCases() {
        return SimSchemaGenerator.schemas().flatMap(schema -> SimDataGenerator.rows(schema).filter(data -> data.size() >= 2).map(data -> {
            var rel = buildEsRelation(schema);
            return new MetaTestCase(schema, data, new Limit(Source.EMPTY, new Literal(Source.EMPTY, 1, DataType.INTEGER), rel));
        }));
    }

    public static class LimitOffByOneLifecycle implements PerProperty.Lifecycle {
        @Override
        public void onSuccess() {
            throw new AssertionError("Expected property to fail: LIMIT_OFF_BY_ONE bug should cause divergence");
        }

        @Override
        public PropertyExecutionResult onFailure(PropertyExecutionResult result) {
            var shrunk = result.shrunkSample().orElseThrow(() -> new AssertionError("No shrunk sample"));
            var tc = (MetaTestCase) shrunk.parameters().get(0);
            assertMinimal(tc, " | LIMIT 1", 2);
            return result.mapToSuccessful();
        }
    }

    @Property(tries = 50)
    @PerProperty(StatsCountOffByOneLifecycle.class)
    void bugStatsCountOffByOne(@ForAll("statsCountTestCases") MetaTestCase tc) throws IOException {
        assertNoDivergence(tc, SimBug.STATS_COUNT_OFF_BY_ONE);
    }

    @Provide
    Arbitrary<MetaTestCase> statsCountTestCases() {
        return schemasWithInteger().flatMap(schema -> SimDataGenerator.rows(schema).map(data -> {
            var rel = buildEsRelation(schema);
            var intCol = firstIntegerAttr(rel);
            var count = new Count(Source.EMPTY, intCol);
            var alias = new Alias(Source.EMPTY, "s0", count);
            var groupings = List.<Expression>of(intCol);
            var aggregates = List.<NamedExpression>of(alias, intCol);
            return new MetaTestCase(schema, data, new Aggregate(Source.EMPTY, rel, groupings, aggregates));
        }));
    }

    public static class StatsCountOffByOneLifecycle implements PerProperty.Lifecycle {
        @Override
        public void onSuccess() {
            throw new AssertionError("Expected property to fail: STATS_COUNT_OFF_BY_ONE bug should cause divergence");
        }

        @Override
        public PropertyExecutionResult onFailure(PropertyExecutionResult result) {
            var shrunk = result.shrunkSample().orElseThrow(() -> new AssertionError("No shrunk sample"));
            var tc = (MetaTestCase) shrunk.parameters().get(0);
            assertMinimal(tc, " | STATS s0 = COUNT(a) BY a", 1);
            return result.mapToSuccessful();
        }
    }

    @Property(tries = 50)
    @PerProperty(InlineStatsDropsRowsLifecycle.class)
    void bugInlineStatsDropsRows(@ForAll("inlineStatsTestCases") MetaTestCase tc) throws IOException {
        assertNoDivergence(tc, SimBug.INLINESTATS_DROPS_ROWS);
    }

    @Provide
    Arbitrary<MetaTestCase> inlineStatsTestCases() {
        return schemasWithInteger().flatMap(schema -> SimDataGenerator.rows(schema).filter(data -> data.size() >= 2).map(data -> {
            var rel = buildEsRelation(schema);
            var intCol = firstIntegerAttr(rel);
            var count = new Count(Source.EMPTY, intCol);
            var alias = new Alias(Source.EMPTY, "s0", count);
            var aggregates = List.<NamedExpression>of(alias);
            return new MetaTestCase(schema, data, new InlineStats(Source.EMPTY, new Aggregate(Source.EMPTY, rel, List.of(), aggregates)));
        }));
    }

    public static class InlineStatsDropsRowsLifecycle implements PerProperty.Lifecycle {
        @Override
        public void onSuccess() {
            throw new AssertionError("Expected property to fail: INLINESTATS_DROPS_ROWS bug should cause divergence");
        }

        @Override
        public PropertyExecutionResult onFailure(PropertyExecutionResult result) {
            var shrunk = result.shrunkSample().orElseThrow(() -> new AssertionError("No shrunk sample"));
            var tc = (MetaTestCase) shrunk.parameters().get(0);
            assertMinimal(tc, " | INLINESTATS s0 = COUNT(a)", 2);
            return result.mapToSuccessful();
        }
    }

    private static void assertMinimal(MetaTestCase tc, String expectedPlanSuffix, int expectedRows) {
        if (tc.schema().columns().size() != 1) {
            throw new AssertionError(Strings.format("Expected 1 column after shrinking, got %d: %s", tc.schema().columns().size(), tc));
        }
        if (tc.data().size() != expectedRows) {
            throw new AssertionError(Strings.format("Expected %d row(s) after shrinking, got %d: %s", expectedRows, tc.data().size(), tc));
        }
        String expected = "FROM " + tc.schema().indexName() + expectedPlanSuffix;
        String actual = LogicalPlanPrinter.print(tc.plan());
        if (actual.equals(expected) == false) {
            throw new AssertionError(Strings.format("Expected shrunk plan [%s] but got [%s]", expected, actual));
        }
    }

    private static void assertNoDivergence(MetaTestCase tc, SimBug bug) throws IOException {
        var correct = new Simulator(tc.schema(), tc.data(), SimBug.BUG_FREE).simulate(tc.plan());
        var bugged = new Simulator(tc.schema(), tc.data(), bug).simulate(tc.plan());
        if (correct.equals(bugged) == false) {
            throw new AssertionError(Strings.format("Divergence: correct=%s bugged=%s tc=%s", correct, bugged, tc));
        }
    }

    private static Arbitrary<SimSchema> schemasWithInteger() {
        return SimSchemaGenerator.schemas().filter(s -> s.columns().stream().anyMatch(c -> c.type() == DataType.INTEGER));
    }

    private static EsRelation buildEsRelation(SimSchema schema) {
        return (EsRelation) LogicalPlanGenerator.buildEsRelation(schema);
    }

    private static Attribute firstIntegerAttr(EsRelation rel) {
        return rel.output().stream().filter(a -> a.dataType() == DataType.INTEGER).findFirst().orElseThrow();
    }
}
