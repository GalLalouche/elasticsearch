/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

import org.elasticsearch.index.IndexMode;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.expression.UnresolvedAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.plan.IndexPattern;
import org.elasticsearch.xpack.esql.plan.logical.Drop;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.UnresolvedRelation;

import java.util.List;

public class LogicalPlanGenerator {

    private static final List<String> EVAL_ALIAS_POOL = List.of("z", "w", "v", "col_0", "col_1");

    public static Arbitrary<LogicalPlan> plansFor(SimSchema schema) {
        List<String> allColumns = schema.columnNames();
        List<String> integerColumns = schema.integerColumns().stream().map(SimSchema.SimColumn::name).toList();

        return Arbitraries.recursive(() -> arbitrarySource(schema), child -> wrap(child, allColumns, integerColumns, schema), 1);
    }

    private static Arbitrary<LogicalPlan> arbitrarySource(SimSchema schema) {
        return Arbitraries.just(
            new UnresolvedRelation(
                Source.EMPTY,
                new IndexPattern(Source.EMPTY, schema.indexName()),
                false,
                List.of(),
                IndexMode.STANDARD,
                null
            )
        );
    }

    private static Arbitrary<LogicalPlan> wrap(
        Arbitrary<LogicalPlan> child,
        List<String> allColumns,
        List<String> integerColumns,
        SimSchema schema
    ) {
        var columns = arbitraryNonEmptySubset(allColumns);
        if (integerColumns.isEmpty()) {
            // No integer columns → can't generate EVAL with arithmetic
            return Arbitraries.oneOf(wrapKeep(child, columns), wrapDrop(child, columns));
        }
        return Arbitraries.oneOf(wrapKeep(child, columns), wrapDrop(child, columns), wrapEval(child, integerColumns, schema));
    }

    private static Arbitrary<List<String>> arbitraryNonEmptySubset(List<String> pool) {
        return Arbitraries.of(pool).set().ofMinSize(1).ofMaxSize(pool.size()).map(set -> List.copyOf(set));
    }

    private static Arbitrary<LogicalPlan> wrapKeep(Arbitrary<LogicalPlan> child, Arbitrary<List<String>> columns) {
        return Combinators.combine(child, columns).as((plan, cols) -> {
            var attrs = cols.stream().map(name -> (NamedExpression) new UnresolvedAttribute(Source.EMPTY, name)).toList();
            return new Keep(Source.EMPTY, plan, attrs);
        });
    }

    private static Arbitrary<LogicalPlan> wrapDrop(Arbitrary<LogicalPlan> child, Arbitrary<List<String>> columns) {
        return Combinators.combine(child, columns).as((plan, cols) -> {
            var attrs = cols.stream().map(name -> (NamedExpression) new UnresolvedAttribute(Source.EMPTY, name)).toList();
            return new Drop(Source.EMPTY, plan, attrs);
        });
    }

    private static Arbitrary<LogicalPlan> wrapEval(Arbitrary<LogicalPlan> child, List<String> integerColumns, SimSchema schema) {
        return Combinators.combine(child, arbitraryAlias(integerColumns, schema))
            .as((plan, alias) -> new Eval(Source.EMPTY, plan, List.of(alias)));
    }

    private static Arbitrary<Alias> arbitraryAlias(List<String> integerColumns, SimSchema schema) {
        // Pick an alias name that doesn't collide with existing schema columns
        List<String> existingNames = schema.columnNames();
        List<String> availableAliases = EVAL_ALIAS_POOL.stream().filter(n -> existingNames.contains(n) == false).toList();
        if (availableAliases.isEmpty()) {
            availableAliases = List.of("_col_0", "_col_1");
        }
        return Combinators.combine(Arbitraries.of(availableAliases), arbitraryExpression(integerColumns))
            .as((name, expr) -> new Alias(Source.EMPTY, name, expr));
    }

    private static Arbitrary<Expression> arbitraryExpression(List<String> integerColumns) {
        var leaf = arbitraryLeaf(integerColumns);
        var pair = Combinators.combine(leaf, leaf).as((left, right) -> new Expression[] { left, right });
        return Arbitraries.oneOf(
            pair.map(p -> new Add(Source.EMPTY, p[0], p[1], EsqlTestUtils.TEST_CFG)),
            pair.map(p -> new Sub(Source.EMPTY, p[0], p[1], EsqlTestUtils.TEST_CFG)),
            pair.map(p -> new Mul(Source.EMPTY, p[0], p[1]))
        );
    }

    private static Arbitrary<Expression> arbitraryLeaf(List<String> integerColumns) {
        return Arbitraries.oneOf(
            Arbitraries.of(integerColumns).map(name -> new UnresolvedAttribute(Source.EMPTY, name)),
            Arbitraries.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).map(i -> new Literal(Source.EMPTY, i, DataType.INTEGER))
        );
    }
}
