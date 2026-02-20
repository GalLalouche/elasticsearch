/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.util.ArrayUtils;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xpack.esql.CsvTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
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
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.ArithmeticOperation;
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
import org.elasticsearch.xpack.esql.plan.logical.Drop;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.Limit;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;
import org.elasticsearch.xpack.esql.plan.logical.UnresolvedRelation;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.elasticsearch.common.logging.LoggerMessageFormat.format;
import static org.elasticsearch.xpack.esql.CsvTestUtils.multiValuesAwareCsvToStringArray;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.reader;

/**
 * Evaluates a {@link LogicalPlan} against in-memory or CSV-backed data, producing columnar results.
 * Acts as a reference implementation for ES|QL semantics: property tests compare its output
 * against a real Elasticsearch cluster to detect divergences.
 */
public class Simulator {
    @Nullable
    private final SimSchema schema;
    @Nullable
    private final List<Map<String, Object>> data;
    private final SimBug activeBug;

    Simulator() {
        this(null, null, SimBug.BUG_FREE);
    }

    Simulator(@Nullable SimSchema schema, @Nullable List<Map<String, Object>> data) {
        this(schema, data, SimBug.BUG_FREE);
    }

    Simulator(@Nullable SimSchema schema, @Nullable List<Map<String, Object>> data, SimBug activeBug) {
        this.schema = schema;
        this.data = data;
        this.activeBug = activeBug;
    }

    public Result simulate(LogicalPlan plan) throws IOException {
        return switch (plan) {
            case org.elasticsearch.xpack.esql.plan.logical.Row row -> visit(row);
            case UnresolvedRelation relation -> visit(relation);
            case EsRelation relation -> visit(relation);
            case Keep keep -> visit(keep);
            case Drop drop -> visit(drop);
            case Filter filter -> visit(filter);
            case Eval eval -> visit(eval);
            case InlineStats inlineStats -> visit(inlineStats);
            case Aggregate aggregate -> visit(aggregate);
            case Limit limit -> visit(limit);
            case OrderBy orderBy -> visit(orderBy);
            default -> throw new UnsupportedOperationException(
                Strings.format("Simulation not (yet) supported for plan type: %s", plan.getClass())
            );
        };
    }

    private Result visit(org.elasticsearch.xpack.esql.plan.logical.Row row) {
        List<Column> columns = row.fields().stream().map(alias -> {
            if (alias.child() instanceof Literal l) {
                return new Column(alias.name(), alias.dataType(), List.of(normalizeObject(l.value())));
            }
            throw new UnsupportedOperationException(
                Strings.format("Row field [%s] is not a literal, but a %s", alias.name(), alias.child().getClass())
            );
        }).toList();
        return new Result(columns);
    }

