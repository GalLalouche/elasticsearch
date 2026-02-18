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
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.Order;
import org.elasticsearch.xpack.esql.expression.function.aggregate.AggregateFunction;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Count;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Max;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Min;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Sum;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;
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
import java.util.stream.IntStream;

/**
 * Generates random {@link LogicalPlan} trees using jqwik's {@link Arbitraries#recursive} combinator.
 * Plans are built bottom-up: an {@link org.elasticsearch.xpack.esql.plan.logical.EsRelation} base
 * is wrapped in randomly chosen layers (KEEP, DROP, EVAL) up to a configurable depth.
 */
public class LogicalPlanGenerator {
    private LogicalPlanGenerator() { /* static class */ }

    private static final int PLAN_DEPTH = Integer.getInteger("simulator.planDepth", 5);
    private static final int EXPR_DEPTH = Integer.getInteger("simulator.exprDepth", 2);

    private static final List<String> EVAL_ALIAS_POOL = List.of("z", "w", "v", "col_0", "col_1");
    private static final List<String> STATS_ALIAS_POOL = List.of("s0", "s1");

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
        if (plan instanceof Aggregate agg) {
            var newGroupings = agg.groupings().stream().map(e -> resolveExpr(e, canonical)).toList();
            var newAggregates = agg.aggregates().stream().<NamedExpression>map(ne -> {
                if (ne instanceof Alias a) {
                    return new Alias(a.source(), a.name(), resolveExpr(a.child(), canonical), a.id(), a.synthetic());
                }
                if (ne instanceof Attribute attr) {
                    return (NamedExpression) canonical.getOrDefault(attr.name(), attr);
                }
                return ne;
            }).toList();
            return new Aggregate(agg.source(), resolvedChild, newGroupings, newAggregates);
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
        if (expr instanceof Count e) return new Count(e.source(), resolveExpr(e.field(), canonical));
        if (expr instanceof Sum e) return new Sum(e.source(), resolveExpr(e.field(), canonical));
        if (expr instanceof Min e) return new Min(e.source(), resolveExpr(e.field(), canonical));
        if (expr instanceof Max e) return new Max(e.source(), resolveExpr(e.field(), canonical));
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
            options.add(wrapStats(current, integerAttrs, available));
            options.add(wrapInlineStats(current, integerAttrs, available));
        }
        options.add(wrapLimit(current));
        options.add(wrapSort(current, available));
        return Arbitraries.oneOf(options);
    }

    private static Arbitrary<LogicalPlan> wrapKeep(LogicalPlan current) {
        return arbitrarySubset(current.output(), 1, current.output().size()).map(kept -> {
            var projections = kept.stream().map(a -> (NamedExpression) a).toList();
            return new Keep(Source.EMPTY, current, projections);
        });
    }

    private static Arbitrary<LogicalPlan> wrapDrop(LogicalPlan current, List<Attribute> available) {
        return arbitrarySubset(available, 1, available.size() - 1).map(dropped -> {
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
        int maxAliases = Math.min(availableAliases.size(), 3);
        return Arbitraries.of(availableAliases).set().ofMinSize(1).ofMaxSize(maxAliases).flatMap(nameSet -> {
            var names = List.copyOf(nameSet);
            return arbitraryExpression(integerAttrs).list()
                .ofSize(names.size())
                .map(
                    exprs -> new Eval(
                        Source.EMPTY,
                        current,
                        IntStream.range(0, names.size()).mapToObj(i -> new Alias(Source.EMPTY, names.get(i), exprs.get(i))).toList()
                    )
                );
        });
    }

    private static Arbitrary<LogicalPlan> wrapFilter(LogicalPlan current, List<Attribute> integerAttrs) {
        return Combinators.combine(
            arbitraryExpression(integerAttrs),
            Arbitraries.oneOf(
                arbitraryExpression(integerAttrs),
                Arbitraries.integers().between(1, 10).map(i -> (Expression) new Literal(Source.EMPTY, i, DataType.INTEGER))
            ),
            Arbitraries.of(true, false)
        ).as((left, right, useGt) -> {
            Expression cond = useGt ? new GreaterThan(Source.EMPTY, left, right) : new LessThan(Source.EMPTY, left, right);
            return new Filter(Source.EMPTY, current, cond);
        });
    }

    private static Arbitrary<LogicalPlan> wrapLimit(LogicalPlan current) {
        return Arbitraries.integers()
            .between(1, 10)
            .map(n -> new Limit(Source.EMPTY, new Literal(Source.EMPTY, n, DataType.INTEGER), current));
    }

    private static Arbitrary<LogicalPlan> wrapSort(LogicalPlan current, List<Attribute> available) {
        List<Attribute> integerAttrs = available.stream().filter(a -> a.dataType() == DataType.INTEGER).toList();
        Arbitrary<Expression> sortExpr = integerAttrs.isEmpty()
            ? arbitraryAttribute(available).map(a -> (Expression) a)
            : Arbitraries.oneOf(arbitraryAttribute(available).map(a -> (Expression) a), arbitraryExpression(integerAttrs));
        int maxOrders = Math.min(available.size(), 3);
        return Combinators.combine(
            sortExpr.list().ofMinSize(1).ofMaxSize(maxOrders),
            Arbitraries.of(Order.OrderDirection.values()).list().ofMinSize(1).ofMaxSize(maxOrders),
            Arbitraries.integers().between(1, 10)
        ).as((exprs, dirs, n) -> {
            int size = Math.min(exprs.size(), dirs.size());
            var orders = IntStream.range(0, size)
                .mapToObj(i -> new Order(Source.EMPTY, exprs.get(i), dirs.get(i), Order.NullsPosition.ANY))
                .toList();
            var orderBy = new OrderBy(Source.EMPTY, current, orders);
            return new Limit(Source.EMPTY, new Literal(Source.EMPTY, n, DataType.INTEGER), orderBy);
        });
    }

    private static Arbitrary<LogicalPlan> wrapStats(LogicalPlan current, List<Attribute> integerAttrs, List<Attribute> available) {
        return arbitraryAggregate(current, integerAttrs, available).map(agg -> (LogicalPlan) agg);
    }

    private static Arbitrary<LogicalPlan> wrapInlineStats(LogicalPlan current, List<Attribute> integerAttrs, List<Attribute> available) {
        return arbitraryAggregate(current, integerAttrs, available).map(agg -> new InlineStats(Source.EMPTY, agg));
    }

    private static Arbitrary<Aggregate> arbitraryAggregate(LogicalPlan current, List<Attribute> integerAttrs, List<Attribute> available) {
        int maxGroups = Math.min(available.size(), 2);
        Arbitrary<List<Attribute>> groupsArb = maxGroups > 0
            ? Arbitraries.oneOf(Arbitraries.just(List.of()), arbitrarySubset(available, 1, maxGroups))
            : Arbitraries.just(List.of());
        return Arbitraries.of(STATS_ALIAS_POOL).set().ofMinSize(1).ofMaxSize(STATS_ALIAS_POOL.size()).flatMap(aliasNames -> {
            var names = List.copyOf(aliasNames);
            return Combinators.combine(
                arbitraryExpression(integerAttrs).list().ofSize(names.size()),
                Arbitraries.of("COUNT", "SUM", "MIN", "MAX").list().ofSize(names.size()),
                groupsArb
            ).as((fields, funcs, groupKeys) -> {
                var aggregates = new ArrayList<NamedExpression>(
                    IntStream.range(0, names.size())
                        .mapToObj(i -> (NamedExpression) new Alias(Source.EMPTY, names.get(i), buildAggFunc(funcs.get(i), fields.get(i))))
                        .toList()
                );
                aggregates.addAll(groupKeys);
                return new Aggregate(Source.EMPTY, current, List.copyOf(groupKeys), aggregates);
            });
        });
    }

    private static AggregateFunction buildAggFunc(String funcName, Expression field) {
        return switch (funcName) {
            case "COUNT" -> new Count(Source.EMPTY, field);
            case "SUM" -> new Sum(Source.EMPTY, field);
            case "MIN" -> new Min(Source.EMPTY, field);
            case "MAX" -> new Max(Source.EMPTY, field);
            default -> throw new IllegalStateException();
        };
    }

    private static Arbitrary<List<Attribute>> arbitrarySubset(List<Attribute> attrs, int minSize, int maxSize) {
        return arbitraryAttribute(attrs).set().ofMinSize(minSize).ofMaxSize(maxSize).map(List::copyOf);
    }

    private static Arbitrary<Expression> arbitraryExpression(List<Attribute> integerAttrs) {
        return Arbitraries.recursive(
            () -> arbitraryLeaf(integerAttrs),
            child -> Combinators.combine(child, child)
                .flatAs(
                    (left, right) -> Arbitraries.<Expression>oneOf(
                        Arbitraries.just(new Add(Source.EMPTY, left, right, EsqlTestUtils.TEST_CFG)),
                        Arbitraries.just(new Sub(Source.EMPTY, left, right, EsqlTestUtils.TEST_CFG)),
                        Arbitraries.just(new Mul(Source.EMPTY, left, right))
                    )
                ),
            EXPR_DEPTH
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
