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

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates random row data conforming to a {@link SimSchema}.
 * Each row is a map from column name to a value matching the column's {@link org.elasticsearch.xpack.esql.core.type.DataType}.
 */
class SimDataGenerator {
    private SimDataGenerator() { /* static class */ }

    private static final List<String> KEYWORD_POOL = List.of("foo", "bar", "baz");

    static Arbitrary<List<Map<String, Object>>> rows(SimSchema schema) {
        return arbitraryRow(schema).list().ofMinSize(1).ofMaxSize(5);
    }

    private static Arbitrary<Map<String, Object>> arbitraryRow(SimSchema schema) {
        List<SimSchema.SimColumn> columns = schema.columns();
        if (columns.size() == 1) {
            return arbitraryValue(columns.get(0).type()).map(v -> Map.of(columns.get(0).name(), v));
        }

        List<Arbitrary<Object>> valueArbitraries = columns.stream().map(col -> arbitraryValue(col.type())).toList();
        return Combinators.combine(valueArbitraries)
            .as(values -> IntStream.range(0, columns.size()).boxed().collect(Collectors.toMap(i -> columns.get(i).name(), values::get)));
    }

    private static Arbitrary<Object> arbitraryValue(DataType type) {
        return switch (type) {
            case INTEGER -> Arbitraries.integers().between(1, 10).map(i -> (Object) i);
            case KEYWORD -> Arbitraries.of(KEYWORD_POOL).map(s -> (Object) s);
            default -> throw new UnsupportedOperationException("Unsupported data type for data generation: " + type);
        };
    }
}
