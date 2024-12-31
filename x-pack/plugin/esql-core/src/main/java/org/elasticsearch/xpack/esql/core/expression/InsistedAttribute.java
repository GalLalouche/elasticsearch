/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.core.expression;

import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;

import java.io.IOException;

public class InsistedAttribute extends TypedAttribute {
    static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(
        Attribute.class,
        "InsistedAttribute",
        InsistedAttribute::readFrom
    );

    private static Attribute readFrom(StreamInput streamInput) throws IOException {
        String name = streamInput.readString();
        DataType dataType = DataType.readFrom(streamInput);
        return new InsistedAttribute(Source.EMPTY, name, dataType);
    }

    public InsistedAttribute(Source source, String name, DataType dataType) {
        this(source, name, dataType, null /* id */);
    }

    private InsistedAttribute(Source source, String name, DataType dataType, @Nullable NameId id) {
        super(source, name, dataType, Nullability.TRUE, id, false /* synthetic */);
    }

    @Override
    protected Attribute clone(Source source, String name, DataType type, Nullability nullability, NameId id, boolean synthetic) {
        return new InsistedAttribute(source, name, type, id);
    }

    @Override
    protected String label() {
        return "i";
    }

    @Override
    protected NodeInfo<? extends Expression> info() {
        throw new AssertionError("TODO(gal)");
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(name());
        dataType().writeTo(out);
    }
}