    private Result visit(UnresolvedRelation relation) throws IOException {
        String pattern = relation.indexPattern().indexPattern();
        assert pattern.indexOf('*') == -1 : "Index patterns with wildcards are not supported yet in simulation, found: " + pattern;

        if (schema != null && data != null && pattern.equals(schema.indexName())) {
            return buildResultFromMemory(schema, data);
        }

        var file = Simulator.class.getResource("/data/" + pattern + ".csv");
        assert file != null : "File not found for index pattern: " + pattern;
        // FIXME(gal, NOCOMMIT) Reduce duplication with CsvTestsDataLoader
        try (BufferedReader reader = reader(file)) {
            String line;
            int lineNumber = 1;

            line = reader.readLine();
            assert line != null : "File is empty: " + file;
            String[] entries = multiValuesAwareCsvToStringArray(line, lineNumber);
            var columns = new ArrayList<Column>(entries.length);
            for (String entry : entries) {
                int split = entry.indexOf(':');
                assert split >= 0 : "Schema row must contain a colon to separate the field name from its type: " + entry;
                String name = entry.substring(0, split).trim();
                DataType type = DataType.fromTypeName(entry.substring(split + 1).trim());
                columns.add(new Column(name, type, new ArrayList<>()));
            }
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                entries = multiValuesAwareCsvToStringArray(line, lineNumber);
                if (entries.length != columns.size()) {
                    throw new IllegalArgumentException(
                        format(
                            null,
                            "Error line [{}]: Incorrect number of entries; expected [{}] but found [{}]",
                            lineNumber,
                            columns.size(),
                            entries.length
                        )
                    );
                }
                for (int i = 0; i < entries.length; i++) {
                    Column column = columns.get(i);
                    column.values.add(parseCsvLiteralString(column.type, entries[i]));
                }
            }
            return new Result(columns);
        }
    }

    private static Result buildResultFromMemory(SimSchema schema, List<Map<String, Object>> data) {
        // ES|QL returns integers as Long internally, so promote Integer values to match
        return new Result(schema.columns().stream().map(col -> new Column(col.name(), col.type(), data.stream().map(row -> {
            Object v = row.get(col.name());
            return v instanceof Integer i ? i.longValue() : v;
        }).toList())).toList());
    }

    private Result visit(EsRelation relation) {
        String pattern = relation.indexPattern();
        if (schema != null && data != null && pattern.equals(schema.indexName())) {
            return buildResultFromMemory(schema, data);
        }
        throw new UnsupportedOperationException(Strings.format("EsRelation with index [%s] not backed by in-memory data", pattern));
    }

    private Result visit(Keep keep) throws IOException {
        Result childResult = simulate(keep.child());
        var newColumns = new ArrayList<Column>();
        for (var projection : keep.projections()) {
            newColumns.add(childResult.getColumn(projection.name()));
        }
        if (activeBug == SimBug.KEEP_DROPS_FIRST && newColumns.isEmpty() == false) {
            newColumns.removeFirst();
        }
        return new Result(newColumns);
    }

    private Result visit(Drop drop) throws IOException {
        Result childResult = simulate(drop.child());
        var newColumns = new ArrayList<>(childResult.columns);
        Set<String> names = drop.removals().stream().map(NamedExpression::name).collect(Collectors.toSet());
        newColumns.removeIf(column -> names.contains(column.name));
        return new Result(newColumns);
    }

    private Result visit(Eval eval) throws IOException {
        Result childResult = simulate(eval.child());
        var newColumns = new ArrayList<Column>();
        for (var expression : eval.expressions()) {
            if (expression instanceof Alias alias) {
                newColumns.add(childResult.evaluate(alias.child(), activeBug).toNamed(alias.name()));
            } else {
                throw new UnsupportedOperationException(
                    Strings.format("Eval expression [%s] is not an alias, but a %s", expression, expression.getClass())
                );
            }
        }
        return new Result(deduplicateKeepLast(CollectionUtils.concatLists(childResult.columns, newColumns)));
    }

    private Result visit(Filter filter) throws IOException {
        Result childResult = simulate(filter.child());
        UnnamedColumn cond = childResult.evaluate(filter.condition(), activeBug);
        var mask = IntStream.range(0, cond.values.size())
            .filter(i -> Boolean.TRUE.equals(cond.values.get(i)) != (activeBug == SimBug.WHERE_INVERTED))
            .toArray();
        return new Result(
            childResult.columns.stream().map(c -> new Column(c.name, c.type, Arrays.stream(mask).mapToObj(c.values::get).toList())).toList()
        );
    }

    private Result visit(Limit limit) throws IOException {
        Result childResult = simulate(limit.child());
        int n = ((Number) ((Literal) limit.limit()).value()).intValue() + (activeBug == SimBug.LIMIT_OFF_BY_ONE ? 1 : 0);
        int actual = Math.min(n, childResult.numRows());
        return new Result(childResult.columns.stream().map(c -> new Column(c.name, c.type, c.values.subList(0, actual))).toList());
    }

    private Result visit(OrderBy orderBy) throws IOException {
        Result childResult = simulate(orderBy.child());
        int numRows = childResult.numRows();
        Integer[] indices = IntStream.range(0, numRows).boxed().toArray(Integer[]::new);
        // Pre-evaluate order expressions to column values
        List<Order> orders = orderBy.order();
        List<List<Object>> orderValues = orders.stream().map(o -> childResult.evaluate(o.child(), activeBug).values).toList();
        Arrays.sort(indices, (a, b) -> {
            for (int i = 0; i < orders.size(); i++) {
                Object valA = orderValues.get(i).get(a);
                Object valB = orderValues.get(i).get(b);
                boolean asc = (orders.get(i).direction() == Order.OrderDirection.ASC) != (activeBug == SimBug.SORT_REVERSED);
                int cmp = compareNullSafe(valA, valB, asc);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        });
        return new Result(
            childResult.columns.stream().map(c -> new Column(c.name, c.type, Arrays.stream(indices).map(c.values::get).toList())).toList()
        );
    }

    private Result visit(InlineStats inlineStats) throws IOException {
        if (activeBug == SimBug.INLINE_STATS_DROPS_ROWS) {
            return visit(inlineStats.aggregate());
        }
        var aggregate = inlineStats.aggregate();
        Result childResult = simulate(aggregate.child());
        int numRows = childResult.numRows();
        var groups = buildGroups(aggregate, childResult, numRows);
        // Build aggregate columns, broadcasting per-group values to each original row.
        // Deduplicate by name (keep last): matches ES mergeOutputExpressions semantics where
        // the last entry wins. For INLINE STATS, grouping keys appear after aggregate functions
        // in the aggregates list, so the grouping key's original child value takes precedence
        // over an aggregate output with the same name.
        List<Column> allAggColumns = aggregate.aggregates().stream().map(namedExpr -> {
            Expression unwrapped = Alias.unwrap(namedExpr);
            if (unwrapped instanceof AggregateFunction aggFunc) {
                Object[] broadcast = new Object[numRows];
                for (var entry : groups.entrySet()) {
                    Object value = computeAggregate(aggFunc, childResult, entry.getValue());
                    for (int idx : entry.getValue()) {
                        broadcast[idx] = value;
                    }
                }
                return new Column(namedExpr.name(), aggFunc.dataType(), Arrays.asList(broadcast));
            }
            UnnamedColumn col = childResult.evaluate(unwrapped, activeBug);
            return new Column(namedExpr.name(), col.type(), col.values());
        }).toList();
        List<Column> aggColumns = deduplicateKeepLast(allAggColumns);
        // Merge: child columns not in aggregate output, then aggregate columns
        var aggNames = aggColumns.stream().map(Column::name).collect(Collectors.toSet());
        var kept = childResult.columns().stream().filter(c -> aggNames.contains(c.name()) == false).toList();
        return new Result(CollectionUtils.concatLists(kept, aggColumns));
    }

    private Result visit(Aggregate aggregate) throws IOException {
        Result childResult = simulate(aggregate.child());
        var groups = buildGroups(aggregate, childResult, childResult.numRows());
        // Deduplicate by name (keep last): matches ES mergeOutputExpressions semantics.
        // Grouping keys appear after aggregate functions in the aggregates list, so the
        // grouping key value takes precedence over an aggregate output with the same name.
        List<Column> allCols = aggregate.aggregates().stream().map(namedExpr -> {
            Expression unwrapped = Alias.unwrap(namedExpr);
            if (unwrapped instanceof AggregateFunction aggFunc) {
                return new Column(
                    namedExpr.name(),
                    aggFunc.dataType(),
                    groups.values().stream().map(indices -> computeAggregate(aggFunc, childResult, indices)).toList()
                );
            }
            UnnamedColumn col = childResult.evaluate(unwrapped, activeBug);
            return new Column(
                namedExpr.name(),
                col.type,
                groups.values().stream().map(indices -> col.values.get(indices.getFirst())).toList()
            );
        }).toList();
        return new Result(deduplicateKeepLast(allCols));
    }

    private LinkedHashMap<List<Object>, List<Integer>> buildGroups(Aggregate aggregate, Result childResult, int numRows) {
        var groups = new LinkedHashMap<List<Object>, List<Integer>>();
        if (aggregate.groupings().isEmpty()) {
            groups.put(List.of(), IntStream.range(0, numRows).boxed().toList());
        } else {
            for (int i = 0; i < numRows; i++) {
                int row = i;
                var key = aggregate.groupings().stream().map(g -> childResult.evaluate(g, activeBug).values.get(row)).toList();
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            }
        }
        return groups;
    }

    /** Keeps only the last column for each name, matching ES mergeOutputExpressions semantics. */
    private static List<Column> deduplicateKeepLast(List<Column> columns) {
        var lastPositions = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < columns.size(); i++) {
            lastPositions.put(columns.get(i).name(), i);
        }
        return IntStream.range(0, columns.size())
            .filter(i -> lastPositions.get(columns.get(i).name()) == i)
            .mapToObj(columns::get)
            .toList();
    }

    private Object computeAggregate(AggregateFunction aggFunc, Result childResult, List<Integer> indices) {
        return switch (aggFunc) {
            case Count count -> {
                long nonNullCount = nonNullValues(indices, childResult, count.field(), activeBug).size();
                yield nonNullCount + (activeBug == SimBug.STATS_COUNT_OFF_BY_ONE ? 1 : 0);
            }
            case Sum sum -> {
                List<Object> nonNull = nonNullValues(indices, childResult, sum.field(), activeBug);
                yield nonNull.isEmpty() ? null : nonNull.stream().mapToLong(Simulator::toLong).sum();
            }
            case Min min -> {
                List<Object> nonNull = nonNullValues(indices, childResult, min.field(), activeBug);
                // ES constant-folds MIN(constant) to the constant — return it even for empty groups.
                yield nonNull.isEmpty() ? evaluateConstant(min.field()) : nonNull.stream().mapToLong(Simulator::toLong).min().orElseThrow();
            }
            case Max max -> {
                List<Object> nonNull = nonNullValues(indices, childResult, max.field(), activeBug);
                // ES constant-folds MAX(constant) to the constant — return it even for empty groups.
                yield nonNull.isEmpty() ? evaluateConstant(max.field()) : nonNull.stream().mapToLong(Simulator::toLong).max().orElseThrow();
            }
            default -> throw new UnsupportedOperationException(Strings.format("Unsupported aggregate function: %s", aggFunc.getClass()));
        };
    }

    private static List<Object> nonNullValues(List<Integer> indices, Result childResult, Expression field, SimBug activeBug) {
        UnnamedColumn col = childResult.evaluate(field, activeBug);
        return indices.stream().map(col.values::get).filter(Objects::nonNull).toList();
    }

    /** Evaluates a constant expression (no {@link Attribute} refs), returning {@code null} for non-constant expressions. Mirrors ES constant-folding: {@code MIN(9)} over zero rows returns {@code 9}. */
    @Nullable
    private static Object evaluateConstant(Expression expr) {
        return switch (expr) {
            case Literal lit -> normalizeObject(lit.value());
            case ArithmeticOperation op -> {
                Object l = evaluateConstant(op.left());
                Object r = evaluateConstant(op.right());
                if (l == null || r == null) {
                    yield null;
                }
                Long result = switch (op) {
                    case Add ignored -> toLong(l) + toLong(r);
                    case Sub ignored -> toLong(l) - toLong(r);
                    case Mul ignored -> toLong(l) * toLong(r);
                    case Div ignored -> toLong(r) == 0 ? null : toLong(l) / toLong(r);
                    case Mod ignored -> toLong(r) == 0 ? null : toLong(l) % toLong(r);
                    default -> throw new UnsupportedOperationException(
                        Strings.format("Unsupported arithmetic in evaluateConstant: %s", op.getClass())
                    );
                };
                yield result == null || result < Integer.MIN_VALUE || result > Integer.MAX_VALUE ? null : result;
            }
            case Neg neg -> {
                Object v = evaluateConstant(neg.field());
                if (v == null) {
                    yield null;
                }
                long result = -toLong(v);
                yield result < Integer.MIN_VALUE || result > Integer.MAX_VALUE ? null : result;
            }
            default -> null;
        };
    }

    public record Result(List<Column> columns) {
        public Result(Column first, Column... rest) {
            this(Arrays.asList(ArrayUtils.prepend(first, rest)));
        }

        int numRows() {
            return columns.isEmpty() ? 0 : columns.getFirst().values.size();
        }

        public Column getColumn(String name) {
            // Last match wins, respecting column shadowing
            return columns.stream()
                .filter(c -> c.name().equals(name))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalArgumentException("Column not found: " + name));
        }

        public UnnamedColumn evaluate(Expression expression, SimBug activeBug) {
            return switch (expression) {
                case Attribute attr -> new UnnamedColumn(getColumn(attr.name()));
                case Literal literal -> new UnnamedColumn(
                    literal.dataType(),
                    Collections.nCopies(numRows(), normalizeObject(literal.value()))
                );
                case Add add -> evalBinaryLong(
                    add.left(),
                    add.right(),
                    activeBug,
                    (l, r) -> activeBug == SimBug.ADD_IS_SUB ? l - r : l + r
                );
                case Sub sub -> evalBinaryLong(sub.left(), sub.right(), activeBug, (l, r) -> l - r);
                case Mul mul -> evalBinaryLong(mul.left(), mul.right(), activeBug, (l, r) -> l * r);
                case Div div -> evalBinaryLong(div.left(), div.right(), activeBug, (l, r) -> r == 0 ? null : l / r);
                case Mod mod -> evalBinaryLong(mod.left(), mod.right(), activeBug, (l, r) -> r == 0 ? null : l % r);
                case Neg neg -> {
                    UnnamedColumn input = evaluate(neg.field(), activeBug);
                    yield new UnnamedColumn(input.type, input.values.stream().<Object>map(o -> {
                        if (o == null) {
                            return null;
                        }
                        long result = -toLong(o);
                        return input.type == DataType.INTEGER && (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) ? null : result;
                    }).toList());
                }
                case GreaterThan gt -> evalComparison(gt.left(), gt.right(), activeBug, cmp -> cmp > 0);
                case LessThan lt -> evalComparison(lt.left(), lt.right(), activeBug, cmp -> cmp < 0);
                case GreaterThanOrEqual gte -> evalComparison(gte.left(), gte.right(), activeBug, cmp -> cmp >= 0);
                case LessThanOrEqual lte -> evalComparison(lte.left(), lte.right(), activeBug, cmp -> cmp <= 0);
                case Equals eq -> evalComparison(eq.left(), eq.right(), activeBug, cmp -> cmp == 0);
                case NotEquals neq -> evalComparison(neq.left(), neq.right(), activeBug, cmp -> cmp != 0);
                case Trim trim -> evalUnaryString(trim.field(), activeBug, String::trim);
                case ToUpper toUpper -> evalUnaryString(
                    toUpper.field(),
                    activeBug,
                    s -> activeBug == SimBug.TO_UPPER_IS_TO_LOWER ? s.toLowerCase(Locale.ROOT) : s.toUpperCase(Locale.ROOT)
                );
                case ToLower toLower -> evalUnaryString(toLower.field(), activeBug, s -> s.toLowerCase(Locale.ROOT));
                case Reverse rev -> evalUnaryString(rev.field(), activeBug, s -> new StringBuilder(s).reverse().toString());
                case Length length -> {
                    UnnamedColumn input = evaluate(length.field(), activeBug);
                    yield new UnnamedColumn(
                        DataType.INTEGER,
                        input.values.stream().<Object>map(o -> o == null ? null : (long) o.toString().length()).toList()
                    );
                }
                case Concat concat -> {
                    List<UnnamedColumn> args = concat.children().stream().map(child -> evaluate(child, activeBug)).toList();
                    int numRows = args.getFirst().values.size();
                    yield new UnnamedColumn(DataType.KEYWORD, IntStream.range(0, numRows).<Object>mapToObj(i -> {
                        StringBuilder sb = new StringBuilder();
                        for (UnnamedColumn arg : args) {
                            Object v = arg.values.get(i);
                            if (v == null) {
                                return null;
                            }
                            sb.append(v);
                        }
                        return sb.toString();
                    }).toList());
                }
                case Left left -> {
                    UnnamedColumn strCol = evaluate(left.children().get(0), activeBug);
                    UnnamedColumn lenCol = evaluate(left.children().get(1), activeBug);
                    yield new UnnamedColumn(DataType.KEYWORD, IntStream.range(0, strCol.values.size()).mapToObj(i -> {
                        Object strVal = strCol.values.get(i);
                        Object lenVal = lenCol.values.get(i);
                        if (strVal == null || lenVal == null) {
                            return null;
                        }
                        String str = strVal.toString();
                        int len = ((Number) lenVal).intValue();
                        return (Object) str.substring(0, Math.min(Math.max(0, len), str.length()));
                    }).toList());
                }
                case Right right -> {
                    UnnamedColumn strCol = evaluate(right.children().get(0), activeBug);
                    UnnamedColumn lenCol = evaluate(right.children().get(1), activeBug);
                    yield new UnnamedColumn(DataType.KEYWORD, IntStream.range(0, strCol.values.size()).mapToObj(i -> {
                        Object strVal = strCol.values.get(i);
                        Object lenVal = lenCol.values.get(i);
                        if (strVal == null || lenVal == null) {
                            return null;
                        }
                        String str = strVal.toString();
                        int len = ((Number) lenVal).intValue();
                        return (Object) str.substring(Math.max(0, str.length() - len));
                    }).toList());
                }
                case StartsWith sw -> {
                    UnnamedColumn strCol = evaluate(sw.children().get(0), activeBug);
                    UnnamedColumn prefixCol = evaluate(sw.children().get(1), activeBug);
                    yield new UnnamedColumn(DataType.BOOLEAN, IntStream.range(0, strCol.values.size()).mapToObj(i -> {
                        Object strVal = strCol.values.get(i);
                        Object prefixVal = prefixCol.values.get(i);
                        if (strVal == null || prefixVal == null) {
                            return null;
                        }
                        return (Object) strVal.toString().startsWith(prefixVal.toString());
                    }).toList());
                }
                case EndsWith ew -> {
                    UnnamedColumn strCol = evaluate(ew.children().get(0), activeBug);
                    UnnamedColumn suffixCol = evaluate(ew.children().get(1), activeBug);
                    yield new UnnamedColumn(DataType.BOOLEAN, IntStream.range(0, strCol.values.size()).mapToObj(i -> {
                        Object strVal = strCol.values.get(i);
                        Object suffixVal = suffixCol.values.get(i);
                        return strVal == null || suffixVal == null ? null : (Object) strVal.toString().endsWith(suffixVal.toString());
                    }).toList());
                }
                case Substring substring -> {
                    UnnamedColumn strCol = evaluate(substring.children().get(0), activeBug);
                    UnnamedColumn startCol = evaluate(substring.children().get(1), activeBug);
                    UnnamedColumn lenCol = substring.children().size() > 2 ? evaluate(substring.children().get(2), activeBug) : null;
                    yield new UnnamedColumn(DataType.KEYWORD, IntStream.range(0, strCol.values.size()).mapToObj(i -> {
                        Object strVal = strCol.values.get(i);
                        Object startVal = startCol.values.get(i);
                        if (strVal == null || startVal == null) {
                            return null;
                        }
                        String str = strVal.toString();
                        int start = ((Number) startVal).intValue();
                        // 1-based start; negative start = from end; 0 = beginning
                        int indexStart = start > 0 ? start - 1 : start < 0 ? Math.max(0, str.length() + start) : 0;
                        indexStart = Math.min(indexStart, str.length());
                        if (lenCol == null) {
                            return str.substring(indexStart);
                        }
                        Object lenVal = lenCol.values.get(i);
                        if (lenVal == null) {
                            return null;
                        }
                        int len = ((Number) lenVal).intValue();
                        int indexEnd = Math.min(str.length(), indexStart + Math.max(0, len));
                        return (Object) str.substring(indexStart, indexEnd);
                    }).toList());
                }
                default -> throw new UnsupportedOperationException(Strings.format("Unsupported Expression in evaluate: [%s]", expression));
            };
        }

        private UnnamedColumn evalBinaryLong(
            Expression leftExpr,
            Expression rightExpr,
            SimBug activeBug,
            BiFunction<Long, Long, Object> op
        ) {
            var left = evaluate(leftExpr, activeBug);
            var right = evaluate(rightExpr, activeBug);
            // ES|QL uses 32-bit integer arithmetic and returns null on overflow; LONG arithmetic can overflow too but is
            // extremely unlikely with the small values in our generated data, so we only check for INTEGER overflow here.
            boolean integerArithmetic = left.type == DataType.INTEGER && right.type == DataType.INTEGER;
            return new UnnamedColumn(left.type, IntStream.range(0, left.values.size()).mapToObj(i -> {
                Object l = left.values.get(i);
                Object r = right.values.get(i);
                if (l == null || r == null) {
                    return null;
                }
                Object result = op.apply(toLong(l), toLong(r));
                if (result == null) {
                    return null;
                }
                long longResult = ((Number) result).longValue();
                if (integerArithmetic && (longResult < Integer.MIN_VALUE || longResult > Integer.MAX_VALUE)) {
                    return null;
                }
                return (Object) longResult;
            }).toList());
        }

        private UnnamedColumn evalUnaryString(Expression fieldExpr, SimBug activeBug, UnaryOperator<String> op) {
            UnnamedColumn input = evaluate(fieldExpr, activeBug);
            return new UnnamedColumn(
                DataType.KEYWORD,
                input.values.stream().<Object>map(o -> o == null ? null : op.apply(o.toString())).toList()
            );
        }

        /** Evaluates a numeric comparison expression (both sides must be convertible to long). */
        private UnnamedColumn evalComparison(Expression leftExpr, Expression rightExpr, SimBug activeBug, IntPredicate test) {
            var left = evaluate(leftExpr, activeBug);
            var right = evaluate(rightExpr, activeBug);
            return new UnnamedColumn(DataType.BOOLEAN, IntStream.range(0, left.values.size()).mapToObj(i -> {
                Object l = left.values.get(i);
                Object r = right.values.get(i);
                if (l == null || r == null) {
                    return null;
                }
                return (Object) test.test(Long.compare(toLong(l), toLong(r)));
            }).toList());
        }
    }

    /** Null-safe comparison for sort values. Follows ES|QL default null ordering: nulls last for ASC, nulls first for DESC. */
    @SuppressWarnings("unchecked")
    private static int compareNullSafe(Object a, Object b, boolean asc) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return asc ? 1 : -1;
        }
        if (b == null) {
            return asc ? -1 : 1;
        }
        int cmp = ((Comparable<Object>) a).compareTo(b);
        return asc ? cmp : -cmp;
    }

    private static long toLong(Object object) {
        return ((Number) object).longValue();
    }

    public record Column(String name, DataType type, List<Object> values) {}

    /** An unnamed column of values, e.g. for literals or inline expressions. */
    public record UnnamedColumn(DataType type, List<Object> values) {
        public UnnamedColumn(Column column) {
            this(column.type, column.values);
        }

        public Column toNamed(String name) {
            return new Column(name, type, values);
        }
    }

    private static Object normalizeObject(Object object) {
        return object instanceof BytesRef br ? getString(br) : object;
    }

    private static String getString(BytesRef br) {
        try {
            return br.utf8ToString();
        } catch (@SuppressWarnings("unused") AssertionError | IllegalArgumentException t) {
            // If BytesRef isn't actually UTF8, or it's e.g. a
            // prefix of UTF8 that ends mid-unicode-char, we
            // fall back to hex:
            return br.toString();
        }
    }

    private static Object parseCsvLiteralString(DataType dataType, String string) {
        return switch (dataType) {
            case TEXT, KEYWORD, IP -> string;
            case INTEGER, LONG -> Long.parseLong(string);
            case DOUBLE, FLOAT -> Double.parseDouble(string);
            case DATETIME -> CsvTestUtils.Type.DATETIME.convert(string);
            default -> throw new UnsupportedOperationException("Unsupported data type for CSV literal string parsing: " + dataType);
        };
    }
}
