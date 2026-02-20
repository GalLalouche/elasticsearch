/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import org.apache.lucene.util.BytesRef;
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
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Concat;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.EndsWith;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Left;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Length;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Reverse;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Right;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.StartsWith;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Substring;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToLower;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToUpper;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Trim;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Div;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mod;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Neg;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThanOrEqual;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThanOrEqual;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.NotEquals;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.Limit;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;
import org.elasticsearch.xpack.esql.plan.logical.Row;
import org.elasticsearch.xpack.esql.plan.logical.UnaryPlan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Generates random {@link LogicalPlan} trees. Plans are built bottom-up: an {@link EsRelation} or {@link Row} base
 * is wrapped in randomly chosen layers (KEEP, DROP, EVAL, FILTER, LIMIT, SORT, STATS, INLINE STATS)
 * up to a configurable depth.
 */
public class LogicalPlanGenerator {
    private LogicalPlanGenerator() { /* static class */ }

    private static final int PLAN_DEPTH = Integer.getInteger("simulator.planDepth", 5);
    private static final int EXPR_DEPTH = Integer.getInteger("simulator.exprDepth", 2);

    private static final List<String> EVAL_ALIAS_POOL = List.of("z", "w", "v", "col_0", "col_1");
    private static final List<String> STATS_ALIAS_POOL = List.of("s0", "s1");
    private static final List<String> KEYWORD_POOL = List.of("foo", "bar", "baz", " Hi ", "HELLO", "world");
    private static final List<BiFunction<Source, Expression, AggregateFunction>> AGG_FUNC_POOL = List.of(
        Count::new,
        Sum::new,
        Min::new,
        Max::new
    );

    static LogicalPlan generate(SimSchema schema, SourceOfRandomness random) {
        return resolveReferences(generateRaw(schema, random));
    }

    /** Returns a plan WITHOUT {@link #resolveReferences} — for testing shrinking validity. */
    static LogicalPlan generateRaw(SimSchema schema, SourceOfRandomness random) {
        return generateRaw(schema, PLAN_DEPTH, random);
    }

