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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.esql.core.util.TestUtils.of;

/**
 * Generates random {@link LogicalPlan} trees. Plans are built bottom-up: an {@link EsRelation} or {@link Row} base
 * is wrapped in randomly chosen layers (KEEP, DROP, EVAL, FILTER, LIMIT, SORT, STATS, INLINE STATS)
 * up to a configurable depth.
 */
class LogicalPlanGenerator {
    private LogicalPlanGenerator() { /* static class */ }

    private static final Set<DataType> NUMERIC_TYPES = Set.of(DataType.INTEGER, DataType.LONG, DataType.DOUBLE);
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
        LogicalPlan plan = random.nextInt(1, 10) <= 3 ? buildRow(schema, random) : buildEsRelation(schema);
        for (int i = 0; i < PLAN_DEPTH; i++) {
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
        if (!(plan instanceof UnaryPlan unaryPlan)) {
            return plan;
        }
        LogicalPlan resolvedChild = resolveReferences(unaryPlan.child());
        Map<String, Attribute> canonical = resolvedChild.output().stream().collect(Collectors.toMap(Attribute::name, a -> a, (a, b) -> b));
        return switch (plan) {
            case Keep keep -> new Keep(
                keep.source(),
                resolvedChild,
                keep.projections()
                    .stream()
                    .<NamedExpression>map(ne -> ne instanceof Attribute a ? canonical.getOrDefault(a.name(), a) : ne)
                    .toList()
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
            case OrderBy orderBy -> new OrderBy(
                orderBy.source(),
                resolvedChild,
                orderBy.order()
                    .stream()
                    .map(o -> new Order(o.source(), resolveExpr(o.child(), canonical), o.direction(), o.nullsPosition()))
                    .toList()
            );
            case Aggregate agg -> {
                List<Expression> newGroupings = agg.groupings().stream().map(e -> resolveExpr(e, canonical)).toList();
                List<NamedExpression> newAggregates = agg.aggregates().stream().map(ne -> switch (ne) {
                    case Alias a -> new Alias(a.source(), a.name(), resolveExpr(a.child(), canonical), a.id(), a.synthetic());
                    case Attribute attr -> canonical.getOrDefault(attr.name(), attr);
                    default -> ne;
                }).toList();
                yield new Aggregate(agg.source(), resolvedChild, newGroupings, newAggregates);
            }
            // Limit and other pass-through nodes: just replace child
            default -> unaryPlan.replaceChild(resolvedChild);
        };
    }

    private static Expression resolveExpr(Expression expr, Map<String, Attribute> canonical) {
        return switch (expr) {
            case Attribute a -> canonical.getOrDefault(a.name(), a);
            case Literal l -> l;
            default -> expr.replaceChildren(expr.children().stream().map(c -> resolveExpr(c, canonical)).toList());
        };
    }

    static LogicalPlan buildEsRelation(SimSchema schema) {
        return new EsRelation(
            Source.EMPTY,
            schema.indexName(),
            IndexMode.STANDARD,
            Map.of(),
            Map.of(),
            Map.of(),
            schema.columns().stream().<Attribute>map(col -> new ReferenceAttribute(Source.EMPTY, col.name(), col.type())).toList()
        );
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
            case INTEGER -> random.nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
            case LONG -> random.nextLong(Long.MIN_VALUE, Long.MAX_VALUE);
            case DOUBLE -> random.nextDouble(-Double.MAX_VALUE / 2, Double.MAX_VALUE / 2);
            // Literal requires BytesRef (not String) for KEYWORD values
            case KEYWORD -> new BytesRef(random.choose(KEYWORD_POOL));
            default -> throw new UnsupportedOperationException("Unsupported type for ROW literal: " + type);
        };
    }

