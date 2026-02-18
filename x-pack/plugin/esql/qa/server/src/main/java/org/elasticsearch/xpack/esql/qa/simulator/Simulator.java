/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.Strings;
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
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Div;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.LongBinaryOperator;
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
                return new Column(alias.name(), alias.dataType(), List.of(l.value()));
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

        // If in-memory data is provided for this index, use it directly
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
            // the schema row
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
        var childResult = simulate(keep.child());
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
        var childResult = simulate(drop.child());
        var newColumns = new ArrayList<>(childResult.columns);
        Set<String> names = drop.removals().stream().map(NamedExpression::name).collect(Collectors.toSet());
        newColumns.removeIf(column -> names.contains(column.name));
        return new Result(newColumns);
    }

    private Result visit(Eval eval) throws IOException {
        var childResult = simulate(eval.child());
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
        return childResult.append(newColumns);
    }

    private Result visit(Filter filter) throws IOException {
        var childResult = simulate(filter.child());
        var cond = childResult.evaluate(filter.condition(), activeBug);
        var mask = IntStream.range(0, cond.values.size())
            .filter(i -> ((boolean) cond.values.get(i)) != (activeBug == SimBug.WHERE_INVERTED))
            .toArray();
        return new Result(
            childResult.columns.stream()
                .map(c -> new Column(c.name, c.type, Arrays.stream(mask).mapToObj(i -> c.values.get(i)).toList()))
                .toList()
        );
    }

    private Result visit(Limit limit) throws IOException {
        var childResult = simulate(limit.child());
        int n = ((Number) ((Literal) limit.limit()).value()).intValue() + (activeBug == SimBug.LIMIT_OFF_BY_ONE ? 1 : 0);
        int actual = Math.min(n, childResult.numRows());
        return new Result(childResult.columns.stream().map(c -> new Column(c.name, c.type, c.values.subList(0, actual))).toList());
    }

    @SuppressWarnings("unchecked")
    private Result visit(OrderBy orderBy) throws IOException {
        var childResult = simulate(orderBy.child());
        int numRows = childResult.numRows();
        Integer[] indices = IntStream.range(0, numRows).boxed().toArray(Integer[]::new);
        // Pre-evaluate order expressions to column values
        List<Order> orders = orderBy.order();
        List<List<Object>> orderValues = orders.stream().map(o -> childResult.evaluate(o.child(), activeBug).values).toList();
        Arrays.sort(indices, (a, b) -> {
            for (int i = 0; i < orders.size(); i++) {
                int cmp = ((Comparable<Object>) orderValues.get(i).get(a)).compareTo(orderValues.get(i).get(b));
                if (cmp != 0) {
                    boolean asc = (orders.get(i).direction() == Order.OrderDirection.ASC) != (activeBug == SimBug.SORT_REVERSED);
                    return asc ? cmp : -cmp;
                }
            }
            return 0;
        });
        return new Result(
            childResult.columns.stream()
                .map(c -> new Column(c.name, c.type, Arrays.stream(indices).map(i -> c.values.get(i)).toList()))
                .toList()
        );
    }

    private Result visit(InlineStats inlineStats) throws IOException {
        if (activeBug == SimBug.INLINE_STATS_DROPS_ROWS) {
            return visit(inlineStats.aggregate());
        }
        var aggregate = inlineStats.aggregate();
        var childResult = simulate(aggregate.child());
        int numRows = childResult.numRows();
        var groups = buildGroups(aggregate, childResult, numRows);
        // Build aggregate columns, broadcasting per-group values to each original row.
        // Deduplicate by name (keep first): when a grouping key shares a name with an aggregate output.
        var seenInline = new HashSet<String>();
        var aggColumns = aggregate.aggregates().stream().map(namedExpr -> {
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
            var col = childResult.evaluate(unwrapped, activeBug);
            return new Column(namedExpr.name(), col.type(), col.values());
        }).filter(col -> seenInline.add(col.name())).toList();
        // Merge: child columns not in aggregate output, then aggregate columns
        var aggNames = aggColumns.stream().map(Column::name).collect(Collectors.toSet());
        var kept = childResult.columns().stream().filter(c -> aggNames.contains(c.name()) == false).toList();
        return new Result(CollectionUtils.concatLists(kept, aggColumns));
    }

    private Result visit(Aggregate aggregate) throws IOException {
        var childResult = simulate(aggregate.child());
        var groups = buildGroups(aggregate, childResult, childResult.numRows());
        // Deduplicate by name (keep first): when a grouping key has the same name as an aggregate
        // output (e.g., STATS s1 = COUNT(...) BY s1), ES produces one column, not two.
        var seen = new HashSet<String>();
        return new Result(aggregate.aggregates().stream().map(namedExpr -> {
            Expression unwrapped = Alias.unwrap(namedExpr);
            if (unwrapped instanceof AggregateFunction aggFunc) {
                return new Column(
                    namedExpr.name(),
                    aggFunc.dataType(),
                    groups.values().stream().map(indices -> computeAggregate(aggFunc, childResult, indices)).toList()
                );
            }
            var col = childResult.evaluate(unwrapped, activeBug);
            return new Column(
                namedExpr.name(),
                col.type,
                groups.values().stream().map(indices -> col.values.get(indices.getFirst())).toList()
            );
        }).filter(col -> seen.add(col.name())).toList());
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

    private Object computeAggregate(AggregateFunction aggFunc, Result data, List<Integer> indices) {
        return switch (aggFunc) {
            // COUNT returns 0 for empty groups (not null), matching ES semantics.
            case Count ignored -> (long) indices.size() + (activeBug == SimBug.STATS_COUNT_OFF_BY_ONE ? 1 : 0);
            case Sum sum -> indices.isEmpty()
                ? null
                : indices.stream().mapToLong(i -> toLong(data.evaluate(sum.field(), activeBug).values.get(i))).sum();
            case Min min -> indices.isEmpty()
                ? null
                : indices.stream().mapToLong(i -> toLong(data.evaluate(min.field(), activeBug).values.get(i))).min().orElseThrow();
            case Max max -> indices.isEmpty()
                ? null
                : indices.stream().mapToLong(i -> toLong(data.evaluate(max.field(), activeBug).values.get(i))).max().orElseThrow();
            default -> throw new UnsupportedOperationException(Strings.format("Unsupported aggregate function: %s", aggFunc.getClass()));
        };
    }

    public record Result(List<Column> columns) {
        int numRows() {
            return columns.isEmpty() ? 0 : columns.getFirst().values.size();
        }

        public Result append(List<Column> newColumns) {
            return new Result(CollectionUtils.concatLists(columns, newColumns));
        }

        public Column getColumn(String name) {
            return columns.stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Column not found: " + name));
        }

        public UnnamedColumn evaluate(Expression expression, SimBug activeBug) {
            return switch (expression) {
                case Attribute attr -> new UnnamedColumn(getColumn(attr.name()));
                case Literal literal -> new UnnamedColumn(
                    literal.dataType(),
                    IntStream.range(0, numRows()).mapToObj(unused -> normalizeObject(literal.value())).toList()
                );
                case Add add -> evalBinaryLong(
                    add.left(),
                    add.right(),
                    activeBug,
                    (l, r) -> activeBug == SimBug.ADD_IS_SUB ? l - r : l + r
                );
                case Sub sub -> evalBinaryLong(sub.left(), sub.right(), activeBug, (l, r) -> l - r);
                case Mul mul -> evalBinaryLong(mul.left(), mul.right(), activeBug, (l, r) -> l * r);
                case Div div -> evalBinaryLong(div.left(), div.right(), activeBug, (l, r) -> l / r);
                case GreaterThan gt -> evalComparison(gt.left(), gt.right(), activeBug, cmp -> cmp > 0);
                case LessThan lt -> evalComparison(lt.left(), lt.right(), activeBug, cmp -> cmp < 0);
                default -> throw new UnsupportedOperationException(Strings.format("Unsupported Expression in evaluate: [%s]", expression));
            };
        }

        private UnnamedColumn evalBinaryLong(Expression leftExpr, Expression rightExpr, SimBug activeBug, LongBinaryOperator op) {
            var left = evaluate(leftExpr, activeBug);
            var right = evaluate(rightExpr, activeBug);
            return new UnnamedColumn(
                left.type,
                IntStream.range(0, left.values.size())
                    .mapToObj(i -> (Object) op.applyAsLong(toLong(left.values.get(i)), toLong(right.values.get(i))))
                    .toList()
            );
        }

        private UnnamedColumn evalComparison(Expression leftExpr, Expression rightExpr, SimBug activeBug, IntPredicate test) {
            var left = evaluate(leftExpr, activeBug);
            var right = evaluate(rightExpr, activeBug);
            return new UnnamedColumn(
                DataType.BOOLEAN,
                IntStream.range(0, left.values.size())
                    .mapToObj(i -> (Object) test.test(Long.compare(toLong(left.values.get(i)), toLong(right.values.get(i)))))
                    .toList()
            );
        }
    }

    private static long toLong(Object object) {
        return ((Number) object).longValue();
    }

    public record Column(String name, DataType type, List<Object> values) {}

    // E.g., for literals or inline expressions.
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
