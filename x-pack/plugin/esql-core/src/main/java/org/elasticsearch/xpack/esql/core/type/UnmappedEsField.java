/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.core.type;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.Strings;
import org.elasticsearch.xpack.esql.core.expression.Expression;

import java.io.IOException;
import java.util.Map;

import static org.elasticsearch.xpack.esql.core.util.PlanStreamInput.readCachedStringWithVersionCheck;
import static org.elasticsearch.xpack.esql.core.util.PlanStreamOutput.writeCachedStringWithVersionCheck;

// FIXME(gal, do-not-merge!) document, explain, fix, etc.
public class UnmappedEsField extends EsField {
    private UnmappedEsField(State state, String name, DataType dataType, Map<String, EsField> properties) {
        super(name, dataType, properties, true /* aggregatable */);
        this.state = state;
    }

    // FIXME(gal, do-not-merge!) Add invalid ctor
    public sealed interface State {};

    // FIXME(gal, do-not-merge!) document all of these
    // FIXME(gal, do-not-merge!) this should accept explicitly an EsField, not one of its subtypes
    public record Simple(EsField field) implements State {};

    public enum Unmapped implements State {
        INSTANCE
    }

    public record SimpleConversion(Expression conversionFromKeyword) implements State {};

    public record MultiTypeConversion(Expression conversionFromKeyword, MultiTypeEsField mf) implements State {};

    private final State state;

    public static UnmappedEsField fromField(EsField f) {
        return new UnmappedEsField(new Simple(f), f.getName(), f.getDataType(), f.getProperties());
    }

    public static UnmappedEsField fromStandalone(String name) {
        return new UnmappedEsField(Unmapped.INSTANCE, name, DataType.KEYWORD, Map.of());
    }

    public static UnmappedEsField withConversion(String name, Expression conversionFromKeyword) {
        return new UnmappedEsField(new SimpleConversion(conversionFromKeyword), name, conversionFromKeyword.dataType(), Map.of());
    }

    public static UnmappedEsField fromMultiType(Expression expression, MultiTypeEsField resolvedField) {
        return new UnmappedEsField(
            new MultiTypeConversion(expression, resolvedField),
            resolvedField.getName(),
            resolvedField.getDataType(),
            resolvedField.getProperties()
        );
    }

    UnmappedEsField(StreamInput in) throws IOException {
        this(readState(in), readCachedStringWithVersionCheck(in), DataType.readFrom(in), in.readImmutableMap(EsField::readFrom));
    }

    private static State readState(StreamInput in) throws IOException {
        var ordinal = in.readInt();
        return switch (ordinal) {
            case 0 -> new Simple(EsField.readFrom(in));
            case 1 -> Unmapped.INSTANCE;
            case 2 -> new SimpleConversion(in.readNamedWriteable(Expression.class));
            case 3 -> new MultiTypeConversion(in.readNamedWriteable(Expression.class), MultiTypeEsField.readFrom(in));
            default -> throw new AssertionError("Unexpected ordinal: " + ordinal);
        };
    }

    public State getState() {
        return state;
    }

    @Override
    public void writeContent(StreamOutput out) throws IOException {
        switch (state) {
            case Simple(var field) -> {
                out.writeInt(0);
                field.writeTo(out);
            }
            case Unmapped unused -> {
                out.writeInt(1);
            }
            case SimpleConversion(var conversion) -> {
                out.writeInt(2);
                out.writeNamedWriteable(conversion);
            }
            case MultiTypeConversion(var conversion, var multiTypeEsField) -> {
                out.writeInt(3);
                out.writeNamedWriteable(conversion);
                multiTypeEsField.writeTo(out);
            }
        }
        writeCachedStringWithVersionCheck(out, getName());
        getDataType().writeTo(out);
        out.writeMap(getProperties(), (o, x) -> x.writeTo(out));
    }

    @Override
    public String getWriteableName() {
        return "InsistedEsField";
    }

    @Override
    public String toString() {
        return Strings.format("InsistedEsField{state=%s}", state);
    }
}