    private static LogicalPlan generateRaw(SimSchema schema, int depth, SourceOfRandomness random) {
        LogicalPlan plan = random.nextInt(1, 10) <= 3 ? buildRow(schema, random) : buildEsRelation(schema);
        for (int i = 0; i < depth; i++) {
            plan = wrapLayer(plan, random);
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
        if (plan instanceof EsRelation || plan instanceof Row) {
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
        return switch (plan) {
            case Keep keep -> new Keep(
                keep.source(),
                resolvedChild,
                keep.projections().stream().map(ne -> (NamedExpression) canonical.getOrDefault(ne.name(), (Attribute) ne)).toList()
            );
            case Filter filter -> new Filter(filter.source(), resolvedChild, resolveExpr(filter.condition(), canonical));
            case Eval eval -> new Eval(
                eval.source(),
                resolvedChild,
                eval.fields()
                    .stream()
                    .map(a -> new Alias(a.source(), a.name(), resolveExpr(a.child(), canonical), a.id(), a.synthetic()))
                    .toList()
            );
            case OrderBy orderBy -> {
                yield new OrderBy(
                    orderBy.source(),
                    resolvedChild,
                    orderBy.order()
                        .stream()
                        .map(o -> new Order(o.source(), resolveExpr(o.child(), canonical), o.direction(), o.nullsPosition()))
                        .toList()
                );
            }
            case Aggregate agg -> {
                List<Expression> newGroupings = agg.groupings().stream().map(e -> resolveExpr(e, canonical)).toList();
                List<NamedExpression> newAggregates = agg.aggregates().stream().<NamedExpression>map(ne -> switch (ne) {
                    case Alias a -> new Alias(a.source(), a.name(), resolveExpr(a.child(), canonical), a.id(), a.synthetic());
                    case Attribute attr -> (NamedExpression) canonical.getOrDefault(attr.name(), attr);
                    default -> ne;
                }).toList();
                yield new Aggregate(agg.source(), resolvedChild, newGroupings, newAggregates);
            }
            default -> // Limit and other pass-through nodes: just replace child
                ((UnaryPlan) plan).replaceChild(resolvedChild);
        };
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
        if (expr instanceof Div e) {
            return new Div(e.source(), resolveExpr(e.left(), canonical), resolveExpr(e.right(), canonical));
        }
        if (expr instanceof Mod e) {
            return new Mod(e.source(), resolveExpr(e.left(), canonical), resolveExpr(e.right(), canonical));
        }
        if (expr instanceof Neg e) {
            return new Neg(e.source(), resolveExpr(e.field(), canonical));
        }
        if (expr instanceof GreaterThan e) {
            return new GreaterThan(e.source(), resolveExpr(e.left(), canonical), resolveExpr(e.right(), canonical), e.zoneId());
        }
        if (expr instanceof LessThan e) {
            return new LessThan(e.source(), resolveExpr(e.left(), canonical), resolveExpr(e.right(), canonical), e.zoneId());
        }
        if (expr instanceof GreaterThanOrEqual e) {
            return new GreaterThanOrEqual(e.source(), resolveExpr(e.left(), canonical), resolveExpr(e.right(), canonical), e.zoneId());
        }
        if (expr instanceof LessThanOrEqual e) {
            return new LessThanOrEqual(e.source(), resolveExpr(e.left(), canonical), resolveExpr(e.right(), canonical), e.zoneId());
        }
        if (expr instanceof Equals e) {
            return new Equals(e.source(), resolveExpr(e.left(), canonical), resolveExpr(e.right(), canonical), e.zoneId());
        }
        if (expr instanceof NotEquals e) {
            return new NotEquals(e.source(), resolveExpr(e.left(), canonical), resolveExpr(e.right(), canonical), e.zoneId());
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
        if (expr instanceof Trim e) {
            return new Trim(e.source(), resolveExpr(e.field(), canonical));
        }
        if (expr instanceof ToUpper e) {
            return new ToUpper(e.source(), resolveExpr(e.field(), canonical), e.configuration());
        }
        if (expr instanceof ToLower e) {
            return new ToLower(e.source(), resolveExpr(e.field(), canonical), e.configuration());
        }
        if (expr instanceof Reverse e) {
            return new Reverse(e.source(), resolveExpr(e.field(), canonical));
        }
        if (expr instanceof Length e) {
            return new Length(e.source(), resolveExpr(e.field(), canonical));
        }
        if (expr instanceof Concat e) {
            List<Expression> resolved = e.children().stream().map(c -> resolveExpr(c, canonical)).toList();
            return new Concat(e.source(), resolved.getFirst(), resolved.subList(1, resolved.size()));
        }
        if (expr instanceof Left e) {
            return new Left(e.source(), resolveExpr(e.children().get(0), canonical), resolveExpr(e.children().get(1), canonical));
        }
        if (expr instanceof Right e) {
            return new Right(e.source(), resolveExpr(e.children().get(0), canonical), resolveExpr(e.children().get(1), canonical));
        }
        if (expr instanceof StartsWith e) {
            return new StartsWith(e.source(), resolveExpr(e.children().get(0), canonical), resolveExpr(e.children().get(1), canonical));
        }
        if (expr instanceof EndsWith e) {
            return new EndsWith(e.source(), resolveExpr(e.children().get(0), canonical), resolveExpr(e.children().get(1), canonical));
        }
        if (expr instanceof Substring e) {
            Expression resolvedStr = resolveExpr(e.children().get(0), canonical);
            Expression resolvedStart = resolveExpr(e.children().get(1), canonical);
            Expression resolvedLen = e.children().size() > 2 ? resolveExpr(e.children().get(2), canonical) : null;
            return new Substring(e.source(), resolvedStr, resolvedStart, resolvedLen);
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

    static Row buildRow(SimSchema schema, SourceOfRandomness random) {
        return new Row(
            Source.EMPTY,
            schema.columns()
                .stream()
                .map(
                    col -> new Alias(
                        Source.EMPTY,
                        col.name(),
                        new Literal(Source.EMPTY, generateLiteralValue(col.type(), random), col.type())
                    )
                )
                .toList()
        );
    }

    private static Object generateLiteralValue(DataType type, SourceOfRandomness random) {
        return switch (type) {
            case INTEGER -> random.nextInt(1, 10);
            // Literal requires BytesRef (not String) for KEYWORD values
            case KEYWORD -> new BytesRef(random.choose(KEYWORD_POOL));
            default -> throw new UnsupportedOperationException("Unsupported type for ROW literal: " + type);
        };
    }

    /** Randomly wraps the given plan in one additional operator, or returns it unchanged (identity). Options are lazy (Suppliers) so only the chosen branch consumes random state. */
    static LogicalPlan wrapLayer(LogicalPlan current, SourceOfRandomness random) {
        List<Attribute> available = current.output();
        List<Attribute> integerAttrs = available.stream().filter(a -> a.dataType() == DataType.INTEGER).toList();
        List<Attribute> keywordAttrs = available.stream().filter(a -> a.dataType() == DataType.KEYWORD).toList();

        List<Supplier<LogicalPlan>> options = new ArrayList<>();
        options.add(() -> current); // identity — allows shrinking layers away
        options.add(() -> wrapKeep(current, random));
        if (available.size() > 1) {
            options.add(() -> wrapDrop(current, available, random));
        }
        if (integerAttrs.isEmpty() == false || keywordAttrs.isEmpty() == false) {
            options.add(() -> wrapEval(current, random));
            options.add(() -> wrapFilter(current, integerAttrs, random));
        }
        if (integerAttrs.isEmpty() == false) {
            options.add(() -> generateAggregate(current, integerAttrs, available, random));
            // INLINE STATS after LIMIT is not supported by the ES|QL engine
            if (current.anyMatch(Limit.class::isInstance) == false) {
                options.add(() -> wrapInlineStats(current, integerAttrs, available, random));
            }
        }
        // LIMIT and SORT are excluded: SORT always wraps in LIMIT, and when LIMIT cuts within a group of
        // rows with equal sort keys, simulator and ES pick different rows (nondeterministic tie-breaking),
        // causing false failures. Standalone LIMIT has the same problem. See TODO.
        return random.choose(options).get();
    }

    private static LogicalPlan wrapKeep(LogicalPlan current, SourceOfRandomness random) {
        List<Attribute> kept = generateSubset(random, current.output(), 1, current.output().size());
        List<NamedExpression> projections = kept.stream().map(a -> (NamedExpression) a).toList();
        return new Keep(Source.EMPTY, current, projections);
    }

    private static LogicalPlan wrapDrop(LogicalPlan current, List<Attribute> available, SourceOfRandomness random) {
        List<Attribute> dropped = generateSubset(random, available, 1, available.size() - 1);
        Set<String> dropNames = dropped.stream().map(Attribute::name).collect(Collectors.toSet());
        List<NamedExpression> kept = available.stream()
            .filter(a -> dropNames.contains(a.name()) == false)
            .map(a -> (NamedExpression) a)
            .toList();
        return new Keep(Source.EMPTY, current, kept);
    }

    private static LogicalPlan wrapEval(LogicalPlan current, SourceOfRandomness random) {
        List<Attribute> available = current.output();
        List<Attribute> integerAttrs = available.stream().filter(a -> a.dataType() == DataType.INTEGER).toList();
        List<Attribute> keywordAttrs = available.stream().filter(a -> a.dataType() == DataType.KEYWORD).toList();
        Set<String> existingNames = available.stream().map(Attribute::name).collect(Collectors.toSet());
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
            Expression expr;
            if (keywordAttrs.isEmpty() == false && (integerAttrs.isEmpty() || random.nextBoolean())) {
                expr = generateKeywordExpression(keywordAttrs, EXPR_DEPTH, random);
            } else {
                expr = generateExpression(integerAttrs, keywordAttrs, EXPR_DEPTH, random);
            }
            fields.add(new Alias(Source.EMPTY, shuffled.get(i), expr));
        }
        return new Eval(Source.EMPTY, current, fields);
    }

    private static LogicalPlan wrapFilter(LogicalPlan current, List<Attribute> integerAttrs, SourceOfRandomness random) {
        List<Attribute> available = current.output();
        List<Attribute> keywordAttrs = available.stream().filter(a -> a.dataType() == DataType.KEYWORD).toList();
        // When no integer columns, or sometimes when keywords exist, generate a string predicate
        if (keywordAttrs.isEmpty() == false && (integerAttrs.isEmpty() || random.nextBoolean())) {
            Expression str = random.choose(keywordAttrs);
            String pattern = random.choose(List.of("f", "B", "ba", "foo"));
            Expression patternLit = new Literal(Source.EMPTY, new BytesRef(pattern), DataType.KEYWORD);
            Expression cond = random.nextBoolean()
                ? new StartsWith(Source.EMPTY, str, patternLit)
                : new EndsWith(Source.EMPTY, str, patternLit);
            return new Filter(Source.EMPTY, current, cond);
        }
        Expression left = generateExpression(integerAttrs, keywordAttrs, EXPR_DEPTH, random);
        Expression right = random.nextBoolean()
            ? generateExpression(integerAttrs, keywordAttrs, EXPR_DEPTH, random)
            : new Literal(Source.EMPTY, random.nextInt(1, 10), DataType.INTEGER);
        Expression cond = switch (random.nextInt(0, 5)) {
            case 0 -> new GreaterThan(Source.EMPTY, left, right);
            case 1 -> new LessThan(Source.EMPTY, left, right);
            case 2 -> new GreaterThanOrEqual(Source.EMPTY, left, right);
            case 3 -> new LessThanOrEqual(Source.EMPTY, left, right);
            case 4 -> new Equals(Source.EMPTY, left, right);
            case 5 -> new NotEquals(Source.EMPTY, left, right);
            default -> throw new IllegalStateException();
        };
        return new Filter(Source.EMPTY, current, cond);
    }

    private static LogicalPlan wrapInlineStats(
        LogicalPlan current,
        List<Attribute> integerAttrs,
        List<Attribute> available,
        SourceOfRandomness random
    ) {
        return new InlineStats(Source.EMPTY, generateAggregate(current, integerAttrs, available, random));
    }

    private static Aggregate generateAggregate(
        LogicalPlan current,
        List<Attribute> integerAttrs,
        List<Attribute> available,
        SourceOfRandomness random
    ) {
        int maxGroups = Math.min(available.size(), 2);
        List<Attribute> groupKeys = (maxGroups > 0 && random.nextBoolean()) ? generateSubset(random, available, 1, maxGroups) : List.of();

        List<String> shuffledNames = new ArrayList<>(STATS_ALIAS_POOL);
        Collections.shuffle(shuffledNames, random.toJDKRandom());
        int nNames = random.nextInt(1, STATS_ALIAS_POOL.size());

        List<Attribute> keywordAttrs = available.stream().filter(a -> a.dataType() == DataType.KEYWORD).toList();
        List<NamedExpression> aggregates = new ArrayList<>();
        for (int i = 0; i < nNames; i++) {
            Expression field = generateExpression(integerAttrs, keywordAttrs, EXPR_DEPTH, random);
            // Constant-only aggregate expressions crash INLINE STATS (ES planner bug)
            if (field.references().isEmpty()) {
                field = random.choose(integerAttrs);
            }
            aggregates.add(new Alias(Source.EMPTY, shuffledNames.get(i), random.choose(AGG_FUNC_POOL).apply(Source.EMPTY, field)));
        }
        aggregates.addAll(groupKeys);
        return new Aggregate(Source.EMPTY, current, List.copyOf(groupKeys), List.copyOf(aggregates));
    }

    private static Expression generateExpression(
        List<Attribute> integerAttrs,
        List<Attribute> keywordAttrs,
        int depth,
        SourceOfRandomness random
    ) {
        if (depth == 0 || random.nextBoolean()) {
            return generateLeaf(integerAttrs, keywordAttrs, random);
        }
        Expression left = generateExpression(integerAttrs, keywordAttrs, depth - 1, random);
        Expression right = generateExpression(integerAttrs, keywordAttrs, depth - 1, random);
        // nextInt is inclusive on both bounds
        Expression result = switch (random.nextInt(0, 4)) {
            case 0 -> new Add(Source.EMPTY, left, right, EsqlTestUtils.TEST_CFG);
            case 1 -> new Sub(Source.EMPTY, left, right, EsqlTestUtils.TEST_CFG);
            case 2 -> new Mul(Source.EMPTY, left, right);
            case 3 -> new Div(Source.EMPTY, left, right);
            case 4 -> new Mod(Source.EMPTY, left, right);
            default -> throw new IllegalStateException();
        };
        // ~20% chance of wrapping in negation
        if (random.nextInt(0, 4) == 0) {
            result = new Neg(Source.EMPTY, result);
        }
        return result;
    }

    private static Expression generateLeaf(List<Attribute> integerAttrs, List<Attribute> keywordAttrs, SourceOfRandomness random) {
        // If no integer columns, fall back to LENGTH(keyword) if available, or a literal
        if (integerAttrs.isEmpty()) {
            if (keywordAttrs.isEmpty() == false) {
                return new Length(Source.EMPTY, random.choose(keywordAttrs));
            }
            return new Literal(Source.EMPTY, random.nextInt(1, 10), DataType.INTEGER);
        }
        // 20% chance of LENGTH(keyword) if keyword columns exist
        if (keywordAttrs.isEmpty() == false && random.nextInt(0, 4) == 0) {
            return new Length(Source.EMPTY, random.choose(keywordAttrs));
        }
        if (random.nextBoolean()) {
            return random.choose(integerAttrs);
        }
        return new Literal(Source.EMPTY, random.nextInt(1, 10), DataType.INTEGER);
    }

    /** Generates a keyword expression: either a leaf (attribute or string literal) or a function call. */
    private static Expression generateKeywordExpression(List<Attribute> keywordAttrs, int depth, SourceOfRandomness random) {
        if (depth == 0 || random.nextBoolean()) {
            return generateKeywordLeaf(keywordAttrs, random);
        }
        Expression child = generateKeywordExpression(keywordAttrs, depth - 1, random);
        // nextInt is inclusive on both bounds
        return switch (random.nextInt(0, 7)) {
            case 0 -> new Trim(Source.EMPTY, child);
            case 1 -> new ToUpper(Source.EMPTY, child, EsqlTestUtils.TEST_CFG);
            case 2 -> new ToLower(Source.EMPTY, child, EsqlTestUtils.TEST_CFG);
            case 3 -> new Reverse(Source.EMPTY, child);
            case 4 -> {
                int nRest = random.nextInt(1, 2);
                List<Expression> rest = new ArrayList<>(nRest);
                for (int j = 0; j < nRest; j++) {
                    rest.add(generateKeywordExpression(keywordAttrs, depth - 1, random));
                }
                yield new Concat(Source.EMPTY, child, rest);
            }
            case 5 -> {
                Expression len = new Literal(Source.EMPTY, random.nextInt(1, 5), DataType.INTEGER);
                yield new Left(Source.EMPTY, child, len);
            }
            case 6 -> {
                Expression len = new Literal(Source.EMPTY, random.nextInt(1, 5), DataType.INTEGER);
                yield new Right(Source.EMPTY, child, len);
            }
            case 7 -> {
                Expression start = new Literal(Source.EMPTY, random.nextInt(1, 3), DataType.INTEGER);
                Expression len = random.nextBoolean() ? new Literal(Source.EMPTY, random.nextInt(1, 4), DataType.INTEGER) : null;
                yield new Substring(Source.EMPTY, child, start, len);
            }
            default -> throw new IllegalStateException();
        };
    }

    private static Expression generateKeywordLeaf(List<Attribute> keywordAttrs, SourceOfRandomness random) {
        if (keywordAttrs.isEmpty() == false && random.nextBoolean()) {
            return random.choose(keywordAttrs);
        }
        return new Literal(Source.EMPTY, new BytesRef(random.choose(KEYWORD_POOL)), DataType.KEYWORD);
    }

    /** Picks a random subset of {@code attrs}. Silently clamps to {@code attrs.size()} when the list is smaller than {@code min}. */
    private static List<Attribute> generateSubset(SourceOfRandomness random, List<Attribute> attrs, int min, int max) {
        List<Attribute> shuffled = new ArrayList<>(attrs);
        Collections.shuffle(shuffled, random.toJDKRandom());
        int size = random.nextInt(min, max);
        return List.copyOf(shuffled.subList(0, Math.min(size, shuffled.size())));
    }
}
