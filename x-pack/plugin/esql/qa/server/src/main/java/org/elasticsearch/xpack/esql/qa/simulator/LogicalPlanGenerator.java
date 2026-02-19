/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Generates random {@link LogicalPlan} trees. Plans are built bottom-up: an {@link EsRelation} base
 * is wrapped in randomly chosen layers (KEEP, DROP, EVAL, FILTER, LIMIT, SORT, STATS, INLINE STATS)
 * up to a configurable depth.
 */
public class LogicalPlanGenerator {
    private LogicalPlanGenerator() { /* static class */ }

    private static final int PLAN_DEPTH = Integer.getInteger("simulator.planDepth", 5);
    private static final int EXPR_DEPTH = Integer.getInteger("simulator.exprDepth", 2);

    private static final List<String> EVAL_ALIAS_POOL = List.of("z", "w", "v", "col_0", "col_1");
    private static final List<String> STATS_ALIAS_POOL = List.of("s0", "s1");
    private static final List<String> AGG_FUNC_POOL = List.of("COUNT", "SUM", "MIN", "MAX");

    static LogicalPlan generate(SimSchema schema, SourceOfRandomness random, GenerationStatus status) {
        return resolveReferences(generateRaw(schema, random, status));
    }

    /** Returns a plan WITHOUT {@link #resolveReferences} — for testing shrinking validity. */
    public static LogicalPlan generateRaw(SimSchema schema, SourceOfRandomness random, GenerationStatus status) {
        return generateRaw(schema, PLAN_DEPTH, random, status);
    }

    private static LogicalPlan generateRaw(SimSchema schema, int depth, SourceOfRandomness random, GenerationStatus status) {
        LogicalPlan plan = buildEsRelation(schema);
        for (int i = 0; i < depth; i++) {
            plan = wrapLayer(plan, random, status);
        }
        return plan;
    }

    /**
     * Walks the plan bottom-up, replacing stale attribute references with canonical ones from
     * each node's child output. This is required for binary plan serialization — do NOT remove.
     * <p>
     * When generating plans iteratively, outer nodes may hold attribute references that become
     * stale if earlier layers are modified. The ES|QL optimizer validates NameId consistency and
     * rejects such plans with "optimized incorrectly due to missing references" errors.
     * <p>
     * See {@code ShrinkingValidityTests} for empirical proof that raw plans can have stale NameIds.
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
        if (expr instanceof Count e) {
            return new Count(e.source(), resolveExpr(e.field(), canonical));
        }
        if (expr instanceof Sum e) {
            return new Sum(e.source(), resolveExpr(e.field(), canonical));
        }
        if (expr instanceof Min e) {
            return new Min(e.source(), resolveExpr(e.field(), canonical));
        }
        if (expr instanceof Max e) {
            return new Max(e.source(), resolveExpr(e.field(), canonical));
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

    /** Randomly wraps the given plan in one additional operator, or returns it unchanged (identity). */
    static LogicalPlan wrapLayer(LogicalPlan current, SourceOfRandomness random, GenerationStatus status) {
        List<Attribute> available = current.output();
        List<Attribute> integerAttrs = available.stream().filter(a -> a.dataType() == DataType.INTEGER).toList();

        List<Supplier<LogicalPlan>> options = new ArrayList<>();
        options.add(() -> current); // identity — allows shrinking layers away
        options.add(() -> wrapKeep(current, random));
        if (available.size() > 1) {
            options.add(() -> wrapDrop(current, available, random));
        }
        if (integerAttrs.isEmpty() == false) {
            options.add(() -> wrapEval(current, random, status));
            options.add(() -> wrapFilter(current, integerAttrs, random, status));
            options.add(() -> generateAggregate(current, integerAttrs, available, random, status));
            // INLINE STATS after LIMIT is not supported by the ES|QL engine
            if (current.anyMatch(Limit.class::isInstance) == false) {
                options.add(() -> wrapInlineStats(current, integerAttrs, available, random, status));
            }
        }
        options.add(() -> wrapLimit(current, random));
        options.add(() -> wrapSort(current, available, random, status));
        return random.choose(options).get();
    }