    /**
     * Randomly wraps the given plan in one additional operator, or returns it unchanged (identity).
     * Options are lazy (Suppliers) so only the chosen branch consumes random state.
     */
    static LogicalPlan wrapLayer(LogicalPlan current, SourceOfRandomness random) {
        List<Attribute> available = current.output();
        List<Attribute> numericAttrs = available.stream().filter(a -> NUMERIC_TYPES.contains(a.dataType())).toList();
        List<Attribute> keywordAttrs = available.stream().filter(a -> a.dataType() == DataType.KEYWORD).toList();

        List<Supplier<LogicalPlan>> options = new ArrayList<>();
        options.add(() -> current); // identity — allows shrinking layers away
        options.add(() -> wrapKeep(current, random));
        if (available.size() > 1) {
            options.add(() -> wrapDrop(current, available, random));
        }
        if (numericAttrs.isEmpty() == false || keywordAttrs.isEmpty() == false) {
            options.add(() -> wrapEval(current, numericAttrs, keywordAttrs, random));
            options.add(() -> wrapFilter(current, numericAttrs, keywordAttrs, random));
        }
        if (numericAttrs.isEmpty() == false) {
            options.add(() -> generateAggregate(current, numericAttrs, keywordAttrs, available, random));
            // INLINE STATS after LIMIT is not supported by the ES|QL engine
            if (current.anyMatch(Limit.class::isInstance) == false) {
                options.add(() -> new InlineStats(Source.EMPTY, generateAggregate(current, numericAttrs, keywordAttrs, available, random)));
            }
        }
        // LIMIT and SORT are excluded: SORT always wraps in LIMIT, and when LIMIT cuts within a group of
        // rows with equal sort keys, simulator and ES pick different rows (nondeterministic tie-breaking),
        // causing false failures. Standalone LIMIT has the same problem. See TODO.
        return random.choose(options).get();
    }

    private static LogicalPlan wrapKeep(LogicalPlan current, SourceOfRandomness random) {
        List<Attribute> kept = generateSubset(random, current.output(), 1, current.output().size());
        return new Keep(Source.EMPTY, current, List.copyOf(kept));
    }

    // DROP is implemented as KEEP of the complement — the resolver does the same conversion
    private static LogicalPlan wrapDrop(LogicalPlan current, List<Attribute> available, SourceOfRandomness random) {
        List<Attribute> dropped = generateSubset(random, available, 1, available.size() - 1);
        Set<String> dropNames = dropped.stream().map(Attribute::name).collect(Collectors.toSet());
        return new Keep(Source.EMPTY, current, available.stream().filter(a -> dropNames.contains(a.name()) == false).toList());
    }

    private static LogicalPlan wrapEval(
        LogicalPlan current,
        List<Attribute> numericAttrs,
        List<Attribute> keywordAttrs,
        SourceOfRandomness random
    ) {
        Set<String> existingNames = current.output().stream().map(Attribute::name).collect(Collectors.toSet());
        List<String> pool = EVAL_ALIAS_POOL.stream().filter(n -> existingNames.contains(n) == false).toList();
        List<String> availableAliases = pool.isEmpty() ? List.of("_col_0", "_col_1") : pool;
        int maxAliases = Math.min(availableAliases.size(), 3);
        int nAliases = random.nextInt(1, maxAliases);
        List<String> shuffled = new ArrayList<>(availableAliases);
        Collections.shuffle(shuffled, random.toJDKRandom());
        List<Alias> fields = new ArrayList<>(nAliases);
        for (int i = 0; i < nAliases; i++) {
            Expression expr;
            if (keywordAttrs.isEmpty() == false && (numericAttrs.isEmpty() || random.nextBoolean())) {
                expr = generateKeywordExpression(keywordAttrs, EXPR_DEPTH, random);
            } else {
                expr = generateExpression(numericAttrs, keywordAttrs, EXPR_DEPTH, random);
            }
            fields.add(new Alias(Source.EMPTY, shuffled.get(i), expr));
        }
        return new Eval(Source.EMPTY, current, fields);
    }

