/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.logical;

import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Insist;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;

/// Push down INSIST data to child relation, by simply replacing the child's output with the INSIST output. This simplifies the tree as
/// there's one less node to worry about. This is done as a rewrite rather at plan creation so the initial tree would still resemble the
/// original user query.
public final class PushdownInsists extends OptimizerRules.OptimizerRule<Insist> {
    @Override
    protected LogicalPlan rule(Insist insist) {
        return ((EsRelation) insist.child()).withAttributes(insist.output());
    }
}
