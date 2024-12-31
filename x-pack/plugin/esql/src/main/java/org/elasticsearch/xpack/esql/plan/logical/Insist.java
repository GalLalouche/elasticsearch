/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.plan.logical;

import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.InsistedAttribute;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;
import org.elasticsearch.xpack.esql.plan.InsistParameters;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class Insist extends UnaryPlan {
    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(LogicalPlan.class, "INSIST", Insist::new);

    private final InsistParameters parameters;

    public Insist(Source source, InsistParameters parameters, LogicalPlan child) {
        super(source, child);
        this.parameters = parameters;
    }

    private @Nullable List<Attribute> computedOutput = null;

    @Override
    public List<Attribute> output() {
        if (computedOutput == null) {
            computedOutput = isRedundant() ? child().output() : Stream.concat(child().output().stream(), attributes().stream()).toList();
        }
        return computedOutput;
    }

    public boolean isRedundant() {
        return child().output().stream().anyMatch(parameters::isTheSameAs);
    }

    public InsistParameters parameters() {
        return parameters;
    }

    public List<Attribute> attributes() {
        return List.of(new InsistedAttribute(source(), parameters.identifier(), parameters.dataType()));
    }

    @Override
    public Insist replaceChild(LogicalPlan newChild) {
        return new Insist(source(), parameters, newChild);
    }

    @Override
    public String commandName() {
        return "INSIST";
    }

    @Override
    public boolean expressionsResolved() {
        return true;
    }

    @Override
    protected NodeInfo<? extends LogicalPlan> info() {
        return NodeInfo.create(this, Insist::new, parameters(), child());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        Source.EMPTY.writeTo(out);
        out.writeWriteable(parameters());
        out.writeNamedWriteable(child());
    }

    private Insist(StreamInput in) throws IOException {
        this(Source.readFrom((PlanStreamInput) in), InsistParameters.readFrom(in), in.readNamedWriteable(LogicalPlan.class));
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    @Override
    public int hashCode() {
        return Objects.hash(parameters, child());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        Insist other = (Insist) obj;

        return Objects.equals(parameters, other.parameters) && Objects.equals(child(), other.child());
    }
}