    private static LogicalPlan wrapFilter(
        LogicalPlan current,
        List<Attribute> numericAttrs,
        List<Attribute> keywordAttrs,
        SourceOfRandomness random
    ) {
        // When no numeric columns, or sometimes when keywords exist, generate a string predicate
        if (keywordAttrs.isEmpty() == false && (numericAttrs.isEmpty() || random.nextBoolean())) {
            Expression str = random.choose(keywordAttrs);
            String pattern = random.choose(List.of("f", "B", "ba", "foo"));
            Expression patternLit = of(pattern);
            Expression cond = random.nextBoolean()
                ? new StartsWith(Source.EMPTY, str, patternLit)
                : new EndsWith(Source.EMPTY, str, patternLit);
            return new Filter(Source.EMPTY, current, cond);
        }
        Expression left = generateExpression(numericAttrs, keywordAttrs, EXPR_DEPTH, random);
        Expression right = random.nextBoolean()
            ? generateExpression(numericAttrs, keywordAttrs, EXPR_DEPTH, random)
            : generateNumericLiteral(numericAttrs, random);
        return new Filter(
            Source.EMPTY,
            current,
            GenUtils.<Expression>choose(
                random,
                new GreaterThan(Source.EMPTY, left, right),
                new LessThan(Source.EMPTY, left, right),
                new GreaterThanOrEqual(Source.EMPTY, left, right),
                new LessThanOrEqual(Source.EMPTY, left, right),
                new Equals(Source.EMPTY, left, right),
                new NotEquals(Source.EMPTY, left, right)
            )
        );
    }

    private static Aggregate generateAggregate(
        LogicalPlan current,
        List<Attribute> numericAttrs,
        List<Attribute> keywordAttrs,
        List<Attribute> available,
        SourceOfRandomness random
    ) {
        int maxGroups = Math.min(available.size(), 2);
        List<Attribute> groupKeys = random.nextBoolean() ? generateSubset(random, available, 1, maxGroups) : List.of();

        List<String> shuffledNames = new ArrayList<>(STATS_ALIAS_POOL);
        Collections.shuffle(shuffledNames, random.toJDKRandom());
        int nNames = random.nextInt(1, STATS_ALIAS_POOL.size());
        List<NamedExpression> aggregates = new ArrayList<>();
        for (int i = 0; i < nNames; i++) {
            Expression field = generateExpression(numericAttrs, keywordAttrs, EXPR_DEPTH, random);
            // Constant-only aggregate expressions crash INLINE STATS (ES planner bug)
            Expression aggField = field.references().isEmpty() ? random.choose(numericAttrs) : field;
            aggregates.add(new Alias(Source.EMPTY, shuffledNames.get(i), random.choose(AGG_FUNC_POOL).apply(Source.EMPTY, aggField)));
        }
        aggregates.addAll(groupKeys);
        return new Aggregate(Source.EMPTY, current, List.copyOf(groupKeys), List.copyOf(aggregates));
    }

    private static Expression generateExpression(
        List<Attribute> numericAttrs,
        List<Attribute> keywordAttrs,
        int depth,
        SourceOfRandomness random
    ) {
        if (depth == 0 || random.nextBoolean()) {
            return generateLeaf(numericAttrs, keywordAttrs, random);
        }
        Expression left = generateExpression(numericAttrs, keywordAttrs, depth - 1, random);
        Expression right = generateExpression(numericAttrs, keywordAttrs, depth - 1, random);
        Expression result = GenUtils.<Expression>choose(
            random,
            new Add(Source.EMPTY, left, right, EsqlTestUtils.TEST_CFG),
            new Sub(Source.EMPTY, left, right, EsqlTestUtils.TEST_CFG),
            new Mul(Source.EMPTY, left, right),
            new Div(Source.EMPTY, left, right),
            new Mod(Source.EMPTY, left, right)
        );
        // ~20% chance of wrapping in negation
        return random.nextInt(0, 4) == 0 ? new Neg(Source.EMPTY, result) : result;
    }

