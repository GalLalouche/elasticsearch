/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.aggregate.AggregateFunction;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Count;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Max;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Min;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Sum;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Concat;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.EndsWith;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Left;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Length;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Reverse;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Right;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.StartsWith;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Substring;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToLower;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToUpper;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Trim;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Neg;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.NotEquals;
import org.elasticsearch.xpack.esql.parser.EsqlParser;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;
import org.elasticsearch.xpack.esql.plan.logical.Row;
import org.elasticsearch.xpack.esql.qa.simulator.Simulator.Column;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import static org.elasticsearch.xpack.esql.CsvTestUtils.Type.DATETIME;
import static org.elasticsearch.xpack.esql.core.util.TestUtils.of;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;

public class SimulatorTests extends ESTestCase {
    private static final Simulator simulator = new Simulator();

    private static final SimSchema AB_SCHEMA = new SimSchema(
        "test_idx",
        List.of(new SimSchema.SimColumn("a", DataType.INTEGER), new SimSchema.SimColumn("b", DataType.KEYWORD))
    );
    private static final List<Map<String, Object>> AB_DATA = List.of(
        Map.of("a", 1, "b", "x"),
        Map.of("a", 2, "b", "x"),
        Map.of("a", 3, "b", "y")
    );
    private static final SimSchema A_SCHEMA = new SimSchema("test_idx", List.of(new SimSchema.SimColumn("a", DataType.INTEGER)));
    private static final List<Map<String, Object>> A_DATA = List.of(Map.of("a", 2), Map.of("a", 3), Map.of("a", 5));
    private static final SimSchema X_KEYWORD_SCHEMA = new SimSchema("test_idx", List.of(new SimSchema.SimColumn("x", DataType.KEYWORD)));

    public void testRow() throws IOException {
        assertThat(simulate("ROW x=1, y=2, z=3"), equalTo(new Result(Column.ofInt("x", 1), Column.ofInt("y", 2), Column.ofInt("z", 3))));
    }

    public void testEvalSimple() throws IOException {
        assertThat(
            simulate("ROW x=1, y=2 | eval z = x + y"),
            equalTo(
                new Result(
                    Column.ofInt("x", 1),
                    Column.ofInt("y", 2),
                    // FIXME(gal, NOCOMMIT) This is silly, but because all arithmetics right now are done in long, only z is long.
                    Column.ofInt("z", 3L)
                )
            )
        );
    }

    public void testBasicFrom() throws IOException {
        assertThat(
            simulate("from sample_data"),
            equalTo(
                new Result(
                    Column.ofDatetime(
                        "@timestamp",
                        DATETIME.convert("2023-10-23T13:55:01.543Z"),
                        DATETIME.convert("2023-10-23T13:53:55.832Z"),
                        DATETIME.convert("2023-10-23T13:52:55.015Z"),
                        DATETIME.convert("2023-10-23T13:51:54.732Z"),
                        DATETIME.convert("2023-10-23T13:33:34.937Z"),
                        DATETIME.convert("2023-10-23T12:27:28.948Z"),
                        DATETIME.convert("2023-10-23T12:15:03.360Z")
                    ),
                    Column.ofIp(
                        "client_ip",
                        "172.21.3.15",
                        "172.21.3.15",
                        "172.21.3.15",
                        "172.21.3.15",
                        "172.21.0.5",
                        "172.21.2.113",
                        "172.21.2.162"
                    ),
                    Column.ofLong("event_duration", 1756467L, 5033755L, 8268153L, 725448L, 1232382L, 2764889L, 3450233L),
                    Column.ofKeyword(
                        "message",
                        "Connected to 10.1.0.1",
                        "Connection error",
                        "Connection error",
                        "Connection error",
                        "Disconnected",
                        "Connected to 10.1.0.2",
                        "Connected to 10.1.0.3"
                    )
                )
            )
        );
    }

    public void testFromKeep() throws IOException {
        assertThat(
            simulate("from sample_data | keep event_duration"),
            equalTo(new Result(Column.ofLong("event_duration", 1756467L, 5033755L, 8268153L, 725448L, 1232382L, 2764889L, 3450233L)))
        );
    }

