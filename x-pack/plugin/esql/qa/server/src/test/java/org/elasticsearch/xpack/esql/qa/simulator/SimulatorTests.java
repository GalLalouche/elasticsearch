/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import org.elasticsearch.index.IndexMode;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.CsvTestUtils;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Count;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Max;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Min;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Sum;
import org.elasticsearch.xpack.esql.parser.EsqlParser;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;

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
        var aAttr = from.output().get(0); // a:INTEGER
        var bAttr = from.output().get(1); // b:KEYWORD
        var aggregate = new Aggregate(
            Source.EMPTY,
            from,
            List.<Expression>of(bAttr),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "s0", new Sum(Source.EMPTY, aAttr)), bAttr)
        );
        // Groups: "x" → rows 0,1 (a=1,2), "y" → row 2 (a=3)
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
        var aAttr = from.output().get(0); // a:INTEGER
        var bAttr = from.output().get(1); // b:KEYWORD
        var aggregate = new Aggregate(
            Source.EMPTY,
            from,
            List.<Expression>of(bAttr),
            List.<NamedExpression>of(new Alias(Source.EMPTY, "s0", new Sum(Source.EMPTY, aAttr)), bAttr)
        );
        var inlineStats = new InlineStats(Source.EMPTY, aggregate);
        // All 3 rows preserved; s0 broadcast per group. Columns: [a, s0, b]
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
        // All 3 rows preserved; total broadcast to all. Columns: [a, total]
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

    private Simulator.Result simulate(String statement) throws IOException {
        return simulator.simulate(parser.createStatement(statement).plan());
    }
}