    private static Expression generateLeaf(List<Attribute> numericAttrs, List<Attribute> keywordAttrs, SourceOfRandomness random) {
        // If no numeric columns, fall back to LENGTH(keyword) if available, or a literal
        if (numericAttrs.isEmpty()) {
            return keywordAttrs.isEmpty()
                ? of(random.nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE))
                : new Length(Source.EMPTY, random.choose(keywordAttrs));
        }
        // 20% chance of LENGTH(keyword) if keyword columns exist
        if (keywordAttrs.isEmpty() == false && random.nextInt(0, 4) == 0) {
            return new Length(Source.EMPTY, random.choose(keywordAttrs));
        }
        if (random.nextBoolean()) {
            return random.choose(numericAttrs);
        }
        return generateNumericLiteral(numericAttrs, random);
    }

    /**
     * Generates a numeric literal whose type is randomly chosen from the types present in the given attributes.
     * Randomly selects among the numeric types present in the schema so all types (INTEGER, LONG, DOUBLE) are exercised proportionally.
     */
    private static Expression generateNumericLiteral(List<Attribute> numericAttrs, SourceOfRandomness random) {
        List<DataType> present = numericAttrs.stream().map(Attribute::dataType).distinct().toList();
        return switch (random.choose(present)) {
            case DOUBLE -> new Literal(Source.EMPTY, random.nextDouble(-Double.MAX_VALUE / 2, Double.MAX_VALUE / 2), DataType.DOUBLE);
            case LONG -> new Literal(Source.EMPTY, random.nextLong(Long.MIN_VALUE, Long.MAX_VALUE), DataType.LONG);
            default -> of(random.nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE));
        };
    }

    /** Generates a keyword expression: either a leaf (attribute or string literal) or a function call. */
    private static Expression generateKeywordExpression(List<Attribute> keywordAttrs, int depth, SourceOfRandomness random) {
        if (depth == 0 || random.nextBoolean()) {
            return generateKeywordLeaf(keywordAttrs, random);
        }
        Expression child = generateKeywordExpression(keywordAttrs, depth - 1, random);
        return GenUtils.<Expression>choose(
            random,
            new Trim(Source.EMPTY, child),
            new ToUpper(Source.EMPTY, child, EsqlTestUtils.TEST_CFG),
            new ToLower(Source.EMPTY, child, EsqlTestUtils.TEST_CFG),
            new Reverse(Source.EMPTY, child),
            generateConcat(keywordAttrs, depth, random, child),
            new Left(Source.EMPTY, child, of(random.nextInt(1, 5))),
            new Right(Source.EMPTY, child, of(random.nextInt(1, 5))),
            new Substring(Source.EMPTY, child, of(random.nextInt(1, 3)), random.nextBoolean() ? of(random.nextInt(1, 4)) : null)
        );
    }

    private static Concat generateConcat(List<Attribute> keywordAttrs, int depth, SourceOfRandomness random, Expression child) {
        int extraArgs = random.nextInt(1, 2);
        List<Expression> rest = new ArrayList<>(extraArgs);
        for (int j = 0; j < extraArgs; j++) {
            rest.add(generateKeywordExpression(keywordAttrs, depth - 1, random));
        }
        return new Concat(Source.EMPTY, child, rest);
    }

    private static Expression generateKeywordLeaf(List<Attribute> keywordAttrs, SourceOfRandomness random) {
        if (keywordAttrs.isEmpty() == false && random.nextBoolean()) {
            return random.choose(keywordAttrs);
        }
        return of(random.choose(KEYWORD_POOL));
    }

    /** Picks a random subset of {@code attrs}. Silently clamps to {@code attrs.size()} when the list is smaller than {@code min}. */
    private static List<Attribute> generateSubset(SourceOfRandomness random, List<Attribute> attrs, int min, int max) {
        List<Attribute> shuffled = new ArrayList<>(attrs);
        Collections.shuffle(shuffled, random.toJDKRandom());
        int size = random.nextInt(min, max);
        return List.copyOf(shuffled.subList(0, Math.min(size, shuffled.size())));
    }
}