    public void testFromDrop() throws IOException {
        assertThat(
            simulate("from sample_data | drop @timestamp, client_ip, message"),
            equalTo(new Result(Column.ofLong("event_duration", 1756467L, 5033755L, 8268153L, 725448L, 1232382L, 2764889L, 3450233L)))
        );
    }

    public void testFromKeepEval() throws IOException {
        assertThat(
            simulate("from sample_data | keep event_duration | eval duration_in_seconds = event_duration / 1000"),
            equalTo(
                new Result(
                    Column.ofLong("event_duration", 1756467L, 5033755L, 8268153L, 725448L, 1232382L, 2764889L, 3450233L),
                    Column.ofLong("duration_in_seconds", 1756L, 5033L, 8268L, 725L, 1232L, 2764L, 3450L)
                )
            )
        );
    }

    public void testFromKeepEvalComplex() throws IOException {
        assertThat(
            simulate("""
                FROM sample_data |
                KEEP event_duration |
                EVAL duration_in_seconds = event_duration / 1000 + 42, constant = "foo"
                """),
            equalTo(
                new Result(
                    Column.ofLong("event_duration", 1756467L, 5033755L, 8268153L, 725448L, 1232382L, 2764889L, 3450233L),
                    Column.ofLong("duration_in_seconds", 1798L, 5075L, 8310L, 767L, 1274L, 2806L, 3492L),
                    Column.ofKeyword("constant", "foo", "foo", "foo", "foo", "foo", "foo", "foo")
                )
            )
        );
    }

    public void testWhere() throws IOException {
        assertThat(
            simulate("from sample_data | where event_duration > 3000000"),
            equalTo(
                new Result(
                    Column.ofDatetime(
                        "@timestamp",
                        DATETIME.convert("2023-10-23T13:53:55.832Z"),
                        DATETIME.convert("2023-10-23T13:52:55.015Z"),
                        DATETIME.convert("2023-10-23T12:15:03.360Z")
                    ),
                    Column.ofIp("client_ip", "172.21.3.15", "172.21.3.15", "172.21.2.162"),
                    Column.ofLong("event_duration", 5033755L, 8268153L, 3450233L),
                    Column.ofKeyword("message", "Connection error", "Connection error", "Connected to 10.1.0.3")
                )
            )
        );
    }

    public void testEvalSub() throws IOException {
        assertThat(
            simulate("ROW x=10, y=3 | eval z = x - y"),
            equalTo(new Result(Column.ofInt("x", 10), Column.ofInt("y", 3), Column.ofInt("z", 7L)))
        );
    }

    public void testEvalMul() throws IOException {
        assertThat(
            simulate("ROW x=3, y=4 | eval z = x * y"),
            equalTo(new Result(Column.ofInt("x", 3), Column.ofInt("y", 4), Column.ofInt("z", 12L)))
        );
    }

    public void testEvalChainedReferences() throws IOException {
        assertThat(
            simulate("ROW x=1 | EVAL y = x + 1 | EVAL z = y + 1"),
            equalTo(new Result(Column.ofInt("x", 1), Column.ofInt("y", 2L), Column.ofInt("z", 3L)))
        );
    }

    public void testEvalShadowing() throws IOException {
        // When multiple EVALs assign to the same column name, ES shadows (replaces) the earlier column.
        assertThat(
            simulate("ROW x=1 | EVAL _col_0 = 9 | EVAL _col_0 = 4"),
            equalTo(new Result(Column.ofInt("x", 1), Column.ofInt("_col_0", 4)))
        );
    }

    public void testIntegerArithmeticOverflowReturnsNull() throws IOException {
        // 50000 * 50000 = 2,500,000,000 which exceeds Integer.MAX_VALUE (2,147,483,647).
        // ES|QL returns null for integer overflow; the simulator must match.
        Result result = simulate("ROW x=50000 | EVAL z = x * x");
        assertThat(result.getColumn("z").type(), equalTo(DataType.INTEGER));
        assertNull(result.getColumn("z").values().getFirst());
    }

