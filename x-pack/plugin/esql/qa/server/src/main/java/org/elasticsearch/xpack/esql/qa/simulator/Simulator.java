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
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.Limit;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;
import org.elasticsearch.xpack.esql.plan.logical.UnresolvedRelation;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        var columns = new ArrayList<Column>(schema.columns().size());
        for (var col : schema.columns()) {
            var values = new ArrayList<>();
            for (var row : data) {
                Object value = row.get(col.name());
                // Normalize: integers from generated data may be Integer, convert to Long for consistency
                if (value instanceof Integer i) {
                    value = i.longValue();
                }
                values.add(value);
            }
            columns.add(new Column(col.name(), col.type(), values));
        }
        return new Result(columns);
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
        var conditionResult = childResult.evaluate(filter.condition(), activeBug);
        var validIndices = new BitSet();
        for (int i = 0; i < conditionResult.values.size(); i++) {
            if ((boolean) conditionResult.values.get(i)) {
                validIndices.set(i);
            }
        }
        if (activeBug == SimBug.WHERE_INVERTED) {
            validIndices.flip(0, conditionResult.values.size());
        }
        var result = new ArrayList<Column>(childResult.columns.size());
        for (var column : childResult.columns) {
            var newValues = new ArrayList<>();
            for (int i = 0; i < column.values.size(); i++) {
                if (validIndices.get(i)) {
                    newValues.add(column.values.get(i));
                }
            }
            result.add(new Column(column.name, column.type, newValues));
        }
        return new Result(result);
    }

    private Result visit(Limit limit) throws IOException {
        var childResult = simulate(limit.child());
        int n = ((Number) ((Literal) limit.limit()).value()).intValue();
        if (activeBug == SimBug.LIMIT_OFF_BY_ONE) n++;
        int actual = Math.min(n, childResult.columns.getFirst().values.size());
        return new Result(childResult.columns.stream().map(c -> new Column(c.name, c.type, c.values.subList(0, actual))).toList());
    }

    @SuppressWarnings("unchecked")
    private Result visit(OrderBy orderBy) throws IOException {
        var childResult = simulate(orderBy.child());
        int numRows = childResult.columns.getFirst().values.size();
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

    private Result visit(Aggregate aggregate) throws IOException {
        var childResult = simulate(aggregate.child());
        int numRows = childResult.columns.isEmpty() ? 0 : childResult.columns.getFirst().values.size();
        // Build groups: map from group key → row indices (LinkedHashMap preserves insertion order)
        var groups = new LinkedHashMap<List<Object>, List<Integer>>();
        if (aggregate.groupings().isEmpty()) {
            groups.put(List.of(), IntStream.range(0, numRows).boxed().toList());
        } else {
            for (int i = 0; i < numRows; i++) {
                var key = new ArrayList<>();
                for (var groupExpr : aggregate.groupings()) {
                    key.add(childResult.evaluate(groupExpr, activeBug).values.get(i));
                }
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            }
        }
        // Build result columns from aggregates list
        var resultColumns = new ArrayList<Column>();
        for (var namedExpr : aggregate.aggregates()) {
            Expression unwrapped = Alias.unwrap(namedExpr);
            if (unwrapped instanceof AggregateFunction aggFunc) {
                var values = groups.values().stream().map(indices -> computeAggregate(aggFunc, childResult, indices)).toList();
                resultColumns.add(new Column(namedExpr.name(), aggFunc.dataType(), values));
            } else {
                // Grouping key reference
                var col = childResult.evaluate(unwrapped, activeBug);
                var values = groups.values().stream().map(indices -> col.values.get(indices.getFirst())).toList();
                resultColumns.add(new Column(namedExpr.name(), col.type, values));
            }
        }
        return new Result(resultColumns);
    }

    private Object computeAggregate(AggregateFunction aggFunc, Result data, List<Integer> indices) {
        return switch (aggFunc) {
            case Count count -> (long) indices.size();
            case Sum sum -> {
                var values = data.evaluate(sum.field(), activeBug);
                long total = 0;
                for (int idx : indices)
                    total += toLong(values.values.get(idx));
                yield total;
            }
            case Min min -> {
                var values = data.evaluate(min.field(), activeBug);
                long result = Long.MAX_VALUE;
                for (int idx : indices)
                    result = Math.min(result, toLong(values.values.get(idx)));
                yield result;
            }
            case Max max -> {
                var values = data.evaluate(max.field(), activeBug);
                long result = Long.MIN_VALUE;
                for (int idx : indices)
                    result = Math.max(result, toLong(values.values.get(idx)));
                yield result;
            }
            default -> throw new UnsupportedOperationException(Strings.format("Unsupported aggregate function: %s", aggFunc.getClass()));
        };
    }

    public record Result(List<Column> columns) {
        public Result append(ArrayList<Column> newColumns) {
            return new Result(CollectionUtils.concatLists(columns, newColumns));
        }

        public Column getColumn(String leftName) {
            return columns.stream()
                .filter(c -> c.name().equals(leftName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Column not found: " + leftName));
        }

        @SuppressWarnings("unchecked")
        public UnnamedColumn evaluate(Expression expression, SimBug activeBug) {
            switch (expression) {
                case Attribute attr -> {
                    return new UnnamedColumn(getColumn(attr.name()));
                }
                case Literal literal -> {
                    return new UnnamedColumn(
                        literal.dataType(),
                        IntStream.range(0, columns().getFirst().values.size()).mapToObj(unused -> normalizeObject(literal.value())).toList()
                    );
                }
                case Add add -> {
                    var leftColumn = evaluate(add.left(), activeBug);
                    var rightColumn = evaluate(add.right(), activeBug);
                    var values = new ArrayList<>();
                    for (int i = 0; i < leftColumn.values.size(); i++) {
                        long l = toLong(leftColumn.values.get(i));
                        long r = toLong(rightColumn.values.get(i));
                        values.add(activeBug == SimBug.ADD_IS_SUB ? l - r : l + r);
                    }
                    return new UnnamedColumn(leftColumn.type, values);
                }
                case Sub sub -> {
                    var leftColumn = evaluate(sub.left(), activeBug);
                    var rightColumn = evaluate(sub.right(), activeBug);
                    var values = new ArrayList<>();
                    for (int i = 0; i < leftColumn.values.size(); i++) {
                        values.add(toLong(leftColumn.values.get(i)) - toLong(rightColumn.values.get(i)));
                    }
                    return new UnnamedColumn(leftColumn.type, values);
                }
                case Mul mul -> {
                    var leftColumn = evaluate(mul.left(), activeBug);
                    var rightColumn = evaluate(mul.right(), activeBug);
                    var values = new ArrayList<>();
                    for (int i = 0; i < leftColumn.values.size(); i++) {
                        values.add(toLong(leftColumn.values.get(i)) * toLong(rightColumn.values.get(i)));
                    }
                    return new UnnamedColumn(leftColumn.type, values);
                }
                // FIXME(gal, NOCOMMIT) Reduce duplication with above
                case Div div -> {
                    var leftColumn = evaluate(div.left(), activeBug);
                    var rightColumn = evaluate(div.right(), activeBug);
                    var values = new ArrayList<>();
                    for (int i = 0; i < leftColumn.values.size(); i++) {
                        values.add(toLong(leftColumn.values.get(i)) / toLong(rightColumn.values.get(i)));
                    }
                    return new UnnamedColumn(leftColumn.type, values);
                }
                case GreaterThan gt -> {
                    var leftColumn = evaluate(gt.left(), activeBug);
                    var rightColumn = evaluate(gt.right(), activeBug);
                    var values = new ArrayList<>();
                    for (int i = 0; i < leftColumn.values.size(); i++) {
                        values.add(((Comparable<Object>) leftColumn.values.get(i)).compareTo(toLong(rightColumn.values.get(i))) > 0);
                    }
                    return new UnnamedColumn(DataType.BOOLEAN, values);
                }
                case LessThan lt -> {
                    var leftColumn = evaluate(lt.left(), activeBug);
                    var rightColumn = evaluate(lt.right(), activeBug);
                    var values = new ArrayList<>();
                    for (int i = 0; i < leftColumn.values.size(); i++) {
                        values.add(((Comparable<Object>) leftColumn.values.get(i)).compareTo(toLong(rightColumn.values.get(i))) < 0);
                    }
                    return new UnnamedColumn(DataType.BOOLEAN, values);
                }
                default -> throw new UnsupportedOperationException(Strings.format("Unsupported Expression in getColumn: [%s]", expression));
            }
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
        return switch (object) {
            case BytesRef br -> getString(br);
            case Object o -> o;
        };
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
