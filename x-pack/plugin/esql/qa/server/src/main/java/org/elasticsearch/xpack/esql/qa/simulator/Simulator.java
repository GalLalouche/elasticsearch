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
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.xpack.esql.CsvTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.UnresolvedAttribute;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Div;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.plan.logical.Drop;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.UnresolvedRelation;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.elasticsearch.common.logging.LoggerMessageFormat.format;
import static org.elasticsearch.xpack.esql.CsvTestUtils.multiValuesAwareCsvToStringArray;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.reader;

public class Simulator {
    private static final Logger LOGGER = LogManager.getLogger(Simulator.class);

    public Result simulate(LogicalPlan plan) throws IOException {
        return switch (plan) {
            case org.elasticsearch.xpack.esql.plan.logical.Row row -> visit(row);
            case UnresolvedRelation relation -> visit(relation);
            case Keep keep -> visit(keep);
            case Drop drop -> visit(drop);
            case Filter filter -> visit(filter);
            case Eval eval -> visit(eval);
            default -> throw new UnsupportedOperationException("Simulation not (yet) supported for plan type: " + plan.getClass());
        };
    }

    private Result visit(org.elasticsearch.xpack.esql.plan.logical.Row row) {
        List<Column> columns = row.fields().stream().map(alias -> switch (alias.child()) {
            case Literal l -> new Column(alias.name(), alias.dataType(), List.of(l.value()));
            default -> throw new UnsupportedOperationException(
                "Row field [" + alias.name() + "] is not a literal, but a " + alias.child().getClass()
            );
        }).toList();
        return new Result(columns);
    }

    private Result visit(UnresolvedRelation relation) throws IOException {
        String pattern = relation.indexPattern().indexPattern();
        assert pattern.indexOf('*') == -1 : "Index patterns with wildcards are not supported yet in simulation, found: " + pattern;

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

    private Result visit(Keep keep) throws IOException {
        var childResult = simulate(keep.child());
        var newColumns = new ArrayList<Column>();
        for (var expression : keep.expressions()) {
            switch (expression) {
                case UnresolvedAttribute ua -> newColumns.add(childResult.getColumn(ua.name()));
                default -> throw new UnsupportedOperationException(
                    "Keep expression [" + expression + "] is not an UnresolvedAttribute, but a " + expression.getClass()
                );
            }
        }
        return new Result(newColumns);
    }

    private Result visit(Drop drop) throws IOException {
        var childResult = simulate(drop.child());
        var newColumns = new ArrayList<>(childResult.columns);
        Set<String> names = drop.expressions().stream().map(expression -> {
            if (expression instanceof UnresolvedAttribute ua) {
                return ua.name();
            }
            throw new UnsupportedOperationException(
                "Drop expression [" + expression + "] is not an UnresolvedAttribute, but a " + expression.getClass()
            );
        }).collect(Collectors.toSet());
        newColumns.removeIf(column -> names.contains(column.name));
        return new Result(newColumns);
    }

    private Result visit(Eval eval) throws IOException {
        var childResult = simulate(eval.child());
        var newColumns = new ArrayList<Column>();
        for (var expression : eval.expressions()) {
            switch (expression) {
                case Alias alias -> newColumns.add(childResult.evaluate(alias.child()).toNamed(alias.name()));
                default -> throw new UnsupportedOperationException(
                    "Eval expression [" + expression + "] is not an alias, but a " + expression.getClass()
                );
            }
        }
        return childResult.append(newColumns);
    }

    private Result visit(Filter filter) throws IOException {
        var childResult = simulate(filter.child());
        var conditionResult = childResult.evaluate(filter.condition());
        var validIndices = new BitSet();
        for (int i = 0; i < conditionResult.values.size(); i++) {
            if ((boolean) conditionResult.values.get(i)) {
                validIndices.set(i);
            }
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
        public UnnamedColumn evaluate(Expression expression) {
            switch (expression) {
                case UnresolvedAttribute ua -> {
                    return new UnnamedColumn(getColumn(ua.name()));
                }
                case Literal literal -> {
                    return new UnnamedColumn(
                        literal.dataType(),
                        IntStream.range(0, columns().getFirst().values.size()).mapToObj(unused -> normalizeObject(literal.value())).toList()
                    );
                }
                case Add add -> {
                    var leftColumn = evaluate(add.left());
                    var rightColumn = evaluate(add.right());
                    var values = new ArrayList<>();
                    for (int i = 0; i < leftColumn.values.size(); i++) {
                        values.add(toLong(leftColumn.values.get(i)) + toLong(rightColumn.values.get(i)));
                    }
                    return new UnnamedColumn(leftColumn.type, values);
                }
                case Sub sub -> {
                    var leftColumn = evaluate(sub.left());
                    var rightColumn = evaluate(sub.right());
                    var values = new ArrayList<>();
                    for (int i = 0; i < leftColumn.values.size(); i++) {
                        values.add(toLong(leftColumn.values.get(i)) - toLong(rightColumn.values.get(i)));
                    }
                    return new UnnamedColumn(leftColumn.type, values);
                }
                case Mul mul -> {
                    var leftColumn = evaluate(mul.left());
                    var rightColumn = evaluate(mul.right());
                    var values = new ArrayList<>();
                    for (int i = 0; i < leftColumn.values.size(); i++) {
                        values.add(toLong(leftColumn.values.get(i)) * toLong(rightColumn.values.get(i)));
                    }
                    return new UnnamedColumn(leftColumn.type, values);
                }
                // FIXME(gal, NOCOMMIT) Reduce duplication with above
                case Div div -> {
                    var leftColumn = evaluate(div.left());
                    var rightColumn = evaluate(div.right());
                    var values = new ArrayList<>();
                    for (int i = 0; i < leftColumn.values.size(); i++) {
                        values.add(toLong(leftColumn.values.get(i)) / toLong(rightColumn.values.get(i)));
                    }
                    return new UnnamedColumn(leftColumn.type, values);
                }
                case GreaterThan gt -> {
                    var leftColumn = evaluate(gt.left());
                    var rightColumn = evaluate(gt.right());
                    var values = new ArrayList<>();
                    for (int i = 0; i < leftColumn.values.size(); i++) {
                        values.add(((Comparable<Object>) leftColumn.values.get(i)).compareTo(toLong(rightColumn.values.get(i))) > 0);
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
