/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import net.jqwik.api.Arbitrary;

import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared utilities for simulator jqwik tests.
 */
class SimulatorTestUtils {
    private SimulatorTestUtils() { /* static class */ }

    /**
     * Initialize ES logging and break the IndexSettings/IndexMode circular class init dependency.
     * Must be called from a {@code static {}} block in every jqwik test class (which runs outside the ES test framework).
     */
    static void initLogging() {
        LogConfigurator.configureESLogging();
        // Force IndexSettings to initialize before IndexMode to break circular class init dependency.
        var unused = IndexSettings.MODE;
    }

    /** Schemas that contain at least one INTEGER column (needed by most generators). */
    static Arbitrary<SimSchema> schemasWithInteger() {
        return SimSchemaGenerator.schemas().filter(s -> s.columns().stream().anyMatch(c -> c.type() == DataType.INTEGER));
    }

    /** Collect attribute references from a plan node's own expressions (not children). */
    static List<Attribute> collectAttributeReferences(LogicalPlan plan) {
        return switch (plan) {
            case Keep keep -> keep.projections().stream().filter(ne -> ne instanceof Attribute).map(ne -> (Attribute) ne).toList();
            case Filter filter -> collectExprAttrs(filter.condition());
            case Eval eval -> eval.fields().stream().flatMap(a -> collectExprAttrs(a.child()).stream()).toList();
            case OrderBy ob -> ob.order().stream().flatMap(o -> collectExprAttrs(o.child()).stream()).toList();
            default -> List.of();
        };
    }

    /** Recursively collect all {@link Attribute} references from an expression tree. */
    static List<Attribute> collectExprAttrs(Expression expr) {
        if (expr instanceof Attribute a) return List.of(a);
        if (expr instanceof Literal) return List.of();
        var result = new ArrayList<Attribute>();
        for (Expression child : expr.children()) {
            result.addAll(collectExprAttrs(child));
        }
        return result;
    }
}
