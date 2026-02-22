/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import org.elasticsearch.xpack.esql.core.type.DataType;

import java.util.List;

record SimSchema(String indexName, List<SimColumn> columns) {
    record SimColumn(String name, DataType type) {
        @Override
        public String toString() {
            return name + ":" + type.typeName();
        }
    }

    @Override
    public String toString() {
        return indexName + "(" + String.join(", ", columns.stream().map(SimColumn::toString).toList()) + ")";
    }
}
