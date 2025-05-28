/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.common;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.core.AbstractRefCounted;
import org.elasticsearch.core.RefCounted;

/**
 * Similar to {@link AbstractRefCounted}, but the reference count starts at 0 instead of 1. {@link LazyAbstractRefCounted#closeInternal}
 * will only when reaching a reference count of 0 <i>after incrementing</i>.
 */
public abstract class LazyAbstractRefCounted implements RefCounted {
    private final SetOnce<AbstractRefCounted> refCounter = new SetOnce<>();

    @Override
    public void incRef() {
        if (maybeCreate() == false) {
            refCounter.get().incRef();
        }
    }

    @Override
    public boolean tryIncRef() {
        return maybeCreate() || refCounter.get().tryIncRef();
    }

    private boolean maybeCreate() {
        return refCounter.trySet(new AbstractRefCounted() {
            @Override
            protected void closeInternal() {
                LazyAbstractRefCounted.this.closeInternal();
            }
        });
    }

    @Override
    public boolean decRef() {
        assert refCounter.get() != null;
        return refCounter.get().decRef();
    }

    @Override
    public boolean hasReferences() {
        AbstractRefCounted rc = refCounter.get();
        return rc != null && rc.hasReferences();
    }

    protected abstract void closeInternal();
}
