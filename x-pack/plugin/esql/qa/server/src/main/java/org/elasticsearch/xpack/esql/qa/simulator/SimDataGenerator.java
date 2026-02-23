/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.qa.simulator.SimSchema.SimColumn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Generates random row data conforming to a {@link SimSchema}.
 * Each row is a map from column name to a value matching the column's {@link org.elasticsearch.xpack.esql.core.type.DataType}.
 */
class SimDataGenerator {
    private SimDataGenerator() { /* static class */ }

    private static final List<String> KEYWORD_POOL = List.of("foo", "bar", "baz", " Hi ", "HELLO", "world");
    private static final double NULL_PROBABILITY = 0.2;

    static List<Map<String, Object>> generate(SimSchema schema, SourceOfRandomness random) {
        return IntStream.range(0, random.nextInt(1, 5)).mapToObj(i -> generateRow(schema, random)).toList();
    }

    private static Map<String, Object> generateRow(SimSchema schema, SourceOfRandomness random) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (SimColumn col : schema.columns()) {
            if (random.nextDouble() < NULL_PROBABILITY) {
                continue; // absent keys are not serialized in JSON, so ES reads them as null
            }
            row.put(col.name(), generateValue(col.type(), random));
        }
        if (row.isEmpty()) {
            // ES rejects empty documents; any column works — we use the first one arbitrarily.
            SimColumn firstCol = schema.columns().getFirst();
            row.put(firstCol.name(), generateValue(firstCol.type(), random));
        }
        return row;
    }

    private static Object generateValue(DataType type, SourceOfRandomness random) {
        return switch (type) {
            case INTEGER -> random.nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
            case KEYWORD -> random.choose(KEYWORD_POOL);
            default -> throw new UnsupportedOperationException("Unsupported data type for data generation: " + type);
        };
    }
}