    public void testEsRelationInMemory() throws IOException {
        assertThat(
            new Simulator(AB_SCHEMA, List.of(Map.of("a", 1, "b", "foo"), Map.of("a", 2, "b", "bar"))).simulate(
                LogicalPlanGenerator.buildEsRelation(AB_SCHEMA)
            ),
            equalTo(new Result(Column.ofInt("a", 1L, 2L), Column.ofKeyword("b", "foo", "bar")))
        );
    }

    public void testLimit() throws IOException {
        assertThat(simulate("ROW x=1, y=2 | LIMIT 1"), equalTo(new Result(Column.ofInt("x", 1), Column.ofInt("y", 2))));
    }

    public void testSort() throws IOException {
        assertThat(
            simulate("FROM sample_data | SORT event_duration ASC | LIMIT 3"),
            equalTo(
                new Result(
                    Column.ofDatetime(
                        "@timestamp",
                        DATETIME.convert("2023-10-23T13:51:54.732Z"),
                        DATETIME.convert("2023-10-23T13:33:34.937Z"),
                        DATETIME.convert("2023-10-23T13:55:01.543Z")
                    ),
                    Column.ofIp("client_ip", "172.21.3.15", "172.21.0.5", "172.21.3.15"),
                    Column.ofLong("event_duration", 725448L, 1232382L, 1756467L),
                    Column.ofKeyword("message", "Connection error", "Disconnected", "Connected to 10.1.0.1")
                )
            )
        );
    }

    public void testSortDesc() throws IOException {
        assertThat(
            simulate("FROM sample_data | SORT event_duration DESC | LIMIT 2"),
            equalTo(
                new Result(
                    Column.ofDatetime(
                        "@timestamp",
                        DATETIME.convert("2023-10-23T13:52:55.015Z"),
                        DATETIME.convert("2023-10-23T13:53:55.832Z")
                    ),
                    Column.ofIp("client_ip", "172.21.3.15", "172.21.3.15"),
                    Column.ofLong("event_duration", 8268153L, 5033755L),
                    Column.ofKeyword("message", "Connection error", "Connection error")
                )
            )
        );
    }

    public void testWhereLessThan() throws IOException {
        assertThat(
            simulate("FROM sample_data | WHERE event_duration < 2000000"),
            equalTo(
                new Result(
                    Column.ofDatetime(
                        "@timestamp",
                        DATETIME.convert("2023-10-23T13:55:01.543Z"),
                        DATETIME.convert("2023-10-23T13:51:54.732Z"),
                        DATETIME.convert("2023-10-23T13:33:34.937Z")
                    ),
                    Column.ofIp("client_ip", "172.21.3.15", "172.21.3.15", "172.21.0.5"),
                    Column.ofLong("event_duration", 1756467L, 725448L, 1232382L),
                    Column.ofKeyword("message", "Connected to 10.1.0.1", "Connection error", "Disconnected")
                )
            )
        );
    }

    public void testStatsSumWithGrouping() throws IOException {
        assertThat(
            simulateAggByB(AB_DATA, Sum::new, "s0"),
            equalTo(new Result(Column.ofLong("s0", 3L, 3L), Column.ofKeyword("b", "x", "y")))
        );
    }

    public void testStatsCountWithGrouping() throws IOException {
        assertThat(
            simulateAggByB(AB_DATA, Count::new, "cnt"),
            equalTo(new Result(Column.ofLong("cnt", 2L, 1L), Column.ofKeyword("b", "x", "y")))
        );
    }

    public void testStatsMin() throws IOException {
        assertThat(
            simulateAggByB(List.of(Map.of("a", 1, "b", "x"), Map.of("a", 5, "b", "x"), Map.of("a", 3, "b", "y")), Min::new, "lo"),
            equalTo(new Result(Column.ofInt("lo", 1L, 3L), Column.ofKeyword("b", "x", "y")))
        );
    }

