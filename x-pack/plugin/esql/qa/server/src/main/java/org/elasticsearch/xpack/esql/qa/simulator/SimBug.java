/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

/**
 * Named simulator bugs for meta-testing. When active, each variant causes the
 * {@link Simulator} to behave incorrectly in a specific way. Property tests
 * verify that these bugs produce detectable, minimal counter-examples after shrinking.
 */
public enum SimBug {
    BUG_FREE,
    ADD_IS_SUB,
    KEEP_DROPS_FIRST,
    WHERE_INVERTED,
    SORT_REVERSED,
    LIMIT_OFF_BY_ONE,
    STATS_COUNT_OFF_BY_ONE,
    INLINESTATS_DROPS_ROWS
}
