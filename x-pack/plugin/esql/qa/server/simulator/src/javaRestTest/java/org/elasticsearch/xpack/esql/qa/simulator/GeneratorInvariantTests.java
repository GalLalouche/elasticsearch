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

import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.parser.EsqlParser;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.UnaryPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.esql.qa.simulator.SimulatorTestUtils.collectAttributeReferences;
import static org.elasticsearch.xpack.esql.qa.simulator.SimulatorTestUtils.collectExprAttrs;

/**
 * Structural invariant tests for {@link LogicalPlanGenerator}.
 * Verifies that generated plans are well-formed without requiring a running cluster.
 */
public class GeneratorInvariantTests {
    static {
        SimulatorTestUtils.initLogging();
    }

    @Provide
    private static Arbitrary<LogicalPlan> resolvedPlans() {
        return SimulatorTestUtils.schemasWithInteger().flatMap(schema -> LogicalPlanGenerator.plansFor(schema, 5));
    }

    @Provide
    private static Arbitrary<LogicalPlan> rawPlans() {
        return SimulatorTestUtils.schemasWithInteger().flatMap(schema -> LogicalPlanGenerator.rawPlansFor(schema, 5));
    }

    @Property(tries = 1000)
    void allColumnReferencesExistInChildOutput(@ForAll("resolvedPlans") LogicalPlan plan) {
        var issues = checkColumnReferences(plan);
        if (issues.isEmpty() == false) {
            throw new AssertionError(
                "Column reference to missing column:\n" + String.join("\n", issues) + "\n" + LogicalPlanPrinter.print(plan)
            );
        }
    }

    private static List<String> checkColumnReferences(LogicalPlan plan) {
        var issues = new ArrayList<String>();
        plan.forEachDown(node -> {
            if (node instanceof UnaryPlan unary) {
                Set<String> childColumns = unary.child().output().stream().map(Attribute::name).collect(Collectors.toSet());
                for (Attribute ref : collectAllAttributeReferences(node)) {
                    if (childColumns.contains(ref.name()) == false) {
                        issues.add("Missing column '" + ref.name() + "' in " + node.getClass().getSimpleName());
                    }
                }
            }
        });
        return issues;
    }

    /**
     * Extends {@link SimulatorTestUtils#collectAttributeReferences} to also cover {@link Aggregate}.
     * {@link InlineStats} is skipped because its child() is the inner Aggregate, which is checked separately.
     */
    private static List<Attribute> collectAllAttributeReferences(LogicalPlan plan) {
        return switch (plan) {
            case InlineStats ignored -> List.of();
            case Aggregate agg -> {
                var refs = new ArrayList<Attribute>();
                for (var expr : agg.groupings()) {
                    refs.addAll(collectExprAttrs(expr));
                }
                for (NamedExpression ne : agg.aggregates()) {
                    if (ne instanceof Alias alias) {
                        refs.addAll(collectExprAttrs(alias.child()));
                    } else if (ne instanceof Attribute a) {
                        refs.add(a);
                    }
                }
                yield refs;
            }
            default -> collectAttributeReferences(plan);
        };
    }

    @Property(tries = 1000)
    void resolveReferencesIsIdempotent(@ForAll("rawPlans") LogicalPlan plan) {
        var plan1 = LogicalPlanGenerator.resolveReferences(plan);
        var plan2 = LogicalPlanGenerator.resolveReferences(plan1);
        var printed1 = LogicalPlanPrinter.print(plan1);
        var printed2 = LogicalPlanPrinter.print(plan2);
        if (printed1.equals(printed2) == false) {
            throw new AssertionError("resolveReferences is not idempotent.\nFirst:  " + printed1 + "\nSecond: " + printed2);
        }
    }

    @Property(tries = 1000)
    void everyPlanPrintsToParseableEsql(@ForAll("resolvedPlans") LogicalPlan plan) {
        var query = LogicalPlanPrinter.print(plan);
        try {
            EsqlParser.INSTANCE.createStatement(query);
        } catch (Exception e) {
            throw new AssertionError("Failed to parse printed query: " + query, e);
        }
    }

    @Property(tries = 1000)
    void noPlanNodeHasZeroOutputColumns(@ForAll("resolvedPlans") LogicalPlan plan) {
        plan.forEachDown(node -> {
            if (node.output().isEmpty()) {
                throw new AssertionError(
                    "Plan node " + node.getClass().getSimpleName() + " has zero output columns\n" + LogicalPlanPrinter.print(plan)
                );
            }
        });
    }
}