    public void testStatsMax() throws IOException {
        assertThat(
            simulateAggByB(List.of(Map.of("a", 1, "b", "x"), Map.of("a", 5, "b", "x"), Map.of("a", 3, "b", "y")), Max::new, "hi"),
            equalTo(new Result(Column.ofInt("hi", 5L, 3L), Column.ofKeyword("b", "x", "y")))
        );
    }

    public void testStatsNoGrouping() throws IOException {
        assertThat(
            new Simulator(A_SCHEMA, A_DATA).simulate(buildSumNoGroupingAggregate()),
            equalTo(new Result(Column.ofLong("total", 10L)))
        );
    }

    public void testStatsOverEmptyResultReturnsNull() throws IOException {
        var from = LogicalPlanGenerator.buildEsRelation(A_SCHEMA);
        var aAttr = from.output().getFirst();
        Result result = new Simulator(A_SCHEMA, A_DATA).simulate(
            new Aggregate(
                Source.EMPTY,
                new Filter(Source.EMPTY, from, new LessThan(Source.EMPTY, aAttr, of(0))),
                List.of(),
                List.<NamedExpression>of(new Alias(Source.EMPTY, "total", new Sum(Source.EMPTY, aAttr)))
            )
        );
        assertNull(result.getColumn("total").values().getFirst());
    }

    public void testInlineStatsWithGrouping() throws IOException {
        var from = LogicalPlanGenerator.buildEsRelation(AB_SCHEMA);
        assertThat(
            new Simulator(AB_SCHEMA, AB_DATA).simulate(
                new InlineStats(
                    Source.EMPTY,
                    new Aggregate(
                        Source.EMPTY,
                        from,
                        List.of(from.output().get(1)),
                        List.of(new Alias(Source.EMPTY, "s0", new Sum(Source.EMPTY, from.output().getFirst())), from.output().get(1))
                    )
                )
            ),
            equalTo(new Result(Column.ofInt("a", 1L, 2L, 3L), Column.ofLong("s0", 3L, 3L, 3L), Column.ofKeyword("b", "x", "x", "y")))
        );
    }

    public void testInlineStatsNoGrouping() throws IOException {
        assertThat(
            new Simulator(A_SCHEMA, A_DATA).simulate(new InlineStats(Source.EMPTY, buildSumNoGroupingAggregate())),
            equalTo(new Result(Column.ofInt("a", 2L, 3L, 5L), Column.ofLong("total", 10L, 10L, 10L)))
        );
    }

    public void testStatsGroupingKeyDuplicatesAggregateName() throws IOException {
        // STATS s1 = COUNT(...), s0 = COUNT(...) BY s1 — grouping key "s1" shares name with aggregate output "s1".
        // ES deduplicates to 2 columns (s1, s0); simulator must do the same.
        var schema = new SimSchema(
            "test_idx",
            List.of(new SimSchema.SimColumn("s1", DataType.INTEGER), new SimSchema.SimColumn("b", DataType.KEYWORD))
        );
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var s1Attr = from.output().getFirst();
        var bAttr = from.output().get(1);
        assertThat(
            new Simulator(schema, List.of(Map.of("s1", 1, "b", "x"), Map.of("s1", 1, "b", "y"), Map.of("s1", 2, "b", "x"))).simulate(
                new Aggregate(
                    Source.EMPTY,
                    from,
                    List.of(s1Attr),
                    List.of(
                        new Alias(Source.EMPTY, "s1", new Count(Source.EMPTY, bAttr)),
                        new Alias(Source.EMPTY, "s0", new Count(Source.EMPTY, bAttr)),
                        s1Attr
                    )
                )
            ),
            equalTo(new Result(Column.ofLong("s0", 2L, 1L), Column.ofInt("s1", 1L, 2L)))
        );
    }

