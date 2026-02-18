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

import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.UnaryPlan;

import java.util.ArrayList;
import java.util.List;

import static java.util.stream.Collectors.toMap;
import static org.elasticsearch.xpack.esql.qa.simulator.SimulatorTestUtils.collectAttributeReferences;

/**
 * Regression test: raw plans (without {@link LogicalPlanGenerator#resolveReferences}) must have
 * no stale NameId references. The tuple-wrapping fix in {@code arbitraryAttribute()} prevents
 * jqwik's {@code FlatMappedShrinkable} from silently substituting attributes across re-evaluations.
 */
public class ShrinkingValidityTests {
    static {
        SimulatorTestUtils.initLogging();
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
        return SimulatorTestUtils.schemasWithInteger().flatMap(schema -> LogicalPlanGenerator.rawPlansFor(schema, 5));
    }

    static List<String> validateReferences(LogicalPlan plan) {
        if (plan instanceof EsRelation || (plan instanceof UnaryPlan) == false) return List.of();
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
}
