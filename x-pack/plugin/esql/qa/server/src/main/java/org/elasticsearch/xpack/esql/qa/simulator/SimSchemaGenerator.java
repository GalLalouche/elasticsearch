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

class SimSchemaGenerator {

    private static final List<String> COLUMN_NAME_POOL = List.of("a", "b", "c", "d", "x", "y");
    private static final List<DataType> TYPE_POOL = List.of(DataType.INTEGER, DataType.KEYWORD);

    static Arbitrary<SimSchema> schemas() {
        var indexName = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(6).map(s -> "sim_" + s.toLowerCase());
        var columnNames = Arbitraries.of(COLUMN_NAME_POOL).set().ofMinSize(1).ofMaxSize(4);
        var types = Arbitraries.of(TYPE_POOL);

        return Combinators.combine(indexName, columnNames).flatAs((name, names) -> {
            List<String> nameList = List.copyOf(names);
            // Generate a type for each column name
            return Arbitraries.of(TYPE_POOL).list().ofSize(nameList.size()).map(typeList -> {
                var columns = new java.util.ArrayList<SimSchema.SimColumn>(nameList.size());
                for (int i = 0; i < nameList.size(); i++) {
                    columns.add(new SimSchema.SimColumn(nameList.get(i), typeList.get(i)));
                }
                return new SimSchema(name, List.copyOf(columns));
            });
        });
    }
}