    /**
     * Guards against a regression where chained INLINE STATS incorrectly replaced a grouping key
     * value with an aggregate result of the same name. When a grouping key collides with an aggregate
     * output name, ES preserves the grouping key's value (the original child column), not the aggregate.
     */
    public void testChainedInlineStatsShadowingKeepsGroupingKeyValue() throws IOException {
        var schema = new SimSchema("test_idx", List.of(new SimSchema.SimColumn("b", DataType.INTEGER)));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var bAttr = from.output().getFirst();

        // First INLINE STATS: s1 = MAX(b + 5) BY b -> s1=6
        List<NamedExpression> aggregates = List.of(
            new Alias(Source.EMPTY, "s1", new Max(Source.EMPTY, new Add(Source.EMPTY, bAttr, of(5), EsqlTestUtils.TEST_CFG))),
            bAttr
        );

        var sim = Simulator.singleRow(schema, Map.of("b", 1));
        Result result1 = sim.simulate(new InlineStats(Source.EMPTY, new Aggregate(Source.EMPTY, from, List.of(bAttr), aggregates)));
        assertThat(result1, equalTo(new Result(Column.ofInt("b", 1L), Column.ofInt("s1", 6L))));

        // Second INLINE STATS: s1 = COUNT(b) BY s1
        // The grouping key s1 has value 6; COUNT(b) = 1.
        // ES keeps the grouping key value (s1=6), not the COUNT value (s1=1).
        var s1Ref = new ReferenceAttribute(Source.EMPTY, "s1", result1.getColumn("s1").type());
        Aggregate aggregate = new Aggregate(
            Source.EMPTY,
            new InlineStats(Source.EMPTY, new Aggregate(Source.EMPTY, from, List.of(bAttr), aggregates)),
            List.of(s1Ref),
            List.of(
                new Alias(Source.EMPTY, "s1", new Count(Source.EMPTY, new ReferenceAttribute(Source.EMPTY, "b", DataType.INTEGER))),
                s1Ref
            )
        );
        assertThat(
            sim.simulate(new InlineStats(Source.EMPTY, aggregate)),
            equalTo(new Result(Column.ofInt("b", 1L), Column.ofInt("s1", 6L)))
        );
    }

    public void testBuildRowPrints() {
        // KEYWORD literals require BytesRef (not String) per Literal's assertion.
        Row row = new Row(Source.EMPTY, List.of(new Alias(Source.EMPTY, "x", of(5)), new Alias(Source.EMPTY, "name", of("bar"))));
        String printed = LogicalPlanPrinter.print(row);
        assertThat(printed, startsWith("ROW "));
        assertThat(printed, containsString("x = "));
        assertThat(printed, containsString("name = \""));
    }

    public void testRowWithStatsNoGrouping() throws IOException {
        var row = new Row(Source.EMPTY, List.of(new Alias(Source.EMPTY, "x", of(5))));
        Result result = simulator.simulate(
            new Aggregate(
                Source.EMPTY,
                row,
                List.of(),
                List.<NamedExpression>of(new Alias(Source.EMPTY, "s0", new Count(Source.EMPTY, row.output().getFirst())))
            )
        );
        assertThat(result.getColumn("s0").values().getFirst(), equalTo(1L));
    }

    public void testRowWithWhereFiltersOut() throws IOException {
        Result result = simulate("ROW x = 1 | WHERE x > 5");
        assertThat(result.numRows(), equalTo(0));
    }

    public void testRowWithWhereKeeps() throws IOException {
        Result result = simulate("ROW x = 10 | WHERE x > 5");
        assertThat(result.numRows(), equalTo(1));
        assertThat(result.getColumn("x").values().getFirst(), equalTo(10));
    }

    public void testRowKeywordOnly() throws IOException {
        Result result = simulate("ROW name = \"hello\", tag = \"world\"");
        assertThat(result.columns().size(), equalTo(2));
        assertThat(result.getColumn("name").type(), equalTo(DataType.KEYWORD));
        assertThat(result.getColumn("tag").type(), equalTo(DataType.KEYWORD));
        assertThat(result.getColumn("name").values().getFirst(), equalTo("hello"));
        assertThat(result.getColumn("tag").values().getFirst(), equalTo("world"));
    }

