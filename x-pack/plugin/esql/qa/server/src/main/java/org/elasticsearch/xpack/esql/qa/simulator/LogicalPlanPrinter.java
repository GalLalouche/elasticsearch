/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import org.elasticsearch.common.Strings;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.ArithmeticOperation;
import org.elasticsearch.xpack.esql.plan.logical.Drop;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.UnresolvedRelation;

/**
 * Converts a {@link LogicalPlan} tree back into an ES|QL query string.
 * Used to send generated plans to Elasticsearch for comparison with the simulator.
 */
public class LogicalPlanPrinter {
    private LogicalPlanPrinter() { /* static class */ }

    public static String print(LogicalPlan plan) {
        StringBuilder sb = new StringBuilder();
        printCommon(plan, sb);
        return sb.toString();
    }

    private static void printCommon(LogicalPlan plan, StringBuilder sb) {
        switch (plan) {
            case UnresolvedRelation relation -> printSpecific(relation, sb);
            case EsRelation relation -> printSpecific(relation, sb);
            case Keep keep -> printSpecific(keep, sb);
            case Drop drop -> printSpecific(drop, sb);
            case Eval eval -> printSpecific(eval, sb);
            default -> throw new UnsupportedOperationException(
                Strings.format("Printing of plan [%s] is not supported yet", plan.getClass())
            );
        }
    }

    private static void printSpecific(UnresolvedRelation relation, StringBuilder sb) {
        sb.append("FROM ").append(relation.indexPattern().indexPattern());
    }

    private static void printSpecific(EsRelation relation, StringBuilder sb) {
        sb.append("FROM ").append(relation.indexPattern());
    }

    private static void printSpecific(Keep keep, StringBuilder sb) {
        printCommon(keep.child(), sb);
        sb.append(" | KEEP ");
        var projections = keep.projections();
        for (int i = 0; i < projections.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(projections.get(i).name());
        }
    }

    private static void printSpecific(Drop drop, StringBuilder sb) {
        printCommon(drop.child(), sb);
        sb.append(" | DROP ");
        var removals = drop.removals();
        for (int i = 0; i < removals.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(removals.get(i).name());
        }
    }

    private static void printSpecific(Eval eval, StringBuilder sb) {
        printCommon(eval.child(), sb);
        sb.append(" | EVAL ");
        for (int i = 0; i < eval.expressions().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(printExpression(eval.expressions().get(i)));
        }
    }

    static String printExpression(Expression expression) {
        return switch (expression) {
            case Alias alias -> Strings.format("%s = %s", alias.name(), printExpression(alias.child()));
            case Attribute attr -> attr.name();
            case Literal literal -> literal.value() instanceof String s ? Strings.format("\"%s\"", s) : literal.value().toString();
            case ArithmeticOperation op -> Strings.format("%s %s %s", printExpression(op.left()), op.symbol(), printExpression(op.right()));
            default -> throw new UnsupportedOperationException(
                Strings.format("Printing of expression [%s] is not supported yet", expression.getClass())
            );
        };
    }
}
