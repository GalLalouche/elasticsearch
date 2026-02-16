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
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.expression.Order;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.Limit;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates random {@link LogicalPlan} trees using jqwik's {@link Arbitraries#recursive} combinator.
 * Plans are built bottom-up: an {@link org.elasticsearch.xpack.esql.plan.logical.EsRelation} base
 * is wrapped in randomly chosen layers (KEEP, DROP, EVAL) up to a configurable depth.
 */
public class LogicalPlanGenerator {
    private static final int DEFAULT_PLAN_DEPTH = 5;
    private static final int PLAN_DEPTH = Integer.getInteger("simulator.planDepth", DEFAULT_PLAN_DEPTH);

    private static final List<String> EVAL_ALIAS_POOL = List.of("z", "w", "v", "col_0", "col_1");

    public static Arbitrary<LogicalPlan> plansFor(SimSchema schema) {
        return plansFor(schema, PLAN_DEPTH);
    }

    public static Arbitrary<LogicalPlan> plansFor(SimSchema schema, int depth) {
        return Arbitraries.recursive(
            () -> Arbitraries.just(buildEsRelation(schema)),
            childArb -> childArb.flatMap(LogicalPlanGenerator::wrapLayer),
            depth
        );
    }

    private static LogicalPlan buildEsRelation(SimSchema schema) {
        List<Attribute> attrs = schema.columns()
            .stream()
            .map(col -> (Attribute) new ReferenceAttribute(Source.EMPTY, col.name(), col.type()))
            .toList();
        return new EsRelation(Source.EMPTY, schema.indexName(), IndexMode.STANDARD, Map.of(), Map.of(), Map.of(), attrs);
    }

    private static Arbitrary<LogicalPlan> wrapLayer(LogicalPlan current) {
        List<Attribute> available = current.output();
        List<Attribute> integerAttrs = available.stream().filter(a -> a.dataType() == DataType.INTEGER).toList();

        var options = new ArrayList<Arbitrary<LogicalPlan>>();
        options.add(Arbitraries.just(current)); // identity — allows shrinking layers away
        options.add(wrapKeep(current, available));
        if (available.size() > 1) {
            options.add(wrapDrop(current, available));
        }
        if (integerAttrs.isEmpty() == false) {
            options.add(wrapEval(current, available, integerAttrs));
            options.add(wrapFilter(current, integerAttrs));
        }
        options.add(wrapLimit(current));
        options.add(wrapSort(current, available));
        return Arbitraries.oneOf(options);
    }

    private static Arbitrary<LogicalPlan> wrapKeep(LogicalPlan current, List<Attribute> available) {
        return arbitraryNonEmptySubset(available).map(kept -> {
            var projections = kept.stream().map(a -> (NamedExpression) a).toList();
            return new Keep(Source.EMPTY, current, projections);
        });
    }

    private static Arbitrary<LogicalPlan> wrapDrop(LogicalPlan current, List<Attribute> available) {
        return arbitraryProperSubset(available).map(dropped -> {
            var removals = dropped.stream().map(a -> (NamedExpression) a).toList();
            return new SimDrop(Source.EMPTY, current, removals);
        });
    }

    private static Arbitrary<LogicalPlan> wrapEval(LogicalPlan current, List<Attribute> available, List<Attribute> integerAttrs) {
        var existingNames = available.stream().map(Attribute::name).collect(Collectors.toSet());
        List<String> availableAliases = EVAL_ALIAS_POOL.stream().filter(n -> existingNames.contains(n) == false).toList();
        if (availableAliases.isEmpty()) {
            availableAliases = List.of("_col_0", "_col_1");
        }
        return Combinators.combine(Arbitraries.of(availableAliases), arbitraryExpression(integerAttrs))
            .as((name, expr) -> new Eval(Source.EMPTY, current, List.of(new Alias(Source.EMPTY, name, expr))));
    }

    private static Arbitrary<LogicalPlan> wrapFilter(LogicalPlan current, List<Attribute> integerAttrs) {
        return Combinators.combine(Arbitraries.of(integerAttrs), Arbitraries.integers().between(1, 10), Arbitraries.of(true, false))
            .as((attr, threshold, useGt) -> {
                var literal = new Literal(Source.EMPTY, threshold, DataType.INTEGER);
                Expression cond = useGt ? new GreaterThan(Source.EMPTY, attr, literal) : new LessThan(Source.EMPTY, attr, literal);
                return new Filter(Source.EMPTY, current, cond);
            });
    }

    private static Arbitrary<LogicalPlan> wrapLimit(LogicalPlan current) {
        return Arbitraries.integers()
            .between(1, 10)
            .map(n -> new Limit(Source.EMPTY, new Literal(Source.EMPTY, n, DataType.INTEGER), current));
    }

    private static Arbitrary<LogicalPlan> wrapSort(LogicalPlan current, List<Attribute> available) {
        return Combinators.combine(
            Arbitraries.of(available),
            Arbitraries.of(Order.OrderDirection.values()),
            Arbitraries.integers().between(1, 10)
        ).as((attr, dir, n) -> {
            var order = new Order(Source.EMPTY, attr, dir, Order.NullsPosition.ANY);
            var orderBy = new OrderBy(Source.EMPTY, current, List.of(order));
            return new Limit(Source.EMPTY, new Literal(Source.EMPTY, n, DataType.INTEGER), orderBy);
        });
    }

    private static <T> Arbitrary<List<T>> arbitraryNonEmptySubset(List<T> pool) {
        return Arbitraries.of(pool).set().ofMinSize(1).ofMaxSize(pool.size()).map(set -> List.copyOf(set));
    }

    /**
     * A proper subset: at least 1 element, but strictly fewer than all.
     */
    private static <T> Arbitrary<List<T>> arbitraryProperSubset(List<T> pool) {
        return Arbitraries.of(pool).set().ofMinSize(1).ofMaxSize(pool.size() - 1).map(set -> List.copyOf(set));
    }

    private static Arbitrary<Expression> arbitraryExpression(List<Attribute> integerAttrs) {
        var leaf = arbitraryLeaf(integerAttrs);
        var pair = Combinators.combine(leaf, leaf).as((left, right) -> new Expression[] { left, right });
        return Arbitraries.oneOf(
            pair.map(p -> new Add(Source.EMPTY, p[0], p[1], EsqlTestUtils.TEST_CFG)),
            pair.map(p -> new Sub(Source.EMPTY, p[0], p[1], EsqlTestUtils.TEST_CFG)),
            pair.map(p -> new Mul(Source.EMPTY, p[0], p[1]))
        );
    }

    private static Arbitrary<Expression> arbitraryLeaf(List<Attribute> integerAttrs) {
        return Arbitraries.oneOf(
            Arbitraries.of(integerAttrs).map(a -> a),
            Arbitraries.integers().between(1, 10).map(i -> new Literal(Source.EMPTY, i, DataType.INTEGER))
        );
    }
}