    public void testRowWithKeep() throws IOException {
        Result result = simulate("ROW x = 1, y = 2, z = 3 | KEEP x, z");
        assertThat(result.columns().size(), equalTo(2));
        assertThat(result.getColumn("x").values().getFirst(), equalTo(1));
        assertThat(result.getColumn("z").values().getFirst(), equalTo(3));
    }

    public void testRowWithStatsGrouped() throws IOException {
        var row = new Row(Source.EMPTY, List.of(new Alias(Source.EMPTY, "x", of(5)), new Alias(Source.EMPTY, "y", of(3))));
        Result result = simulator.simulate(
            new Aggregate(
                Source.EMPTY,
                row,
                List.of(row.output().get(1)),
                List.of(new Alias(Source.EMPTY, "s0", new Sum(Source.EMPTY, row.output().getFirst())), row.output().get(1))
            )
        );
        assertThat(result.numRows(), equalTo(1));
        assertThat(result.getColumn("s0").values().getFirst(), equalTo(5L));
        assertThat(result.getColumn("y").values().getFirst(), equalTo(3));
    }

    public void testEvalTrim() throws IOException {
        Result result = evalUnaryStringFn(" hi ", x -> new Trim(Source.EMPTY, x));
        assertThat(result.getColumn("y").type(), equalTo(DataType.KEYWORD));
        assertThat(result.getColumn("y").values().getFirst(), equalTo("hi"));
    }

    public void testEvalToUpper() throws IOException {
        Result result = evalUnaryStringFn("hello", x -> new ToUpper(Source.EMPTY, x, EsqlTestUtils.TEST_CFG));
        assertThat(result.getColumn("y").type(), equalTo(DataType.KEYWORD));
        assertThat(result.getColumn("y").values().getFirst(), equalTo("HELLO"));
    }

    public void testEvalToLower() throws IOException {
        Result result = evalUnaryStringFn("HELLO", x -> new ToLower(Source.EMPTY, x, EsqlTestUtils.TEST_CFG));
        assertThat(result.getColumn("y").type(), equalTo(DataType.KEYWORD));
        assertThat(result.getColumn("y").values().getFirst(), equalTo("hello"));
    }

    public void testEvalReverse() throws IOException {
        Result result = evalUnaryStringFn("hello", x -> new Reverse(Source.EMPTY, x));
        assertThat(result.getColumn("y").type(), equalTo(DataType.KEYWORD));
        assertThat(result.getColumn("y").values().getFirst(), equalTo("olleh"));
    }

    public void testNestedStringFunctions() throws IOException {
        var schema = X_KEYWORD_SCHEMA;
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var xAttr = from.output().getFirst();
        Result result = Simulator.singleRow(schema, Map.of("x", " hi "))
            .simulate(
                new Eval(
                    Source.EMPTY,
                    from,
                    List.of(new Alias(Source.EMPTY, "y", new ToUpper(Source.EMPTY, new Trim(Source.EMPTY, xAttr), EsqlTestUtils.TEST_CFG)))
                )
            );
        assertThat(result.getColumn("y").type(), equalTo(DataType.KEYWORD));
        assertThat(result.getColumn("y").values().getFirst(), equalTo("HI"));
    }

    public void testEvalLength() throws IOException {
        Result result = evalUnaryStringFn("hello", x -> new Length(Source.EMPTY, x));
        assertThat(result.getColumn("y").type(), equalTo(DataType.INTEGER));
        assertThat(result.getColumn("y").values().getFirst(), equalTo(5L));
    }

    public void testEvalLengthEmpty() throws IOException {
        Result result = evalUnaryStringFn("", x -> new Length(Source.EMPTY, x));
        assertThat(result.getColumn("y").type(), equalTo(DataType.INTEGER));
        assertThat(result.getColumn("y").values().getFirst(), equalTo(0L));
    }

