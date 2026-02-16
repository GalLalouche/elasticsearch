/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.CsvTestUtils;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.parser.EsqlParser;

import java.io.IOException;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;

public class SimulatorTests extends ESTestCase {
    private final Simulator simulator = new Simulator();

    EsqlParser parser = EsqlParser.INSTANCE;

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

    private Simulator.Result simulate(String statement) throws IOException {
        return simulator.simulate(parser.createStatement(statement));
    }
}
