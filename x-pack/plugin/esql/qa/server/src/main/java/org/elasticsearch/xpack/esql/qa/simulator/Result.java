/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.util.ArrayUtils;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.type.DataType;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

/**
 * Columnar result of a simulated ES|QL query execution. Also serves as the expression evaluator:
 * given an {@link Expression} tree, {@link #evaluate} walks it and produces a column of output values.
 */
record Result(List<Simulator.Column> columns) {
    public Result(Simulator.Column first, Simulator.Column... rest) {
        this(Arrays.asList(ArrayUtils.prepend(first, rest)));
    }

    int numRows() {
        return columns.isEmpty() ? 0 : columns.getFirst().values().size();
    }

    public Simulator.Column getColumn(String name) {
        // ES|QL EVAL can shadow columns; iterating in reverse returns the live value.
        for (int i = columns.size() - 1; i >= 0; i--) {
            Simulator.Column c = columns.get(i);
            if (c.name().equals(name)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Column not found: " + name);
    }

    public Simulator.UnnamedColumn evaluate(Expression expression, SimBug activeBug) {
        return switch (expression) {
            case Attribute attr -> new Simulator.UnnamedColumn(getColumn(attr.name()));
            case Literal literal -> new Simulator.UnnamedColumn(
                literal.dataType(),
                Collections.nCopies(numRows(), Simulator.normalizeObject(literal.value()))
            );
            case Add add -> evalBinaryLong(add.left(), add.right(), activeBug, (l, r, rt) -> {
                if (activeBug == SimBug.ADD_IS_SUB) {
                    return rt == DataType.INTEGER ? (Long) (l - r) : Simulator.safeExact(() -> Math.subtractExact(l, r));
                }
                return rt == DataType.INTEGER ? (Long) (l + r) : Simulator.safeExact(() -> Math.addExact(l, r));
            });
            case Sub sub -> evalBinaryLong(sub.left(), sub.right(), activeBug, (l, r, rt) ->
                rt == DataType.INTEGER ? (Long) (l - r) : Simulator.safeExact(() -> Math.subtractExact(l, r)));
            case Mul mul -> evalBinaryLong(mul.left(), mul.right(), activeBug, (l, r, rt) ->
                rt == DataType.INTEGER ? (Long) (l * r) : Simulator.safeExact(() -> Math.multiplyExact(l, r)));
            case Div div -> evalBinaryLong(div.left(), div.right(), activeBug, (l, r, rt) -> {
                if (r == 0) return null;
                return rt == DataType.INTEGER ? (Long) (l / r) : Simulator.safeExact(() -> Math.divideExact(l, r));
            });
            case Mod mod -> evalBinaryLong(mod.left(), mod.right(), activeBug,
                (l, r, rt) -> r == 0 ? null : l % r);
            case Neg neg -> {
                Simulator.UnnamedColumn input = evaluate(neg.field(), activeBug);
                yield new Simulator.UnnamedColumn(input.type(), input.values().stream().<Object>map(o -> {
                    if (o == null) {
                        return null;
                    }
                    long val = Simulator.toLong(o);
                    if (input.type() == DataType.INTEGER) {
                        long result = -val;
                        return (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) ? null : result;
                    }
                    // LONG: use negateExact to detect -Long.MIN_VALUE overflow
                    return Simulator.safeExact(() -> Math.negateExact(val));
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
                Simulator.UnnamedColumn input = evaluate(length.field(), activeBug);
                // The simulator stores all integer values as Long internally, even when DataType is INTEGER.
                yield new Simulator.UnnamedColumn(
                    DataType.INTEGER,
                    input.values().stream().<Object>map(o -> o == null ? null : (long) o.toString().length()).toList()
                );
            }
            case Concat concat -> {
                List<Simulator.UnnamedColumn> args = concat.children().stream().map(child -> evaluate(child, activeBug)).toList();
                int numRows = args.getFirst().values().size();
                yield new Simulator.UnnamedColumn(DataType.KEYWORD, IntStream.range(0, numRows).<Object>mapToObj(i -> {
                    StringBuilder sb = new StringBuilder();
                    for (Simulator.UnnamedColumn arg : args) {
                        Object v = arg.values().get(i);
                        if (v == null) {
                            return null;
                        }
                        sb.append(v);
                    }
                    return sb.toString();
                }).toList());
            }
            case Left left -> evalBinaryString(
                left.children().get(0),
                left.children().get(1),
                activeBug,
                DataType.KEYWORD,
                (str, arg) -> str.substring(0, Math.min(Math.max(0, ((Number) arg).intValue()), str.length()))
            );
            case Right right -> evalBinaryString(
                right.children().get(0),
                right.children().get(1),
                activeBug,
                DataType.KEYWORD,
                (str, arg) -> str.substring(Math.max(0, str.length() - Math.max(0, ((Number) arg).intValue())))
            );
            case StartsWith sw -> evalBinaryString(
                sw.children().get(0),
                sw.children().get(1),
                activeBug,
                DataType.BOOLEAN,
                (str, arg) -> str.startsWith(arg.toString())
            );
            case EndsWith ew -> evalBinaryString(
                ew.children().get(0),
                ew.children().get(1),
                activeBug,
                DataType.BOOLEAN,
                (str, arg) -> str.endsWith(arg.toString())
            );
            case Substring substring -> {
                Simulator.UnnamedColumn strCol = evaluate(substring.children().get(0), activeBug);
                Simulator.UnnamedColumn startCol = evaluate(substring.children().get(1), activeBug);
                // SUBSTRING(str, start) with no length arg returns to end-of-string; lenCol == null encodes that.
                Simulator.UnnamedColumn lenCol = substring.children().size() > 2 ? evaluate(substring.children().get(2), activeBug) : null;
                yield new Simulator.UnnamedColumn(DataType.KEYWORD, IntStream.range(0, strCol.values().size()).<Object>mapToObj(i -> {
                    Object strVal = strCol.values().get(i);
                    Object startVal = startCol.values().get(i);
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
                    Object lenVal = lenCol.values().get(i);
                    if (lenVal == null) {
                        return null;
                    }
                    int len = ((Number) lenVal).intValue();
                    int indexEnd = Math.min(str.length(), indexStart + Math.max(0, len));
                    return str.substring(indexStart, indexEnd);
                }).toList());
            }
            default -> throw new UnsupportedOperationException(Strings.format("Unsupported Expression in evaluate: [%s]", expression));
        };
    }

    @FunctionalInterface
    private interface ArithOp {
        Object apply(long l, long r, DataType resultType);
    }

    /**
     * Determines the numeric result type for binary arithmetic: LONG if either operand is LONG, INTEGER otherwise.
     * This mirrors ES|QL type promotion without calling {@code op.dataType()} on the AST node, which would
     * fail for string-parsed plans containing {@code UnresolvedAttribute} nodes.
     */
    private static DataType numericResultType(DataType leftType, DataType rightType) {
        if (leftType == DataType.LONG || rightType == DataType.LONG) {
            return DataType.LONG;
        }
        return DataType.INTEGER;
    }

    private Simulator.UnnamedColumn evalBinaryLong(
        Expression leftExpr,
        Expression rightExpr,
        SimBug activeBug,
        ArithOp op
    ) {
        Simulator.UnnamedColumn left = evaluate(leftExpr, activeBug);
        Simulator.UnnamedColumn right = evaluate(rightExpr, activeBug);
        DataType resultType = numericResultType(left.type(), right.type());
        return new Simulator.UnnamedColumn(resultType, zipWith(left.values(), right.values(), (l, r) -> {
            if (l == null || r == null) {
                return null;
            }
            Object result = op.apply(Simulator.toLong(l), Simulator.toLong(r), resultType);
            if (result == null) {
                return null;
            }
            long longResult = ((Number) result).longValue();
            if (resultType == DataType.INTEGER && (longResult < Integer.MIN_VALUE || longResult > Integer.MAX_VALUE)) {
                return null;
            }
            return longResult;
        }));
    }

    private Simulator.UnnamedColumn evalUnaryString(Expression fieldExpr, SimBug activeBug, UnaryOperator<String> op) {
        Simulator.UnnamedColumn input = evaluate(fieldExpr, activeBug);
        return new Simulator.UnnamedColumn(
            DataType.KEYWORD,
            input.values().stream().<Object>map(o -> o == null ? null : op.apply(o.toString())).toList()
        );
    }

    // Both INTEGER and LONG values are stored as Java long internally, so Long.compare handles all numeric comparisons.
    private Simulator.UnnamedColumn evalComparison(Expression leftExpr, Expression rightExpr, SimBug activeBug, IntPredicate test) {
        Simulator.UnnamedColumn left = evaluate(leftExpr, activeBug);
        Simulator.UnnamedColumn right = evaluate(rightExpr, activeBug);
        return new Simulator.UnnamedColumn(
            DataType.BOOLEAN,
            zipWith(
                left.values(),
                right.values(),
                (l, r) -> l == null || r == null ? null : test.test(Long.compare(Simulator.toLong(l), Simulator.toLong(r)))
            )
        );
    }

    private Simulator.UnnamedColumn evalBinaryString(
        Expression strExpr,
        Expression argExpr,
        SimBug activeBug,
        DataType resultType,
        BiFunction<String, Object, Object> op
    ) {
        Simulator.UnnamedColumn strCol = evaluate(strExpr, activeBug);
        Simulator.UnnamedColumn argCol = evaluate(argExpr, activeBug);
        return new Simulator.UnnamedColumn(
            resultType,
            zipWith(
                strCol.values(),
                argCol.values(),
                (strVal, argVal) -> strVal == null || argVal == null ? null : op.apply(strVal.toString(), argVal)
            )
        );
    }

    private static <A, B, C> List<C> zipWith(List<A> as, List<B> bs, BiFunction<A, B, C> fn) {
        assert as.size() == bs.size();
        return IntStream.range(0, as.size()).mapToObj(i -> fn.apply(as.get(i), bs.get(i))).toList();
    }
}
