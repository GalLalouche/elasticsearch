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

/**
 * Generates random {@link SimSchema} instances: an index name plus a variable number of columns
 * with names and types drawn from fixed pools.
 */
class SimSchemaGenerator {
    private static final List<String> COLUMN_NAME_POOL = List.of("a", "b", "c", "d", "x", "y");
    private static final List<DataType> TYPE_POOL = List.of(DataType.INTEGER, DataType.KEYWORD);

    static Arbitrary<SimSchema> schemas() {
        var indexName = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(6).map(s -> "sim_" + s.toLowerCase());
        Arbitrary<SimSchema.SimColumn> column = Combinators.combine(Arbitraries.of(COLUMN_NAME_POOL), Arbitraries.of(TYPE_POOL))
            .as(SimSchema.SimColumn::new);

        return Combinators.combine(indexName, column.list().ofMinSize(1).ofMaxSize(4).uniqueElements(SimSchema.SimColumn::name))
            .as((name, cols) -> new SimSchema(name, List.copyOf(cols)));
    }
}