    public void testEvalConcat() throws IOException {
        var schema = new SimSchema(
            "test_idx",
            List.of(new SimSchema.SimColumn("a", DataType.KEYWORD), new SimSchema.SimColumn("b", DataType.KEYWORD))
        );
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        Result result = Simulator.singleRow(schema, Map.of("a", "foo", "b", "bar"))
            .simulate(
                new Eval(
                    Source.EMPTY,
                    from,
                    List.of(new Alias(Source.EMPTY, "c", new Concat(Source.EMPTY, from.output().getFirst(), List.of(from.output().get(1)))))
                )
            );
        assertThat(result.getColumn("c").type(), equalTo(DataType.KEYWORD));
        assertThat(result.getColumn("c").values().getFirst(), equalTo("foobar"));
    }

    public void testEvalLeft() throws IOException {
        Result result = evalLeftOrRight((str, len) -> new Left(Source.EMPTY, str, len));
        assertThat(result.getColumn("y").type(), equalTo(DataType.KEYWORD));
        assertThat(result.getColumn("y").values().getFirst(), equalTo("hel"));
    }

    public void testEvalRight() throws IOException {
        Result result = evalLeftOrRight((str, len) -> new Right(Source.EMPTY, str, len));
        assertThat(result.getColumn("y").type(), equalTo(DataType.KEYWORD));
        assertThat(result.getColumn("y").values().getFirst(), equalTo("llo"));
    }

    public void testFilterStartsWith() throws IOException {
        Result result = filterKeywordColumn(xAttr -> new StartsWith(Source.EMPTY, xAttr, of("foo")));
        assertThat(result.numRows(), equalTo(1));
        assertThat(result.getColumn("x").values().getFirst(), equalTo("foobar"));
    }

    public void testFilterEndsWith() throws IOException {
        Result result = filterKeywordColumn(xAttr -> new EndsWith(Source.EMPTY, xAttr, of("bar")));
        assertThat(result.numRows(), equalTo(1));
        assertThat(result.getColumn("x").values().getFirst(), equalTo("foobar"));
    }

    public void testEvalSubstring() throws IOException {
        var schema = X_KEYWORD_SCHEMA;
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        Result result = Simulator.singleRow(schema, Map.of("x", "hello"))
            .simulate(
                new Eval(
                    Source.EMPTY,
                    from,
                    List.of(new Alias(Source.EMPTY, "y", new Substring(Source.EMPTY, from.output().getFirst(), of(2), of(3))))
                )
            );
        assertThat(result.getColumn("y").type(), equalTo(DataType.KEYWORD));
        assertThat(result.getColumn("y").values().getFirst(), equalTo("ell"));
    }

    public void testEvalMod() throws IOException {
        assertThat(
            simulate("ROW x=7, y=3 | EVAL z = x % y"),
            equalTo(new Result(Column.ofInt("x", 7), Column.ofInt("y", 3), Column.ofInt("z", 1L)))
        );
    }

    public void testEvalDivByZero() throws IOException {
        Result result = simulate("ROW x=5 | EVAL z = x / 0");
        assertNull(result.getColumn("z").values().getFirst());
    }

    public void testEvalModByZero() throws IOException {
        Result result = simulate("ROW x=5 | EVAL z = x % 0");
        assertNull(result.getColumn("z").values().getFirst());
    }

    public void testEvalNeg() throws IOException {
        assertThat(simulate("ROW x=3 | EVAL z = -x"), equalTo(new Result(Column.ofInt("x", 3), Column.ofInt("z", -3L))));
    }

    public void testNegOverflow() throws IOException {
        // -Integer.MIN_VALUE overflows 32-bit; ES|QL returns null
        var schema = new SimSchema("test_idx", List.of(new SimSchema.SimColumn("a", DataType.INTEGER)));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        Result result = Simulator.singleRow(schema, Map.of("a", Integer.MIN_VALUE))
            .simulate(new Eval(Source.EMPTY, from, List.of(new Alias(Source.EMPTY, "z", new Neg(Source.EMPTY, from.output().getFirst())))));
        assertNull(result.getColumn("z").values().getFirst());
    }

