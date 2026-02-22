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
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.ArithmeticOperation;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Div;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mod;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Neg;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
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
import org.elasticsearch.xpack.esql.plan.logical.Row;
import org.elasticsearch.xpack.esql.plan.logical.UnresolvedRelation;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.elasticsearch.xpack.esql.CsvTestUtils.multiValuesAwareCsvToStringArray;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.reader;

/**
 * Evaluates a {@link LogicalPlan} against in-memory or CSV-backed data, producing columnar results.
 * Acts as a reference implementation for ES|QL semantics: property tests compare its output
 * against a real Elasticsearch cluster to detect divergences.
 */
class Simulator {
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

    static Simulator singleRow(SimSchema schema, Map<String, Object> row) {
        return new Simulator(schema, List.of(row));
    }

    Simulator(@Nullable SimSchema schema, @Nullable List<Map<String, Object>> data, SimBug activeBug) {
        this.schema = schema;
        this.data = data;
        this.activeBug = activeBug;
    }

    public Result simulate(LogicalPlan plan) throws IOException {
        return switch (plan) {
            case Row row -> visit(row);
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

    private Result visit(Row row) {
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

        URL file = Simulator.class.getResource("/data/" + pattern + ".csv");
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
                        Strings.format(
                            "Error line [%d]: Incorrect number of entries; expected [%d] but found [%d]",
                            lineNumber,
                            columns.size(),
                            entries.length
                        )
                    );
                }
                for (int i = 0; i < entries.length; i++) {
                    Column column = columns.get(i);
                    column.values().add(parseCsvLiteralString(column.type(), entries[i]));
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
        List<Column> newColumns = keep.projections().stream().map(p -> childResult.getColumn(p.name())).toList();
        if (activeBug == SimBug.KEEP_DROPS_FIRST && newColumns.isEmpty() == false) {
            newColumns = new ArrayList<>(newColumns);
            newColumns.removeFirst();
        }
        return new Result(newColumns);
    }

    private Result visit(Drop drop) throws IOException {
        Result childResult = simulate(drop.child());
        var newColumns = new ArrayList<>(childResult.columns());
        Set<String> names = drop.removals().stream().map(NamedExpression::name).collect(Collectors.toSet());
        newColumns.removeIf(column -> names.contains(column.name()));
        return new Result(newColumns);
    }

    private Result visit(Eval eval) throws IOException {
        Result childResult = simulate(eval.child());
        List<Column> newColumns = eval.expressions().stream().map(expression -> {
            if (expression instanceof Alias alias) {
                return childResult.evaluate(alias.child(), activeBug).toNamed(alias.name());
            }
            throw new UnsupportedOperationException(
                Strings.format("Eval expression [%s] is not an alias, but a %s", expression, expression.getClass())
            );
        }).toList();
        return new Result(deduplicateKeepLast(CollectionUtils.concatLists(childResult.columns(), newColumns)));
    }

    private Result visit(Filter filter) throws IOException {
        Result childResult = simulate(filter.child());
        UnnamedColumn cond = childResult.evaluate(filter.condition(), activeBug);
        int[] mask = IntStream.range(0, cond.values().size())
            .filter(i -> Boolean.TRUE.equals(cond.values().get(i)) != (activeBug == SimBug.WHERE_INVERTED))
            .toArray();
        return new Result(
            childResult.columns()
                .stream()
                .map(c -> new Column(c.name(), c.type(), Arrays.stream(mask).mapToObj(c.values()::get).toList()))
                .toList()
        );
    }

    private Result visit(Limit limit) throws IOException {
        Result childResult = simulate(limit.child());
        int n = ((Number) ((Literal) limit.limit()).value()).intValue() + (activeBug == SimBug.LIMIT_OFF_BY_ONE ? 1 : 0);
        int actual = Math.min(n, childResult.numRows());
        return new Result(childResult.columns().stream().map(c -> new Column(c.name(), c.type(), c.values().subList(0, actual))).toList());
    }

    private Result visit(OrderBy orderBy) throws IOException {
        Result childResult = simulate(orderBy.child());
        int numRows = childResult.numRows();
        Integer[] indices = IntStream.range(0, numRows).boxed().toArray(Integer[]::new);
        // Pre-evaluate order expressions to column values
        List<Order> orders = orderBy.order();
        List<List<Object>> orderValues = orders.stream().map(o -> childResult.evaluate(o.child(), activeBug).values()).toList();
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
            childResult.columns()
                .stream()
                .map(c -> new Column(c.name(), c.type(), Arrays.stream(indices).map(c.values()::get).toList()))
                .toList()
        );
    }