    private static LogicalPlan wrapKeep(LogicalPlan current, SourceOfRandomness random) {
        List<Attribute> kept = generateSubset(random, current.output(), 1, current.output().size());
        var projections = kept.stream().map(a -> (NamedExpression) a).toList();
        return new Keep(Source.EMPTY, current, projections);
    }

    private static LogicalPlan wrapDrop(LogicalPlan current, List<Attribute> available, SourceOfRandomness random) {
        List<Attribute> dropped = generateSubset(random, available, 1, available.size() - 1);
        var dropNames = dropped.stream().map(Attribute::name).collect(Collectors.toSet());
        var kept = available.stream().filter(a -> dropNames.contains(a.name()) == false).map(a -> (NamedExpression) a).toList();
        return new Keep(Source.EMPTY, current, kept);
    }

    private static LogicalPlan wrapEval(LogicalPlan current, SourceOfRandomness random, GenerationStatus status) {
        List<Attribute> available = current.output();
        List<Attribute> integerAttrs = available.stream().filter(a -> a.dataType() == DataType.INTEGER).toList();
        var existingNames = available.stream().map(Attribute::name).collect(Collectors.toSet());
        List<String> availableAliases = EVAL_ALIAS_POOL.stream().filter(n -> existingNames.contains(n) == false).toList();
        if (availableAliases.isEmpty()) {
            availableAliases = List.of("_col_0", "_col_1");
        }
        int maxAliases = Math.min(availableAliases.size(), 3);
        int nAliases = random.nextInt(1, maxAliases);
        List<String> shuffled = new ArrayList<>(availableAliases);
        Collections.shuffle(shuffled, random.toJDKRandom());
        List<Alias> fields = new ArrayList<>(nAliases);
        for (int i = 0; i < nAliases; i++) {
            fields.add(new Alias(Source.EMPTY, shuffled.get(i), generateExpression(integerAttrs, random, status)));
        }
        return new Eval(Source.EMPTY, current, fields);
    }

    private static LogicalPlan wrapFilter(
        LogicalPlan current,
        List<Attribute> integerAttrs,
        SourceOfRandomness random,
        GenerationStatus status
    ) {
        Expression left = generateExpression(integerAttrs, random, status);
        Expression right = random.nextBoolean()
            ? generateExpression(integerAttrs, random, status)
            : new Literal(Source.EMPTY, random.nextInt(1, 10), DataType.INTEGER);
        Expression cond = random.nextBoolean() ? new GreaterThan(Source.EMPTY, left, right) : new LessThan(Source.EMPTY, left, right);
        return new Filter(Source.EMPTY, current, cond);
    }

    private static LogicalPlan wrapLimit(LogicalPlan current, SourceOfRandomness random) {
        int n = random.nextInt(1, 10); // 1–10 inclusive
        return new Limit(Source.EMPTY, new Literal(Source.EMPTY, n, DataType.INTEGER), current);
    }

    private static LogicalPlan wrapSort(
        LogicalPlan current,
        List<Attribute> available,
        SourceOfRandomness random,
        GenerationStatus status
    ) {
        List<Attribute> integerAttrs = available.stream().filter(a -> a.dataType() == DataType.INTEGER).toList();
        int maxOrders = Math.min(available.size(), 3);
        int nOrders = random.nextInt(1, maxOrders);
        List<Order> orders = new ArrayList<>(nOrders);
        for (int i = 0; i < nOrders; i++) {
            Expression expr = (integerAttrs.isEmpty() || random.nextBoolean())
                ? random.choose(available)
                : generateExpression(integerAttrs, random, status);
            Order.OrderDirection dir = random.choose(Order.OrderDirection.values());
            orders.add(new Order(Source.EMPTY, expr, dir, Order.NullsPosition.ANY));
        }
        int n = random.nextInt(1, 10);
        return new Limit(Source.EMPTY, new Literal(Source.EMPTY, n, DataType.INTEGER), new OrderBy(Source.EMPTY, current, orders));
    }

