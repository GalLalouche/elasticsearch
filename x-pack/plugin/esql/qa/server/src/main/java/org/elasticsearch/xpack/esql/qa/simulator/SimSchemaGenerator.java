/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import org.elasticsearch.xpack.esql.core.type.DataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generates random {@link SimSchema} instances: an index name plus a variable number of columns
 * with names and types drawn from fixed pools.
 */
class SimSchemaGenerator {
    private SimSchemaGenerator() { /* static class */ }

    private static final List<String> COLUMN_NAME_POOL = List.of("a", "b", "c", "d", "x", "y");
    private static final List<DataType> TYPE_POOL = List.of(DataType.INTEGER, DataType.KEYWORD);

    static SimSchema generate(SourceOfRandomness random) {
        String indexName = "sim_" + randomAlpha(random, 3, 6);
        List<String> shuffledNames = new ArrayList<>(COLUMN_NAME_POOL);
        Collections.shuffle(shuffledNames, random.toJDKRandom());
        int nCols = random.nextInt(1, 4);
        List<SimSchema.SimColumn> columns = shuffledNames.stream()
            .limit(nCols)
            .map(name -> new SimSchema.SimColumn(name, random.choose(TYPE_POOL)))
            .toList();
        return new SimSchema(indexName, columns);
    }

    /**
     * Retries {@link #generate} until the schema contains at least one INTEGER column.
     * Terminates quickly since INTEGER is one of two types in the pool.
     */
    static SimSchema generateWithInteger(SourceOfRandomness random) {
        SimSchema schema;
        do {
            schema = generate(random);
        } while (schema.columns().stream().noneMatch(c -> c.type() == DataType.INTEGER));
        return schema;
    }

    private static String randomAlpha(SourceOfRandomness random, int minLen, int maxLen) {
        int len = random.nextInt(minLen, maxLen);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + random.nextInt(0, 'z' - 'a')));
        }
        return sb.toString();
    }
}