    private Result visit(InlineStats inlineStats) throws IOException {
        if (activeBug == SimBug.INLINE_STATS_DROPS_ROWS) {
            return visit(inlineStats.aggregate());
        }
        Aggregate innerAggregate = inlineStats.aggregate();
        Result childResult = simulate(innerAggregate.child());
        int numRows = childResult.numRows();
        var groups = buildGroups(innerAggregate, childResult);
        // Deduplicate by name (keep last): matches ES mergeOutputExpressions semantics where
        // the last entry wins. For INLINE STATS, grouping keys appear after aggregate functions
        // in the aggregates list, so the grouping key's original child value takes precedence
        // over an aggregate output with the same name.
        List<Column> allAggColumns = innerAggregate.aggregates().stream().map(namedExpr -> {
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
        Set<String> aggNames = aggColumns.stream().map(Column::name).collect(Collectors.toSet());
        List<Column> kept = childResult.columns().stream().filter(c -> aggNames.contains(c.name()) == false).toList();
        return new Result(CollectionUtils.concatLists(kept, aggColumns));
    }

    private Result visit(Aggregate aggregate) throws IOException {
        Result childResult = simulate(aggregate.child());
        var groups = buildGroups(aggregate, childResult);
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
                col.type(),
                groups.values().stream().map(indices -> col.values().get(indices.getFirst())).toList()
            );
        }).toList();
        return new Result(deduplicateKeepLast(allCols));
    }

    private LinkedHashMap<List<Object>, List<Integer>> buildGroups(Aggregate aggregate, Result childResult) {
        int numRows = childResult.numRows();
        var groups = new LinkedHashMap<List<Object>, List<Integer>>();
        if (aggregate.groupings().isEmpty()) {
            groups.put(List.of(), IntStream.range(0, numRows).boxed().toList());
        } else {
            List<List<Object>> groupingCols = aggregate.groupings().stream().map(g -> childResult.evaluate(g, activeBug).values()).toList();
            for (int i = 0; i < numRows; i++) {
                int row = i;
                List<Object> key = groupingCols.stream().map(col -> col.get(row)).toList();
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
        return indices.stream().map(col.values()::get).filter(Objects::nonNull).toList();
    }

    /**
     * Evaluates a constant expression (no {@link Attribute} refs), returning {@code null} for non-constant expressions.
     * Mirrors ES constant-folding: {@code MIN(9)} over zero rows returns {@code 9}.
     */
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

    static long toLong(Object object) {
        return ((Number) object).longValue();
    }

    record Column(String name, DataType type, List<Object> values) {
        public static Column ofInt(String name, Object... values) {
            return new Column(name, DataType.INTEGER, List.of(values));
        }

        public static Column ofLong(String name, Object... values) {
            return new Column(name, DataType.LONG, List.of(values));
        }

        public static Column ofKeyword(String name, Object... values) {
            return new Column(name, DataType.KEYWORD, List.of(values));
        }

        public static Column ofIp(String name, Object... values) {
            return new Column(name, DataType.IP, List.of(values));
        }

        public static Column ofDatetime(String name, Object... values) {
            return new Column(name, DataType.DATETIME, List.of(values));
        }
    }

    /** An unnamed column of values, e.g. for literals or inline expressions. */
    record UnnamedColumn(DataType type, List<Object> values) {
        public UnnamedColumn(Column column) {
            this(column.type(), column.values());
        }

        public Column toNamed(String name) {
            return new Column(name, type, values);
        }
    }

    static Object normalizeObject(Object object) {
        return object instanceof BytesRef br ? br.utf8ToString() : object;
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
