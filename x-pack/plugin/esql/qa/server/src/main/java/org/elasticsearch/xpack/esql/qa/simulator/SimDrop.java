/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.plan.logical.Drop;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A Drop variant that computes {@link #output()} by subtracting removals from the child's output.
 * The standard {@link Drop} inherits {@code child().output()} unchanged (it's an unresolved plan node
 * that relies on the analyzer to compute output). This resolved variant lets the generator call
 * {@code plan.output()} to discover available columns without manual state tracking.
 */
class SimDrop extends Drop {

    SimDrop(Source source, LogicalPlan child, List<NamedExpression> removals) {
        super(source, child, removals);
    }

    @Override
    public List<Attribute> output() {
        Set<String> dropNames = removals().stream().map(NamedExpression::name).collect(Collectors.toSet());
        return child().output().stream().filter(a -> dropNames.contains(a.name()) == false).toList();
    }
}
