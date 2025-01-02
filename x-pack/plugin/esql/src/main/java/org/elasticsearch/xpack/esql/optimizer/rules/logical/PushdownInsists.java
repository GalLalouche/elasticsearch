/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.logical;

import org.elasticsearch.xpack.esql.core.expression.InsistedAttribute;
import org.elasticsearch.xpack.esql.core.util.CollectionUtils;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Insist;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;

import java.util.ArrayList;

/// Push down INSIST data to child relation, either adding a new column or modifying an existing one. Insisted attributes are represented
/// using, well, [[InsistedAttribute]]. Note that due to the way we represent multi-index queries, we cannot determine at this phase whether
/// a column is mapped, as it might be mapped in one index, but not in another, so we handle unmapped fields during the block loading
/// phase.
public final class PushdownInsists extends OptimizerRules.OptimizerRule<Insist> {
    @Override
    protected LogicalPlan rule(Insist plan) {
        EsRelation child = (EsRelation) plan.child();
        var result = new ArrayList<>(child.output());
        InsistedAttribute newAttribute = new InsistedAttribute(plan.source(), plan.parameters().identifier(), plan.parameters().dataType());
        CollectionUtils.findIndex(child.output(), c1 -> c1.name().equals(plan.parameters().identifier()))
            .ifPresentOrElse(i -> result.set(i, newAttribute), () -> result.add(newAttribute));
        return child.withAttributes(result);
    }
}