    private static LogicalPlan wrapInlineStats(
        LogicalPlan current,
        List<Attribute> integerAttrs,
        List<Attribute> available,
        SourceOfRandomness random,
        GenerationStatus status
    ) {
        return new InlineStats(Source.EMPTY, generateAggregate(current, integerAttrs, available, random, status));
    }

    private static Aggregate generateAggregate(
        LogicalPlan current,
        List<Attribute> integerAttrs,
        List<Attribute> available,
        SourceOfRandomness random,
        GenerationStatus status
    ) {
        int maxGroups = Math.min(available.size(), 2);
        List<Attribute> groupKeys = (maxGroups > 0 && random.nextBoolean()) ? generateSubset(random, available, 1, maxGroups) : List.of();

        List<String> shuffledNames = new ArrayList<>(STATS_ALIAS_POOL);
        Collections.shuffle(shuffledNames, random.toJDKRandom());
        int nNames = random.nextInt(1, STATS_ALIAS_POOL.size());

        List<NamedExpression> aggregates = new ArrayList<>();
        for (int i = 0; i < nNames; i++) {
            Expression field = generateExpression(integerAttrs, random, status);
            String funcName = random.choose(AGG_FUNC_POOL);
            aggregates.add(new Alias(Source.EMPTY, shuffledNames.get(i), buildAggFunc(funcName, field)));
        }
        aggregates.addAll(groupKeys);
        return new Aggregate(Source.EMPTY, current, List.copyOf(groupKeys), List.copyOf(aggregates));
    }

    private static AggregateFunction buildAggFunc(String funcName, Expression field) {
        return switch (funcName) {
            case "COUNT" -> new Count(Source.EMPTY, field);
            case "SUM" -> new Sum(Source.EMPTY, field);
            case "MIN" -> new Min(Source.EMPTY, field);
            case "MAX" -> new Max(Source.EMPTY, field);
            default -> throw new IllegalStateException("Unknown aggregate function: " + funcName);
        };
    }

    private static Expression generateExpression(List<Attribute> integerAttrs, SourceOfRandomness random, GenerationStatus status) {
        return generateExpression(integerAttrs, EXPR_DEPTH, random);
    }

    private static Expression generateExpression(List<Attribute> integerAttrs, int depth, SourceOfRandomness random) {
        if (depth == 0 || random.nextBoolean()) {
            return generateLeaf(integerAttrs, random);
        }
        Expression left = generateExpression(integerAttrs, depth - 1, random);
        Expression right = generateExpression(integerAttrs, depth - 1, random);
        return switch (random.nextInt(0, 2)) {
            case 0 -> new Add(Source.EMPTY, left, right, EsqlTestUtils.TEST_CFG);
            case 1 -> new Sub(Source.EMPTY, left, right, EsqlTestUtils.TEST_CFG);
            case 2 -> new Mul(Source.EMPTY, left, right);
            default -> throw new IllegalStateException();
        };
    }

    private static Expression generateLeaf(List<Attribute> integerAttrs, SourceOfRandomness random) {
        if (random.nextBoolean()) {
            return random.choose(integerAttrs);
        }
        return new Literal(Source.EMPTY, random.nextInt(1, 10), DataType.INTEGER);
    }

    private static List<Attribute> generateSubset(SourceOfRandomness random, List<Attribute> attrs, int min, int max) {
        List<Attribute> shuffled = new ArrayList<>(attrs);
        Collections.shuffle(shuffled, random.toJDKRandom());
        int size = random.nextInt(min, max);
        return List.copyOf(shuffled.subList(0, Math.min(size, shuffled.size())));
    }
}
