/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.logical;

import org.elasticsearch.xpack.esql.plan.logical.Insist;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;

/** Removes redundant insist clauses, i.e., insisting on mapped fields. */
public final class PruneRedundantInsistClauses extends OptimizerRules.OptimizerRule<Insist> {
    @Override
    protected LogicalPlan rule(Insist plan) {
        return plan.isRedundant() ? plan.child() : plan;
    }
}
