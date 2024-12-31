/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.logical;

import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.InsistedAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.scalar.convert.AbstractConvertFunction;
import org.elasticsearch.xpack.esql.plan.InsistParameters;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Insist;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.type.EsqlDataTypeConverter;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/// Desugars INSIST clauses into either a cast or removes them entirely.
///
/// INSIST clauses can be desugared, based on three cases:
/// 1. If the requested column exists and is of the correct type, the INSIST clause is redundant and can be removed.
/// 2. If the requested column exists but is of the wrong type, the INSIST clause can be replaced with a CAST clause.
/// 3. If the requested column does not exist, we update the underlying relation to "pretend" it exists as a `KEYWORD` (since we can read
/// any value as a `KEYWORD`), and add a CAST on top of it if needed. This isn't the most efficient way to handle this case, but it has the
/// benefit of completely removing insists, and since we're dealing with source anyway, our performance is gonna be wrecked anyway. We may
/// we wish to revisit this implementation in the future.
public final class DesugarInsist extends OptimizerRules.OptimizerRule<Insist> {
    @Override
    protected LogicalPlan rule(Insist plan) {
        // Column exists and is of the same type, just replace this node with its child.
        if (plan.isRedundant()) {
            return plan.child();
        }

        InsistParameters params = plan.parameters();
        if (lookupAttribute(plan.child(), params).orElse(null) instanceof Attribute attr) {
            // Column exists but is different, so just add a cast.
            assert params.dataType() != attr.dataType();
            return addCast(plan.child(), plan);
        }

        // Column does not exist, need to patch up the relation in addition to maybe adding a cast.
        var newRelation = updateEsRelation(plan);
        return params.dataType() != DEFAULT_ADDED_TYPE ? addCast(newRelation, plan) : newRelation;
    }

    private static EsRelation updateEsRelation(Insist plan) {
        var newAttribute = new InsistedAttribute(plan.source(), plan.parameters().identifier(), DEFAULT_ADDED_TYPE);
        EsRelation oldRelation = (EsRelation) plan.child();
        return oldRelation.withAttributes(Stream.concat(oldRelation.output().stream(), Stream.of(newAttribute)).toList());
    }

    private static Eval addCast(LogicalPlan child, Insist insist) {
        var params = insist.parameters();
        // Since we're replacing the INSIST output with an eval, we need to maintain the same ID, otherwise the plan verifier will throw
        // a hissy fit about the IDs not matching.
        var insistedId = insist.insistedId();
        var attr = lookupAttribute(child, params).get();
        AbstractConvertFunction conversion = EsqlDataTypeConverter.converterFunctionFactory(params.dataType()).apply(Source.EMPTY, attr);
        return new Eval(Source.EMPTY, child, List.of(new Alias(Source.EMPTY, params.identifier(), conversion, insistedId)));
    }

    private static Optional<Attribute> lookupAttribute(LogicalPlan node, InsistParameters parameters) {
        return node.output().stream().filter(c -> c.name().equals(parameters.identifier())).findFirst();
    }

    private static final DataType DEFAULT_ADDED_TYPE = DataType.KEYWORD;
}
