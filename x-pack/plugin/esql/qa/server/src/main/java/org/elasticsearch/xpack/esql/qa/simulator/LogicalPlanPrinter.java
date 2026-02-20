/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.Strings;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.expression.Order;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Count;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Max;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Min;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Sum;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.ArithmeticOperation;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.EsqlBinaryComparison;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.Drop;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.Limit;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;
import org.elasticsearch.xpack.esql.plan.logical.Row;
import org.elasticsearch.xpack.esql.plan.logical.UnresolvedRelation;

import static java.util.stream.Collectors.joining;

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
            case Row row -> printSpecific(row, sb);
            case Keep keep -> printSpecific(keep, sb);
            case Drop drop -> printSpecific(drop, sb);
            case Eval eval -> printSpecific(eval, sb);
            case Filter filter -> printSpecific(filter, sb);
            case InlineStats inlineStats -> printSpecific(inlineStats, sb);
            case Aggregate aggregate -> printSpecific(aggregate, sb);
            case Limit limit -> printSpecific(limit, sb);
            case OrderBy orderBy -> printSpecific(orderBy, sb);
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

    private static void printSpecific(Row row, StringBuilder sb) {
        sb.append("ROW ").append(row.fields().stream().map(LogicalPlanPrinter::printExpression).collect(joining(", ")));
    }

    private static void printSpecific(Keep keep, StringBuilder sb) {
        printCommon(keep.child(), sb);
        sb.append(" | KEEP ").append(keep.projections().stream().map(NamedExpression::name).collect(joining(", ")));
    }

    private static void printSpecific(Drop drop, StringBuilder sb) {
        printCommon(drop.child(), sb);
        sb.append(" | DROP ").append(drop.removals().stream().map(NamedExpression::name).collect(joining(", ")));
    }

    private static void printSpecific(Eval eval, StringBuilder sb) {
        printCommon(eval.child(), sb);
        sb.append(" | EVAL ").append(eval.expressions().stream().map(LogicalPlanPrinter::printExpression).collect(joining(", ")));
    }

    private static void printSpecific(Filter filter, StringBuilder sb) {
        printCommon(filter.child(), sb);
        sb.append(" | WHERE ").append(printExpression(filter.condition()));
    }

    private static void printSpecific(Limit limit, StringBuilder sb) {
        printCommon(limit.child(), sb);
        sb.append(" | LIMIT ").append(((Literal) limit.limit()).value());
    }

    private static void printSpecific(InlineStats inlineStats, StringBuilder sb) {
        printAggregate(inlineStats.aggregate(), "INLINE STATS", sb);
    }

    private static void printSpecific(Aggregate aggregate, StringBuilder sb) {
        printAggregate(aggregate, "STATS", sb);
    }

    private static void printAggregate(Aggregate aggregate, String keyword, StringBuilder sb) {
        printCommon(aggregate.child(), sb);
        // aggregates() contains agg functions followed by grouping key references, so subtract groupings to get only agg functions
        int numAggs = aggregate.aggregates().size() - aggregate.groupings().size();
        sb.append(" | ")
            .append(keyword)
            .append(" ")
            .append(aggregate.aggregates().subList(0, numAggs).stream().map(LogicalPlanPrinter::printExpression).collect(joining(", ")));
        if (aggregate.groupings().isEmpty() == false) {
            sb.append(" BY ").append(aggregate.groupings().stream().map(LogicalPlanPrinter::printExpression).collect(joining(", ")));
        }
    }

    private static void printSpecific(OrderBy orderBy, StringBuilder sb) {
        printCommon(orderBy.child(), sb);
        sb.append(" | SORT ")
            .append(
                orderBy.order()
                    .stream()
                    .map(o -> printExpression(o.child()) + (o.direction() == Order.OrderDirection.ASC ? " ASC" : " DESC"))
                    .collect(joining(", "))
            );
    }

    private static String parenthesizeArithmetic(Expression expr) {
        return expr instanceof ArithmeticOperation ? "(" + printExpression(expr) + ")" : printExpression(expr);
    }

    static String printExpression(Expression expression) {
        return switch (expression) {
            case Alias alias -> Strings.format("%s = %s", alias.name(), printExpression(alias.child()));
            case Attribute attr -> attr.name();
            case Literal literal -> literal.value() instanceof String s ? Strings.format("\"%s\"", s)
                : literal.value() instanceof BytesRef br ? Strings.format("\"%s\"", br.utf8ToString())
                : literal.value().toString();
            case ArithmeticOperation op -> Strings.format(
                "%s %s %s",
                parenthesizeArithmetic(op.left()),
                op.symbol(),
                parenthesizeArithmetic(op.right())
            );
            case EsqlBinaryComparison comp -> Strings.format(
                "%s %s %s",
                printExpression(comp.left()),
                comp.getFunctionType().symbol(),
                printExpression(comp.right())
            );
            case Count c -> Strings.format("COUNT(%s)", printExpression(c.field()));
            case Sum s -> Strings.format("SUM(%s)", printExpression(s.field()));
            case Min m -> Strings.format("MIN(%s)", printExpression(m.field()));
            case Max m -> Strings.format("MAX(%s)", printExpression(m.field()));
            default -> throw new UnsupportedOperationException(
                Strings.format("Printing of expression [%s] is not supported yet", expression.getClass())
            );
        };
    }
}