    public void testWhereGreaterThanOrEqual() throws IOException {
        Result result = simulate("FROM sample_data | WHERE event_duration >= 1756467");
        assertThat(result.numRows(), equalTo(5));
        for (Object val : result.getColumn("event_duration").values()) {
            assertThat(Simulator.toLong(val), greaterThanOrEqualTo(1756467L));
        }
    }

    public void testWhereLessThanOrEqual() throws IOException {
        Result result = simulate("FROM sample_data | WHERE event_duration <= 1756467");
        assertThat(result.numRows(), equalTo(3));
        for (Object val : result.getColumn("event_duration").values()) {
            assertThat(Simulator.toLong(val), lessThanOrEqualTo(1756467L));
        }
    }

    public void testWhereEquals() throws IOException {
        Result result = simulate("FROM sample_data | WHERE event_duration == 1756467");
        assertThat(result.numRows(), equalTo(1));
        assertThat(result.getColumn("event_duration").values().getFirst(), equalTo(1756467L));
    }

    public void testWhereNotEquals() throws IOException {
        // Build programmatically because the ES|QL parser represents != as Not(Equals(...)), not as NotEquals directly.
        var schema = new SimSchema(
            "test_idx",
            List.of(new SimSchema.SimColumn("a", DataType.INTEGER), new SimSchema.SimColumn("b", DataType.KEYWORD))
        );
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        Result result = new Simulator(schema, List.of(Map.of("a", 1, "b", "x"), Map.of("a", 2, "b", "y"), Map.of("a", 3, "b", "z")))
            .simulate(new Filter(Source.EMPTY, from, new NotEquals(Source.EMPTY, from.output().getFirst(), of(2))));
        assertThat(result.numRows(), equalTo(2));
        assertThat(result.getColumn("a").values(), equalTo(List.of(1L, 3L)));
    }

    private static Result simulateAggByB(
        List<Map<String, Object>> data,
        BiFunction<Source, Expression, AggregateFunction> aggFn,
        String alias
    ) throws IOException {
        var from = LogicalPlanGenerator.buildEsRelation(AB_SCHEMA);
        return new Simulator(AB_SCHEMA, data).simulate(
            new Aggregate(
                Source.EMPTY,
                from,
                List.of(from.output().get(1)),
                List.of(new Alias(Source.EMPTY, alias, aggFn.apply(Source.EMPTY, from.output().getFirst())), from.output().get(1))
            )
        );
    }

    private static Result evalLeftOrRight(BiFunction<Expression, Expression, Expression> fn) throws IOException {
        var schema = X_KEYWORD_SCHEMA;
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        return Simulator.singleRow(schema, Map.of("x", "hello"))
            .simulate(new Eval(Source.EMPTY, from, List.of(new Alias(Source.EMPTY, "y", fn.apply(from.output().getFirst(), of(3))))));
    }

    private static Result filterKeywordColumn(UnaryOperator<Expression> predicateFn) throws IOException {
        var schema = X_KEYWORD_SCHEMA;
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        return new Simulator(schema, List.of(Map.of("x", "foobar"), Map.of("x", "bazqux"))).simulate(
            new Filter(Source.EMPTY, from, predicateFn.apply(from.output().getFirst()))
        );
    }

    private static Aggregate buildSumNoGroupingAggregate() {
        var from = LogicalPlanGenerator.buildEsRelation(A_SCHEMA);
        return new Aggregate(
            Source.EMPTY,
            from,
            List.of(),
            List.of(new Alias(Source.EMPTY, "total", new Sum(Source.EMPTY, from.output().getFirst())))
        );
    }

    private static Result evalUnaryStringFn(String input, UnaryOperator<Expression> fn) throws IOException {
        var schema = X_KEYWORD_SCHEMA;
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        return Simulator.singleRow(schema, Map.of("x", input))
            .simulate(new Eval(Source.EMPTY, from, List.of(new Alias(Source.EMPTY, "y", fn.apply(from.output().getFirst())))));
    }

    private Result simulate(String statement) throws IOException {
        return simulator.simulate(EsqlParser.INSTANCE.createStatement(statement).plan());
    }
}
