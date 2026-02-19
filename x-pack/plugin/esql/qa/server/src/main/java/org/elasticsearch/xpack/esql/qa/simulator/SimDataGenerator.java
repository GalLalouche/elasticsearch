/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import org.elasticsearch.xpack.esql.core.type.DataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates random row data conforming to a {@link SimSchema}.
 * Each row is a map from column name to a value matching the column's {@link org.elasticsearch.xpack.esql.core.type.DataType}.
 */
class SimDataGenerator {
    private SimDataGenerator() { /* static class */ }

    private static final List<String> KEYWORD_POOL = List.of("foo", "bar", "baz");

    static List<Map<String, Object>> generate(SimSchema schema, SourceOfRandomness random, GenerationStatus status) {
        int nRows = random.nextInt(1, 6); // 1–5 inclusive
        List<Map<String, Object>> rows = new ArrayList<>(nRows);
        for (int i = 0; i < nRows; i++) {
            rows.add(generateRow(schema, random));
        }
        return rows;
    }

    private static Map<String, Object> generateRow(SimSchema schema, SourceOfRandomness random) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (SimSchema.SimColumn col : schema.columns()) {
            row.put(col.name(), generateValue(col.type(), random));
        }
        return row;
    }

    private static Object generateValue(DataType type, SourceOfRandomness random) {
        return switch (type) {
            case INTEGER -> random.nextInt(1, 11); // 1–10 inclusive
            case KEYWORD -> random.choose(KEYWORD_POOL);
            default -> throw new UnsupportedOperationException("Unsupported data type for data generation: " + type);
        };
    }
}
