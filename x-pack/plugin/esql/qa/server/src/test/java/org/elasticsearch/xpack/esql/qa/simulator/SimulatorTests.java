/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.CsvTestUtils;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Count;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Max;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Min;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Sum;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.parser.EsqlParser;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;
import org.elasticsearch.xpack.esql.plan.logical.Row;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;

public class SimulatorTests extends ESTestCase {
    private final Simulator simulator = new Simulator();

    private final EsqlParser parser = EsqlParser.INSTANCE;

    public void testRow() throws Exception {
        assertThat(
            simulate("ROW x=1, y=2, z=3"),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column("x", DataType.INTEGER, List.of(1)),
                        new Simulator.Column("y", DataType.INTEGER, List.of(2)),
                        new Simulator.Column("z", DataType.INTEGER, List.of(3))
                    )
                )
            )
        );
    }

    public void testEvalSimple() throws Exception {
        assertThat(
            simulate("ROW x=1, y=2 | eval z = x + y"),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column("x", DataType.INTEGER, List.of(1)),
                        new Simulator.Column("y", DataType.INTEGER, List.of(2)),
                        // FIXME(gal, NOCOMMIT) This is silly, but because all arithmetics right now are done in long, only z is long.
                        new Simulator.Column("z", DataType.INTEGER, List.of(3L))
                    )
                )
            )
        );
    }

    public void testBasicFrom() throws Exception {
        assertThat(
            simulate("from sample_data"),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column(
                            "@timestamp",
                            DataType.DATETIME,
                            List.of(
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T13:55:01.543Z"),
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T13:53:55.832Z"),
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T13:52:55.015Z"),
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T13:51:54.732Z"),
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T13:33:34.937Z"),
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T12:27:28.948Z"),
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T12:15:03.360Z")
                            )
                        ),
                        new Simulator.Column(
                            "client_ip",
                            DataType.IP,
                            List.of(
                                "172.21.3.15",
                                "172.21.3.15",
                                "172.21.3.15",
                                "172.21.3.15",
                                "172.21.0.5",
                                "172.21.2.113",
                                "172.21.2.162"
                            )
                        ),
                        new Simulator.Column(
                            "event_duration",
                            DataType.LONG,
                            List.of(1756467L, 5033755L, 8268153L, 725448L, 1232382L, 2764889L, 3450233L)
                        ),
                        new Simulator.Column(
                            "message",
                            DataType.KEYWORD,
                            List.of(
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
                )
            )
        );
    }

    public void testFromKeep() throws Exception {
        assertThat(
            simulate("from sample_data | keep event_duration"),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column(
                            "event_duration",
                            DataType.LONG,
                            List.of(1756467L, 5033755L, 8268153L, 725448L, 1232382L, 2764889L, 3450233L)
                        )
                    )
                )
            )
        );
    }

    public void testFromDrop() throws Exception {
        assertThat(
            simulate("from sample_data | drop @timestamp, client_ip, message"),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column(
                            "event_duration",
                            DataType.LONG,
                            List.of(1756467L, 5033755L, 8268153L, 725448L, 1232382L, 2764889L, 3450233L)
                        )
                    )
                )
            )
        );
    }

    public void testFromKeepEval() throws Exception {
        assertThat(
            simulate("from sample_data | keep event_duration | eval duration_in_seconds = event_duration / 1000"),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column(
                            "event_duration",
                            DataType.LONG,
                            List.of(1756467L, 5033755L, 8268153L, 725448L, 1232382L, 2764889L, 3450233L)
                        ),
                        new Simulator.Column("duration_in_seconds", DataType.LONG, List.of(1756L, 5033L, 8268L, 725L, 1232L, 2764L, 3450L))
                    )
                )
            )
        );
    }

    public void testFromKeepEvalComplex() throws Exception {
        assertThat(
            simulate("""
                FROM sample_data |
                KEEP event_duration |
                EVAL duration_in_seconds = event_duration / 1000 + 42, constant = "foo"
                """),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column(
                            "event_duration",
                            DataType.LONG,
                            List.of(1756467L, 5033755L, 8268153L, 725448L, 1232382L, 2764889L, 3450233L)
                        ),
                        new Simulator.Column("duration_in_seconds", DataType.LONG, List.of(1798L, 5075L, 8310L, 767L, 1274L, 2806L, 3492L)),
                        new Simulator.Column("constant", DataType.KEYWORD, List.of("foo", "foo", "foo", "foo", "foo", "foo", "foo"))
                    )
                )
            )
        );
    }

    public void testWhere() throws Exception {
        assertThat(
            simulate("from sample_data | where event_duration > 3000000"),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column(
                            "@timestamp",
                            DataType.DATETIME,
                            List.of(
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T13:53:55.832Z"),
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T13:52:55.015Z"),
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T12:15:03.360Z")
                            )
                        ),
                        new Simulator.Column("client_ip", DataType.IP, List.of("172.21.3.15", "172.21.3.15", "172.21.2.162")),
                        new Simulator.Column("event_duration", DataType.LONG, List.of(5033755L, 8268153L, 3450233L)),
                        new Simulator.Column(
                            "message",
                            DataType.KEYWORD,
                            List.of("Connection error", "Connection error", "Connected to 10.1.0.3")
                        )
                    )
                )
            )
        );
    }

    public void testEvalSub() throws Exception {
        assertThat(
            simulate("ROW x=10, y=3 | eval z = x - y"),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column("x", DataType.INTEGER, List.of(10)),
                        new Simulator.Column("y", DataType.INTEGER, List.of(3)),
                        new Simulator.Column("z", DataType.INTEGER, List.of(7L))
                    )
                )
            )
        );
    }

    public void testEvalMul() throws Exception {
        assertThat(
            simulate("ROW x=3, y=4 | eval z = x * y"),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column("x", DataType.INTEGER, List.of(3)),
                        new Simulator.Column("y", DataType.INTEGER, List.of(4)),
                        new Simulator.Column("z", DataType.INTEGER, List.of(12L))
                    )
                )
            )
        );
    }

    public void testEvalShadowing() throws Exception {
        // When multiple EVALs assign to the same column name, ES shadows (replaces) the earlier column.
        assertThat(
            simulate("ROW x=1 | EVAL _col_0 = 9 | EVAL _col_0 = 4"),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column("x", DataType.INTEGER, List.of(1)),
                        new Simulator.Column("_col_0", DataType.INTEGER, List.of(4))
                    )
                )
            )
        );
    }

    public void testIntegerArithmeticOverflowReturnsNull() throws Exception {
        // 50000 * 50000 = 2,500,000,000 which exceeds Integer.MAX_VALUE (2,147,483,647).
        // ES|QL returns null for integer overflow; the simulator must match.
        Simulator.Result result = simulate("ROW x=50000 | EVAL z = x * x");
        assertThat(result.columns().get(1).name(), equalTo("z"));
        assertThat(result.columns().get(1).type(), equalTo(DataType.INTEGER));
        assertNull(result.columns().get(1).values().get(0));
    }

    public void testEsRelationInMemory() throws Exception {
        var schema = new SimSchema(
            "test_idx",
            List.of(new SimSchema.SimColumn("a", DataType.INTEGER), new SimSchema.SimColumn("b", DataType.KEYWORD))
        );
        var data = List.<Map<String, Object>>of(Map.of("a", 1, "b", "foo"), Map.of("a", 2, "b", "bar"));
        List<Attribute> attrs = schema.columns()
            .stream()
            .map(col -> (Attribute) new ReferenceAttribute(Source.EMPTY, col.name(), col.type()))
            .toList();
        var plan = new EsRelation(Source.EMPTY, schema.indexName(), IndexMode.STANDARD, Map.of(), Map.of(), Map.of(), attrs);

        assertThat(
            new Simulator(schema, data).simulate(plan),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column("a", DataType.INTEGER, List.of(1L, 2L)),
                        new Simulator.Column("b", DataType.KEYWORD, List.of("foo", "bar"))
                    )
                )
            )
        );
    }

    public void testLimit() throws Exception {
        assertThat(
            simulate("ROW x=1, y=2 | LIMIT 1"),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column("x", DataType.INTEGER, List.of(1)),
                        new Simulator.Column("y", DataType.INTEGER, List.of(2))
                    )
                )
            )
        );
    }

    public void testSort() throws Exception {
        assertThat(
            simulate("FROM sample_data | SORT event_duration ASC | LIMIT 3"),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column(
                            "@timestamp",
                            DataType.DATETIME,
                            List.of(
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T13:51:54.732Z"),
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T13:33:34.937Z"),
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T13:55:01.543Z")
                            )
                        ),
                        new Simulator.Column("client_ip", DataType.IP, List.of("172.21.3.15", "172.21.0.5", "172.21.3.15")),
                        new Simulator.Column("event_duration", DataType.LONG, List.of(725448L, 1232382L, 1756467L)),
                        new Simulator.Column(
                            "message",
                            DataType.KEYWORD,
                            List.of("Connection error", "Disconnected", "Connected to 10.1.0.1")
                        )
                    )
                )
            )
        );
    }

    public void testSortDesc() throws Exception {
        assertThat(
            simulate("FROM sample_data | SORT event_duration DESC | LIMIT 2"),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column(
                            "@timestamp",
                            DataType.DATETIME,
                            List.of(
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T13:52:55.015Z"),
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T13:53:55.832Z")
                            )
                        ),
                        new Simulator.Column("client_ip", DataType.IP, List.of("172.21.3.15", "172.21.3.15")),
                        new Simulator.Column("event_duration", DataType.LONG, List.of(8268153L, 5033755L)),
                        new Simulator.Column("message", DataType.KEYWORD, List.of("Connection error", "Connection error"))
                    )
                )
            )
        );
    }

    public void testWhereLessThan() throws Exception {
        assertThat(
            simulate("FROM sample_data | WHERE event_duration < 2000000"),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column(
                            "@timestamp",
                            DataType.DATETIME,
                            List.of(
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T13:55:01.543Z"),
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T13:51:54.732Z"),
                                CsvTestUtils.Type.DATETIME.convert("2023-10-23T13:33:34.937Z")
                            )
                        ),
                        new Simulator.Column("client_ip", DataType.IP, List.of("172.21.3.15", "172.21.3.15", "172.21.0.5")),
                        new Simulator.Column("event_duration", DataType.LONG, List.of(1756467L, 725448L, 1232382L)),
                        new Simulator.Column(
                            "message",
                            DataType.KEYWORD,
                            List.of("Connected to 10.1.0.1", "Connection error", "Disconnected")
                        )
                    )
                )
            )
        );
    }

    public void testStatsSumWithGrouping() throws Exception {
        var schema = new SimSchema(
            "test_idx",
            List.of(new SimSchema.SimColumn("a", DataType.INTEGER), new SimSchema.SimColumn("b", DataType.KEYWORD))
        );
        var data = List.<Map<String, Object>>of(Map.of("a", 1, "b", "x"), Map.of("a", 2, "b", "x"), Map.of("a", 3, "b", "y"));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var aAttr = from.output().get(0);
        var bAttr = from.output().get(1);
        var aggregate = new Aggregate(
            Source.EMPTY,
            from,
            List.<Expression>of(bAttr),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "s0", new Sum(Source.EMPTY, aAttr)), bAttr)
        );
        assertThat(
            new Simulator(schema, data).simulate(aggregate),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column("s0", DataType.LONG, List.of(3L, 3L)),
                        new Simulator.Column("b", DataType.KEYWORD, List.of("x", "y"))
                    )
                )
            )
        );
    }

    public void testStatsCountWithGrouping() throws Exception {
        var schema = new SimSchema(
            "test_idx",
            List.of(new SimSchema.SimColumn("a", DataType.INTEGER), new SimSchema.SimColumn("b", DataType.KEYWORD))
        );
        var data = List.<Map<String, Object>>of(Map.of("a", 1, "b", "x"), Map.of("a", 2, "b", "x"), Map.of("a", 3, "b", "y"));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var aAttr = from.output().get(0);
        var bAttr = from.output().get(1);
        var aggregate = new Aggregate(
            Source.EMPTY,
            from,
            List.<Expression>of(bAttr),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "cnt", new Count(Source.EMPTY, aAttr)), bAttr)
        );
        assertThat(
            new Simulator(schema, data).simulate(aggregate),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column("cnt", DataType.LONG, List.of(2L, 1L)),
                        new Simulator.Column("b", DataType.KEYWORD, List.of("x", "y"))
                    )
                )
            )
        );
    }

    public void testStatsMinMax() throws Exception {
        var schema = new SimSchema(
            "test_idx",
            List.of(new SimSchema.SimColumn("a", DataType.INTEGER), new SimSchema.SimColumn("b", DataType.KEYWORD))
        );
        var data = List.<Map<String, Object>>of(Map.of("a", 1, "b", "x"), Map.of("a", 5, "b", "x"), Map.of("a", 3, "b", "y"));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var aAttr = from.output().get(0);
        var bAttr = from.output().get(1);
        var aggMin = new Aggregate(
            Source.EMPTY,
            from,
            List.<Expression>of(bAttr),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "lo", new Min(Source.EMPTY, aAttr)), bAttr)
        );
        assertThat(
            new Simulator(schema, data).simulate(aggMin),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column("lo", DataType.INTEGER, List.of(1L, 3L)),
                        new Simulator.Column("b", DataType.KEYWORD, List.of("x", "y"))
                    )
                )
            )
        );
        var aggMax = new Aggregate(
            Source.EMPTY,
            from,
            List.<Expression>of(bAttr),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "hi", new Max(Source.EMPTY, aAttr)), bAttr)
        );
        assertThat(
            new Simulator(schema, data).simulate(aggMax),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column("hi", DataType.INTEGER, List.of(5L, 3L)),
                        new Simulator.Column("b", DataType.KEYWORD, List.of("x", "y"))
                    )
                )
            )
        );
    }

    public void testStatsNoGrouping() throws Exception {
        var schema = new SimSchema("test_idx", List.of(new SimSchema.SimColumn("a", DataType.INTEGER)));
        var data = List.<Map<String, Object>>of(Map.of("a", 2), Map.of("a", 3), Map.of("a", 5));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var aAttr = from.output().get(0);
        var aggregate = new Aggregate(
            Source.EMPTY,
            from,
            List.of(),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "total", new Sum(Source.EMPTY, aAttr)))
        );
        assertThat(
            new Simulator(schema, data).simulate(aggregate),
            equalTo(new Simulator.Result(List.of(new Simulator.Column("total", DataType.LONG, List.of(10L)))))
        );
    }

    public void testInlineStatsWithGrouping() throws Exception {
        var schema = new SimSchema(
            "test_idx",
            List.of(new SimSchema.SimColumn("a", DataType.INTEGER), new SimSchema.SimColumn("b", DataType.KEYWORD))
        );
        var data = List.<Map<String, Object>>of(Map.of("a", 1, "b", "x"), Map.of("a", 2, "b", "x"), Map.of("a", 3, "b", "y"));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var aAttr = from.output().get(0);
        var bAttr = from.output().get(1);
        var aggregate = new Aggregate(
            Source.EMPTY,
            from,
            List.<Expression>of(bAttr),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "s0", new Sum(Source.EMPTY, aAttr)), bAttr)
        );
        var inlineStats = new InlineStats(Source.EMPTY, aggregate);
        assertThat(
            new Simulator(schema, data).simulate(inlineStats),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column("a", DataType.INTEGER, List.of(1L, 2L, 3L)),
                        new Simulator.Column("s0", DataType.LONG, List.of(3L, 3L, 3L)),
                        new Simulator.Column("b", DataType.KEYWORD, List.of("x", "x", "y"))
                    )
                )
            )
        );
    }

    public void testInlineStatsNoGrouping() throws Exception {
        var schema = new SimSchema("test_idx", List.of(new SimSchema.SimColumn("a", DataType.INTEGER)));
        var data = List.<Map<String, Object>>of(Map.of("a", 2), Map.of("a", 3), Map.of("a", 5));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var aAttr = from.output().get(0);
        var aggregate = new Aggregate(
            Source.EMPTY,
            from,
            List.of(),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "total", new Sum(Source.EMPTY, aAttr)))
        );
        var inlineStats = new InlineStats(Source.EMPTY, aggregate);
        assertThat(
            new Simulator(schema, data).simulate(inlineStats),
            equalTo(
                new Simulator.Result(
                    List.of(
                        new Simulator.Column("a", DataType.INTEGER, List.of(2L, 3L, 5L)),
                        new Simulator.Column("total", DataType.LONG, List.of(10L, 10L, 10L))
                    )
                )
            )
        );
    }

    public void testStatsGroupingKeyDuplicatesAggregateName() throws Exception {
        // STATS s1 = COUNT(...), s0 = COUNT(...) BY s1 — grouping key "s1" shares name with aggregate output "s1".
        // ES deduplicates to 2 columns (s1, s0); simulator must do the same.
        var schema = new SimSchema(
            "test_idx",
            List.of(new SimSchema.SimColumn("s1", DataType.INTEGER), new SimSchema.SimColumn("b", DataType.KEYWORD))
        );
        var data = List.<Map<String, Object>>of(Map.of("s1", 1, "b", "x"), Map.of("s1", 1, "b", "y"), Map.of("s1", 2, "b", "x"));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var s1Attr = from.output().get(0);
        var bAttr = from.output().get(1);
        // aggregates list: [Alias("s1", COUNT(b)), Alias("s0", COUNT(b)), s1Attr]
        var aggregate = new Aggregate(
            Source.EMPTY,
            from,
            List.<Expression>of(s1Attr),
            List.<NamedExpression>of(
                new Alias(Source.EMPTY, "s1", new Count(Source.EMPTY, bAttr)),
                new Alias(Source.EMPTY, "s0", new Count(Source.EMPTY, bAttr)),
                s1Attr
            )
        );
        Simulator.Result result = new Simulator(schema, data).simulate(aggregate);
        assertThat(result.columns().size(), equalTo(2));
        assertThat(result.columns().get(0).name(), equalTo("s0"));
        assertThat(result.columns().get(1).name(), equalTo("s1"));
        assertThat(result.columns().get(0).values(), equalTo(List.of(2L, 1L)));
        assertThat(result.columns().get(1).values(), equalTo(List.of(1L, 2L)));
    }

    public void testChainedInlineStatsShadowingKeepsGroupingKeyValue() throws Exception {
        // When a second INLINE STATS redefines a column from the first via an aggregate that shares
        // its name with a grouping key, ES preserves the grouping key's (original child) value.
        // Example: INLINE STATS s1 = MAX(b + 5) BY b | INLINE STATS s1 = COUNT(b) BY s1
        // After the second INLINE STATS, s1 should retain the MAX result (6), not become COUNT (1).
        var schema = new SimSchema("test_idx", List.of(new SimSchema.SimColumn("b", DataType.INTEGER)));
        var data = List.<Map<String, Object>>of(Map.of("b", 1));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var bAttr = from.output().get(0); // b:INTEGER

        // First INLINE STATS: s1 = MAX(b + 5) BY b → s1=6
        var add5 = new Add(Source.EMPTY, bAttr, new Literal(Source.EMPTY, 5, DataType.INTEGER), EsqlTestUtils.TEST_CFG);
        var agg1 = new Aggregate(
            Source.EMPTY,
            from,
            List.<Expression>of(bAttr),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "s1", new Max(Source.EMPTY, add5)), bAttr)
        );
        var inline1 = new InlineStats(Source.EMPTY, agg1);

        // After inline1, result should be: [b=[1], s1=[6]]
        var sim = new Simulator(schema, data);
        Simulator.Result result1 = sim.simulate(inline1);
        assertThat(result1.columns().size(), equalTo(2));
        assertThat(result1.getColumn("b").values(), equalTo(List.of(1L)));
        assertThat(result1.getColumn("s1").values(), equalTo(List.of(6L)));

        // Second INLINE STATS: s1 = COUNT(b) BY s1
        // aggregates: [Alias("s1", COUNT(b)), s1Attr_from_inline1_output]
        // The grouping key s1 has value 6; COUNT(b) = 1.
        // ES keeps the grouping key value (s1=6), not the COUNT value (s1=1).
        Simulator.Column s1Column = result1.columns().stream().filter(c -> c.name().equals("s1")).findFirst().orElseThrow();
        var s1Ref = new ReferenceAttribute(Source.EMPTY, "s1", s1Column.type());
        var bRef = new ReferenceAttribute(Source.EMPTY, "b", DataType.INTEGER);
        var agg2 = new Aggregate(
            Source.EMPTY,
            inline1,
            List.<Expression>of(s1Ref),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "s1", new Count(Source.EMPTY, bRef)), s1Ref)
        );
        var inline2 = new InlineStats(Source.EMPTY, agg2);

        Simulator.Result result2 = sim.simulate(inline2);
        // s1 should be 6 (grouping key value), not 1 (COUNT value)
        assertThat(result2.getColumn("s1").values(), equalTo(List.of(6L)));
        assertThat(result2.getColumn("b").values(), equalTo(List.of(1L)));
    }

    public void testNullInArithmetic() throws Exception {
        // Row 0: a=1, b=2 -> z = 3. Row 1: a absent (null), b=3 -> z = null.
        var schema = new SimSchema(
            "test_idx",
            List.of(new SimSchema.SimColumn("a", DataType.INTEGER), new SimSchema.SimColumn("b", DataType.INTEGER))
        );
        var data = List.<Map<String, Object>>of(Map.of("a", 1, "b", 2), Map.of("b", 3));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var aAttr = from.output().get(0);
        var bAttr = from.output().get(1);
        var add = new Add(Source.EMPTY, aAttr, bAttr, EsqlTestUtils.TEST_CFG);
        var eval = new Eval(Source.EMPTY, from, List.of(new Alias(Source.EMPTY, "z", add)));
        Simulator.Result result = new Simulator(schema, data).simulate(eval);
        Simulator.Column zCol = result.getColumn("z");
        assertThat(zCol.values().get(0), equalTo(3L));
        assertNull(zCol.values().get(1));
    }

    public void testNullInFilter() throws Exception {
        // WHERE a > 5: null a -> filtered out; a=3 -> filtered out; a=10 -> kept.
        var schema = new SimSchema(
            "test_idx",
            List.of(new SimSchema.SimColumn("a", DataType.INTEGER), new SimSchema.SimColumn("b", DataType.KEYWORD))
        );
        var data = List.<Map<String, Object>>of(Map.of("a", 10, "b", "x"), Map.of("b", "y"), Map.of("a", 3, "b", "z"));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var aAttr = from.output().get(0);
        var gt = new GreaterThan(Source.EMPTY, aAttr, new Literal(Source.EMPTY, 5, DataType.INTEGER));
        var filter = new Filter(Source.EMPTY, from, gt);
        Simulator.Result result = new Simulator(schema, data).simulate(filter);
        assertThat(result.numRows(), equalTo(1));
        assertThat(result.getColumn("a").values().get(0), equalTo(10L));
    }

    public void testNullInStatsCount() throws Exception {
        // COUNT(a) grouped by b: group "x" has rows (a=1) and (a=null) -> COUNT=1; group "y" has (a=3) -> COUNT=1.
        var schema = new SimSchema(
            "test_idx",
            List.of(new SimSchema.SimColumn("a", DataType.INTEGER), new SimSchema.SimColumn("b", DataType.KEYWORD))
        );
        var data = List.<Map<String, Object>>of(Map.of("a", 1, "b", "x"), Map.of("b", "x"), Map.of("a", 3, "b", "y"));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var aAttr = from.output().get(0);
        var bAttr = from.output().get(1);
        var aggregate = new Aggregate(
            Source.EMPTY,
            from,
            List.<Expression>of(bAttr),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "cnt", new Count(Source.EMPTY, aAttr)), bAttr)
        );
        Simulator.Result result = new Simulator(schema, data).simulate(aggregate);
        assertThat(result.getColumn("cnt").values(), equalTo(List.of(1L, 1L)));
    }

    public void testNullInStatsSum() throws Exception {
        // SUM(a) grouped by b: group "x" has (a=1) and (a=null) -> SUM=1; group "y" has (a=null) only -> SUM=null.
        var schema = new SimSchema(
            "test_idx",
            List.of(new SimSchema.SimColumn("a", DataType.INTEGER), new SimSchema.SimColumn("b", DataType.KEYWORD))
        );
        var data = List.<Map<String, Object>>of(Map.of("a", 1, "b", "x"), Map.of("b", "x"), Map.of("b", "y"));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var aAttr = from.output().get(0);
        var bAttr = from.output().get(1);
        var aggregate = new Aggregate(
            Source.EMPTY,
            from,
            List.<Expression>of(bAttr),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "s", new Sum(Source.EMPTY, aAttr)), bAttr)
        );
        Simulator.Result result = new Simulator(schema, data).simulate(aggregate);
        List<Object> sumValues = result.getColumn("s").values();
        List<Object> bValues = result.getColumn("b").values();
        int xIdx = bValues.indexOf("x");
        int yIdx = bValues.indexOf("y");
        assertThat(sumValues.get(xIdx), equalTo(1L));
        assertNull(sumValues.get(yIdx));
    }

    public void testNullInGroupingKey() throws Exception {
        // SUM(a) grouped by b: group "x" has (a=1) -> SUM=1; group null has (a=2) and (a=3) -> SUM=5.
        var schema = new SimSchema(
            "test_idx",
            List.of(new SimSchema.SimColumn("a", DataType.INTEGER), new SimSchema.SimColumn("b", DataType.KEYWORD))
        );
        var data = List.<Map<String, Object>>of(Map.of("a", 1, "b", "x"), Map.of("a", 2), Map.of("a", 3));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var aAttr = from.output().get(0);
        var bAttr = from.output().get(1);
        var aggregate = new Aggregate(
            Source.EMPTY,
            from,
            List.<Expression>of(bAttr),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "s", new Sum(Source.EMPTY, aAttr)), bAttr)
        );
        Simulator.Result result = new Simulator(schema, data).simulate(aggregate);
        List<Object> sumValues = result.getColumn("s").values();
        List<Object> bValues = result.getColumn("b").values();
        assertThat(sumValues.size(), equalTo(2));
        // Find "x" group and null group (order may vary)
        int xIdx = bValues.indexOf("x");
        int nullIdx = xIdx == 0 ? 1 : 0;
        assertThat(sumValues.get(xIdx), equalTo(1L));
        assertThat(sumValues.get(nullIdx), equalTo(5L));
        assertNull(bValues.get(nullIdx));
    }

    public void testAllNullsInColumn() throws Exception {
        // All rows have null a -> COUNT(a)=0, SUM(a)=null.
        var schema = new SimSchema(
            "test_idx",
            List.of(new SimSchema.SimColumn("a", DataType.INTEGER), new SimSchema.SimColumn("b", DataType.INTEGER))
        );
        var data = List.<Map<String, Object>>of(Map.of("b", 1), Map.of("b", 2));
        var from = LogicalPlanGenerator.buildEsRelation(schema);
        var aAttr = from.output().get(0);
        var aggregateCount = new Aggregate(
            Source.EMPTY,
            from,
            List.of(),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "cnt", new Count(Source.EMPTY, aAttr)))
        );
        Simulator.Result countResult = new Simulator(schema, data).simulate(aggregateCount);
        assertThat(countResult.getColumn("cnt").values().get(0), equalTo(0L));

        var aggregateSum = new Aggregate(
            Source.EMPTY,
            from,
            List.of(),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "s", new Sum(Source.EMPTY, aAttr)))
        );
        Simulator.Result sumResult = new Simulator(schema, data).simulate(aggregateSum);
        assertNull(sumResult.getColumn("s").values().get(0));
    }

    public void testBuildRowPrints() {
        // KEYWORD literals require BytesRef (not String) per Literal's assertion.
        Row row = new Row(
            Source.EMPTY,
            List.of(
                new Alias(Source.EMPTY, "x", new Literal(Source.EMPTY, 5, DataType.INTEGER)),
                new Alias(Source.EMPTY, "name", new Literal(Source.EMPTY, new BytesRef("bar"), DataType.KEYWORD))
            )
        );
        String printed = LogicalPlanPrinter.print(row);
        assertTrue(printed.startsWith("ROW "));
        assertTrue(printed.contains("x = "));
        assertTrue(printed.contains("name = \""));
    }

    public void testRowWithStatsNoGrouping() throws Exception {
        Simulator.Result result = simulate("ROW x = 5 | STATS s0 = COUNT(x)");
        assertThat(result.getColumn("s0").values().get(0), equalTo(1L));
    }

    public void testRowWithWhereFiltersOut() throws Exception {
        Simulator.Result result = simulate("ROW x = 1 | WHERE x > 5");
        assertThat(result.numRows(), equalTo(0));
    }

    public void testRowWithWhereKeeps() throws Exception {
        Simulator.Result result = simulate("ROW x = 10 | WHERE x > 5");
        assertThat(result.numRows(), equalTo(1));
        assertThat(result.getColumn("x").values().get(0), equalTo(10));
    }

    public void testRowKeywordOnly() throws Exception {
        // The parser stores keyword literals as BytesRef; visit(Row) preserves them as-is.
        Simulator.Result result = simulate("ROW name = \"hello\", tag = \"world\"");
        assertThat(result.columns().size(), equalTo(2));
        assertThat(result.getColumn("name").type(), equalTo(DataType.KEYWORD));
        assertThat(result.getColumn("tag").type(), equalTo(DataType.KEYWORD));
        assertThat(result.getColumn("name").values().get(0), equalTo(new BytesRef("hello")));
        assertThat(result.getColumn("tag").values().get(0), equalTo(new BytesRef("world")));
    }

    public void testRowWithKeep() throws Exception {
        Simulator.Result result = simulate("ROW x = 1, y = 2, z = 3 | KEEP x, z");
        assertThat(result.columns().size(), equalTo(2));
        assertThat(result.getColumn("x").values().get(0), equalTo(1));
        assertThat(result.getColumn("z").values().get(0), equalTo(3));
    }

    public void testRowWithStatsGrouped() throws Exception {
        Simulator.Result result = simulate("ROW x = 5, y = 3 | STATS s0 = SUM(x) BY y");
        assertThat(result.numRows(), equalTo(1));
        assertThat(result.getColumn("s0").values().get(0), equalTo(5L));
        assertThat(result.getColumn("y").values().get(0), equalTo(3));
    }

    private Simulator.Result simulate(String statement) throws IOException {
        return simulator.simulate(parser.createStatement(statement).plan());
    }
}
