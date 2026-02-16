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

import org.elasticsearch.xpack.esql.core.type.DataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class SimDataGenerator {

    private static final List<String> KEYWORD_POOL = List.of("foo", "bar", "baz");

    static Arbitrary<List<Map<String, Object>>> rows(SimSchema schema) {
        Arbitrary<Map<String, Object>> singleRow = arbitraryRow(schema);
        return singleRow.list().ofMinSize(1).ofMaxSize(5);
    }

    private static Arbitrary<Map<String, Object>> arbitraryRow(SimSchema schema) {
        List<SimSchema.SimColumn> columns = schema.columns();
        if (columns.size() == 1) {
            return arbitraryValue(columns.get(0).type()).map(v -> Map.of(columns.get(0).name(), v));
        }

        // Build list of value arbitraries, one per column
        List<Arbitrary<Object>> valueArbitraries = columns.stream().map(col -> arbitraryValue(col.type())).toList();

        // Combine all value arbitraries into a single row
        return Combinators.combine(valueArbitraries).as(values -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                row.put(columns.get(i).name(), values.get(i));
            }
            return row;
        });
    }

    private static Arbitrary<Object> arbitraryValue(DataType type) {
        return switch (type) {
            case INTEGER -> Arbitraries.integers().between(1, 10).map(i -> (Object) i);
            case KEYWORD -> Arbitraries.of(KEYWORD_POOL).map(s -> (Object) s);
            default -> throw new UnsupportedOperationException("Unsupported data type for data generation: " + type);
        };
    }
}
