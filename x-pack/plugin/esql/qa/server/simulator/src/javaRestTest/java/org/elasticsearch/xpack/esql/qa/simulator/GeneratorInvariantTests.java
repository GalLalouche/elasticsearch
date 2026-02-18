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
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.parser.EsqlParser;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.UnaryPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.esql.qa.simulator.ShrinkingValidityTests.collectAttributeReferences;
import static org.elasticsearch.xpack.esql.qa.simulator.ShrinkingValidityTests.collectExprAttrs;

/**
 * Structural invariant tests for {@link LogicalPlanGenerator}.
 * Verifies that generated plans are well-formed without requiring a running cluster.
 */
public class GeneratorInvariantTests {
    static {
        LogConfigurator.configureESLogging();
        var unused = IndexSettings.MODE;
    }

    // --- Providers ---

    @Provide
    Arbitrary<LogicalPlan> resolvedPlans() {
        return schemasWithInteger().flatMap(schema -> LogicalPlanGenerator.plansFor(schema, 5));
    }

    @Provide
    Arbitrary<LogicalPlan> rawPlans() {
        return schemasWithInteger().flatMap(schema -> LogicalPlanGenerator.rawPlansFor(schema, 5));
    }

    private static Arbitrary<SimSchema> schemasWithInteger() {
        return SimSchemaGenerator.schemas().filter(s -> s.columns().stream().anyMatch(c -> c.type() == DataType.INTEGER));
    }

    // --- Invariant 1: All column references exist in child output ---

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
     * Extends {@link ShrinkingValidityTests#collectAttributeReferences} to also cover {@link Aggregate}.
     * {@link InlineStats} is skipped because its child() is the inner Aggregate, which is checked separately.
     */
    static List<Attribute> collectAllAttributeReferences(LogicalPlan plan) {
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

    // --- Invariant 2: resolveReferences is idempotent ---

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

    // --- Invariant 3: Every plan prints to parseable ES|QL ---

    @Property(tries = 1000)
    void everyPlanPrintsToParseableEsql(@ForAll("resolvedPlans") LogicalPlan plan) {
        var query = LogicalPlanPrinter.print(plan);
        try {
            EsqlParser.INSTANCE.createStatement(query);
        } catch (Exception e) {
            throw new AssertionError("Failed to parse printed query: " + query, e);
        }
    }

    // --- Invariant 4: No plan node has zero output columns ---

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
