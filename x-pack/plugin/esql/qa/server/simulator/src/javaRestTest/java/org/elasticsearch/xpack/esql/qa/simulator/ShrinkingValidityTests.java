/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;
import org.elasticsearch.xpack.esql.plan.logical.UnaryPlan;

import java.util.ArrayList;
import java.util.List;

import static java.util.stream.Collectors.toMap;

/**
 * Regression test: raw plans (without {@link LogicalPlanGenerator#resolveReferences}) must have
 * no stale NameId references. The tuple-wrapping fix in {@code arbitraryAttribute()} prevents
 * jqwik's {@code FlatMappedShrinkable} from silently substituting attributes across re-evaluations.
 */
public class ShrinkingValidityTests {
    static {
        LogConfigurator.configureESLogging();
        var unused = IndexSettings.MODE;
    }

    @Property(tries = 1000)
    void rawPlansHaveNoStaleReferences(@ForAll("rawPlans") LogicalPlan plan) {
        var issues = validateReferences(plan);
        if (issues.isEmpty() == false) {
            throw new AssertionError("Stale NameId in raw plan: " + issues + "\n" + LogicalPlanPrinter.print(plan));
        }
    }

    @Provide
    Arbitrary<LogicalPlan> rawPlans() {
        return schemasWithInteger().flatMap(schema -> LogicalPlanGenerator.rawPlansFor(schema, 5));
    }

    static List<String> validateReferences(LogicalPlan plan) {
        if (plan instanceof EsRelation || plan instanceof UnaryPlan == false) return List.of();
        var unary = (UnaryPlan) plan;
        var canonical = unary.child().output().stream().collect(toMap(Attribute::name, a -> a, (a, b) -> a));
        var issues = new ArrayList<>(validateReferences(unary.child()));
        for (Attribute ref : collectAttributeReferences(plan)) {
            var canon = canonical.get(ref.name());
            if (canon == null) {
                issues.add("Missing column '" + ref.name() + "' in " + plan.getClass().getSimpleName());
            } else if (canon.id().equals(ref.id()) == false) {
                issues.add(
                    "Stale NameId for '"
                        + ref.name()
                        + "' in "
                        + plan.getClass().getSimpleName()
                        + " (expected "
                        + canon.id()
                        + ", got "
                        + ref.id()
                        + ")"
                );
            }
        }
        return issues;
    }

    static List<Attribute> collectAttributeReferences(LogicalPlan plan) {
        return switch (plan) {
            case Keep keep -> keep.projections().stream().filter(ne -> ne instanceof Attribute).map(ne -> (Attribute) ne).toList();
            case Filter filter -> collectExprAttrs(filter.condition());
            case Eval eval -> eval.fields().stream().flatMap(a -> collectExprAttrs(a.child()).stream()).toList();
            case OrderBy ob -> ob.order().stream().flatMap(o -> collectExprAttrs(o.child()).stream()).toList();
            default -> List.of();
        };
    }

    static List<Attribute> collectExprAttrs(Expression expr) {
        if (expr instanceof Attribute a) return List.of(a);
        if (expr instanceof Literal) return List.of();
        var result = new ArrayList<Attribute>();
        for (Expression child : expr.children()) {
            result.addAll(collectExprAttrs(child));
        }
        return result;
    }

    private static Arbitrary<SimSchema> schemasWithInteger() {
        return SimSchemaGenerator.schemas().filter(s -> s.columns().stream().anyMatch(c -> c.type() == DataType.INTEGER));
    }
}
