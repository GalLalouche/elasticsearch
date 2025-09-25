/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.simulator;

import org.elasticsearch.common.Strings;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;

class LogicalPlanPrinter {
    private LogicalPlanPrinter() {}

    public static String print(LogicalPlan plan) {
        StringBuilder sb = new StringBuilder();
        printCommon(plan, sb);
        return sb.toString();
    }

    private static void printCommon(LogicalPlan plan, StringBuilder sb) {
        switch (plan) {
            case EsRelation relation -> printSpecific(relation, sb);
            case Eval eval -> printSpecific(eval, sb);
            default -> throw new UnsupportedOperationException(
                Strings.format("Printing of plan [%s] is not supported yet", plan.getClass())
            );
        }
    }

    private static void printSpecific(EsRelation relation, StringBuilder sb) {
        sb.append("FROM ").append(relation.indexPattern());
    }

    private static void printSpecific(Eval eval, StringBuilder sb) {
        printCommon(eval.child(), sb);
        sb.append("\n| EVAL ");
        for (int i = 0; i < eval.expressions().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(eval.expressions().get(i).toString());
        }
    }
}
