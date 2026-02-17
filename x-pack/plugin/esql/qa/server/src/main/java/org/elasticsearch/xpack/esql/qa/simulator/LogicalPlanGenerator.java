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

import org.elasticsearch.core.Tuple;
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
import org.elasticsearch.xpack.esql.plan.logical.UnaryPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.function.Function.identity;

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
        return rawPlansFor(schema, depth).map(LogicalPlanGenerator::resolveReferences);
    }

    /** Returns plans WITHOUT {@link #resolveReferences} — for testing shrinking validity. */
    static Arbitrary<LogicalPlan> rawPlansFor(SimSchema schema, int depth) {
        return Arbitraries.recursive(
            () -> Arbitraries.just(buildEsRelation(schema)),
            childArb -> childArb.flatMap(LogicalPlanGenerator::wrapLayer),
            depth
        );
    }

    /**
     * Walks the plan bottom-up, replacing stale attribute references with canonical ones from
     * each node's child output. This is required for binary plan serialization — do NOT remove.
     * <p>
     * The {@code Arbitraries.recursive} base supplier {@code () -> Arbitraries.just(buildEsRelation(schema))}
     * is invoked multiple times by jqwik (for generation, shrinking, and reporting). Each invocation
     * calls {@code buildEsRelation}, creating new {@code ReferenceAttribute} objects with fresh
     * NameIds. But outer flatMap layers may retain closures referencing attributes from a previous
     * invocation. This produces plans where outer nodes hold attribute references with stale NameIds.
     * The ES|QL optimizer validates NameId consistency and rejects such plans with "optimized
     * incorrectly due to missing references" errors.
     * <p>
     * See {@code ShrinkingValidityTests} for empirical proof that raw plans have stale NameIds.
     * Staleness affects NameIds at ALL plan levels — not just the base EsRelation, but also
     * EVAL-created columns and other derived attributes. The exact jqwik mechanism is unknown,
     * but occurs during both generation and shrinking, regardless of edge-case mode.
     */
    static LogicalPlan resolveReferences(LogicalPlan plan) {
        if (plan instanceof EsRelation) {
            return plan;
        }
        if (plan instanceof UnaryPlan == false) {
            return plan;
        }
        LogicalPlan resolvedChild = resolveReferences(((UnaryPlan) plan).child());
        Map<String, Attribute> canonical = new HashMap<>();
        for (Attribute attr : resolvedChild.output()) {
            canonical.put(attr.name(), attr);
        }
        if (plan instanceof Keep keep) {
            var newProj = keep.projections()
                .stream()
                .map(ne -> (NamedExpression) canonical.getOrDefault(ne.name(), (Attribute) ne))
                .toList();
            return new Keep(keep.source(), resolvedChild, newProj);
        }
        if (plan instanceof Filter filter) {
            return new Filter(filter.source(), resolvedChild, resolveExpr(filter.condition(), canonical));
        }
        if (plan instanceof Eval eval) {
            var newFields = eval.fields()
                .stream()
                .map(a -> new Alias(a.source(), a.name(), resolveExpr(a.child(), canonical), a.id(), a.synthetic()))
                .toList();
            return new Eval(eval.source(), resolvedChild, newFields);
        }
        if (plan instanceof OrderBy orderBy) {
            var newOrders = orderBy.order()
                .stream()
                .map(o -> new Order(o.source(), resolveExpr(o.child(), canonical), o.direction(), o.nullsPosition()))
                .toList();
            return new OrderBy(orderBy.source(), resolvedChild, newOrders);
        }
        // Limit and other pass-through nodes: just replace child
        return ((UnaryPlan) plan).replaceChild(resolvedChild);
    }

    private static Expression resolveExpr(Expression expr, Map<String, Attribute> canonical) {
        if (expr instanceof Attribute a) {
            return canonical.getOrDefault(a.name(), a);
        }
        if (expr instanceof Literal) {
            return expr;
        }
        if (expr instanceof Add e) {
            return new Add(e.source(), resolveExpr(e.left(), canonical), resolveExpr(e.right(), canonical), e.configuration());
        }
        if (expr instanceof Sub e) {
            return new Sub(e.source(), resolveExpr(e.left(), canonical), resolveExpr(e.right(), canonical), e.configuration());
        }
        if (expr instanceof Mul e) {
            return new Mul(e.source(), resolveExpr(e.left(), canonical), resolveExpr(e.right(), canonical));
        }
        if (expr instanceof GreaterThan e) {
            return new GreaterThan(e.source(), resolveExpr(e.left(), canonical), resolveExpr(e.right(), canonical), e.zoneId());
        }
        if (expr instanceof LessThan e) {
            return new LessThan(e.source(), resolveExpr(e.left(), canonical), resolveExpr(e.right(), canonical), e.zoneId());
        }
        return expr;
    }

    static LogicalPlan buildEsRelation(SimSchema schema) {
        List<Attribute> attrs = schema.columns()
            .stream()
            .map(col -> (Attribute) new ReferenceAttribute(Source.EMPTY, col.name(), col.type()))
            .toList();
        return new EsRelation(Source.EMPTY, schema.indexName(), IndexMode.STANDARD, Map.of(), Map.of(), Map.of(), attrs);
    }

    static Arbitrary<LogicalPlan> wrapLayer(LogicalPlan current) {
        List<Attribute> available = current.output();
        List<Attribute> integerAttrs = available.stream().filter(a -> a.dataType() == DataType.INTEGER).toList();

        var options = new ArrayList<Arbitrary<LogicalPlan>>();
        options.add(Arbitraries.just(current)); // identity — allows shrinking layers away
        options.add(wrapKeep(current));
        if (available.size() > 1) {
            options.add(wrapDrop(current, available));
        }
        if (integerAttrs.isEmpty() == false) {
            options.add(wrapEval(current));
            options.add(wrapFilter(current, integerAttrs));
        }
        options.add(wrapLimit(current));
        options.add(wrapSort(current, available));
        return Arbitraries.oneOf(options);
    }

    private static Arbitrary<LogicalPlan> wrapKeep(LogicalPlan current) {
        var output = current.output();
        return arbitraryNonEmptySubset(current.output()).map(kept -> {
            for (var a : kept) {
                if (output.contains(a) == false) {
                    throw new IllegalStateException("Generated attribute '" + a + "' not in child output");
                }
            }
            var projections = kept.stream().map(a -> (NamedExpression) a).toList();
            var keep = new Keep(Source.EMPTY, current, projections);
            assert current.output().equals(output);
            var childOutput = current.output().stream().collect(Collectors.toMap(Attribute::name, identity(), (a, b) -> a));
            verifyNoStaleness(projections, childOutput);
            return keep;
        });
    }

    private static void verifyNoStaleness(List<NamedExpression> projections, Map<String, Attribute> childOutput) {
        for (var proj : projections) {
            var canon = childOutput.get(proj.name());
            if (canon != null && canon.id().equals(((Attribute) proj).id()) == false) {
                var err = new AssertionError(
                    "Stale at Keep creation: '" + proj.name() + "' expected " + canon.id() + " got " + ((Attribute) proj).id()
                );
                err.printStackTrace(System.err);
                throw err;
            }
        }
    }

    private static Arbitrary<LogicalPlan> wrapDrop(LogicalPlan current, List<Attribute> available) {
        return arbitraryProperSubset(available).map(dropped -> {
            var dropNames = dropped.stream().map(Attribute::name).collect(Collectors.toSet());
            var kept = available.stream().filter(a -> dropNames.contains(a.name()) == false).map(a -> (NamedExpression) a).toList();
            return new Keep(Source.EMPTY, current, kept);
        });
    }

    private static Arbitrary<LogicalPlan> wrapEval(LogicalPlan current) {
        var available = current.output();
        var integerAttrs = available.stream().filter(a -> a.dataType() == DataType.INTEGER).toList();
        var existingNames = available.stream().map(Attribute::name).collect(Collectors.toSet());
        List<String> availableAliases = EVAL_ALIAS_POOL.stream().filter(n -> existingNames.contains(n) == false).toList();
        if (availableAliases.isEmpty()) {
            availableAliases = List.of("_col_0", "_col_1");
        }
        return Combinators.combine(Arbitraries.of(availableAliases), arbitraryExpression(integerAttrs)).as((name, expr) -> {
            var childOutput = current.output().stream().collect(Collectors.toMap(Attribute::name, identity(), (a, b) -> a));
            expr.forEachDown(Attribute.class, a -> {
                var canon = childOutput.get(a.name());
                if (canon != null && canon.id().equals(a.id()) == false) {
                    var err = new AssertionError("Stale at Eval creation: '" + a.name() + "' expected " + canon.id() + " got " + a.id());
                    err.printStackTrace(System.err);
                    throw err;
                }
            });
            return new Eval(Source.EMPTY, current, List.of(new Alias(Source.EMPTY, name, expr)));
        });
    }

    private static Arbitrary<LogicalPlan> wrapFilter(LogicalPlan current, List<Attribute> integerAttrs) {
        return Combinators.combine(arbitraryAttribute(integerAttrs), Arbitraries.integers().between(1, 10), Arbitraries.of(true, false))
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
            arbitraryAttribute(available),
            Arbitraries.of(Order.OrderDirection.values()),
            Arbitraries.integers().between(1, 10)
        ).as((attr, dir, n) -> {
            var order = new Order(Source.EMPTY, attr, dir, Order.NullsPosition.ANY);
            var orderBy = new OrderBy(Source.EMPTY, current, List.of(order));
            return new Limit(Source.EMPTY, new Literal(Source.EMPTY, n, DataType.INTEGER), orderBy);
        });
    }

    private static Arbitrary<List<Attribute>> arbitraryNonEmptySubset(List<Attribute> attributes) {
        return arbitraryAttribute(attributes).set().ofMinSize(1).ofMaxSize(attributes.size()).map(List::copyOf);
    }

    /**
     * A proper subset: at least 1 element, but strictly fewer than all.
     */
    private static <T> Arbitrary<List<T>> arbitraryProperSubset(List<T> pool) {
        return Arbitraries.of(pool).set().ofMinSize(1).ofMaxSize(pool.size() - 1).map(List::copyOf);
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
            arbitraryAttribute(integerAttrs),
            Arbitraries.integers().between(1, 10).map(i -> new Literal(Source.EMPTY, i, DataType.INTEGER))
        );
    }

    private static Arbitrary<Attribute> arbitraryAttribute(List<Attribute> available) {
        // Getting around the fact that Arbitraries.of(List) doesn't work well with equals/hashCode since it doesn't consider NameId.
        return Arbitraries.of(available.stream().map(a -> Tuple.tuple(a, a.id())).toList()).map(Tuple::v1);
    }
}
