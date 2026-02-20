/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;

import java.util.List;

/** Shared utilities for simulator tests. */
class SimulatorTestUtils {
    private SimulatorTestUtils() { /* static class */ }

    /**
     * Initialize ES logging and break the IndexSettings/IndexMode circular class init dependency.
     * Must be called from a {@code static {}} block in every test class (which runs outside the ES test framework).
     */
    static void initLogging() {
        // Match ESTestCase's logging init: log4j2-test.properties on the classpath sets rootLogger.level = info.
        LogConfigurator.loadLog4jPlugins();
        LogConfigurator.configureESLogging();
        // Log4j2 may have already initialized before plugins were loaded (e.g. JUnitQuickcheck triggers
        // class loading that calls LogManager.getLogger()). Reconfigure so it re-reads log4j2-test.properties
        // with the plugins now available — this creates the console appender that uses %test_thread_info.
        ((LoggerContext) LogManager.getContext(false)).reconfigure();
        // Force IndexSettings to initialize before IndexMode to break circular class init dependency.
        assert IndexSettings.MODE != null;
    }

    /**
     * Process {@link TestLogging} on the given class, applying log levels via {@link Loggers#setLevel}.
     * JUnitQuickcheck doesn't register {@link org.elasticsearch.test.junit.listeners.LoggingListener},
     * so we replicate its behavior here for classes that use {@code @RunWith(JUnitQuickcheck.class)}.
     */
    static void applyTestLogging(Class<?> testClass) {
        TestLogging annotation = testClass.getAnnotation(TestLogging.class);
        if (annotation != null) {
            for (String pair : annotation.value().split(",")) {
                String[] kv = pair.split(":");
                if (kv.length != 2) {
                    throw new IllegalArgumentException("Invalid @TestLogging entry: [" + pair + "], expected format 'logger:LEVEL'");
                }
                String loggerName = kv[0].trim();
                String level = kv[1].trim();
                if ("_root".equals(loggerName)) {
                    Loggers.setLevel(LogManager.getRootLogger(), level);
                } else {
                    Loggers.setLevel(LogManager.getLogger(loggerName), level);
                }
            }
        }
    }

    /** Collect attribute references from a plan node's own expressions (not children). */
    static List<Attribute> collectAttributeReferences(LogicalPlan plan) {
        return switch (plan) {
            case Keep keep -> keep.projections().stream().filter(ne -> ne instanceof Attribute).map(ne -> (Attribute) ne).toList();
            case Filter filter -> collectExprAttrs(filter.condition());
            case Eval eval -> eval.fields().stream().flatMap(a -> collectExprAttrs(a.child()).stream()).toList();
            case OrderBy ob -> ob.order().stream().flatMap(o -> collectExprAttrs(o.child()).stream()).toList();
            default -> List.of();
        };
    }

    /** Recursively collect all {@link Attribute} references from an expression tree. */
    static List<Attribute> collectExprAttrs(Expression expr) {
        if (expr instanceof Attribute a) {
            return List.of(a);
        }
        if (expr instanceof Literal) {
            return List.of();
        }
        return expr.children().stream().flatMap(child -> collectExprAttrs(child).stream()).toList();
    }

    /** Generates resolved {@link LogicalPlan} trees for property-based tests. */
    public static class ResolvedPlanGenerator extends Generator<LogicalPlan> {
        static {
            initLogging();
        }

        public ResolvedPlanGenerator() {
            super(LogicalPlan.class);
        }

        @Override
        public LogicalPlan generate(SourceOfRandomness random, GenerationStatus status) {
            SimSchema schema = SimSchemaGenerator.generateWithInteger(random, status);
            return LogicalPlanGenerator.generate(schema, random, status);
        }
    }

    /** Generates raw (unresolved) {@link LogicalPlan} trees for property-based tests. */
    public static class RawPlanGenerator extends Generator<LogicalPlan> {
        static {
            initLogging();
        }

        public RawPlanGenerator() {
            super(LogicalPlan.class);
        }

        @Override
        public LogicalPlan generate(SourceOfRandomness random, GenerationStatus status) {
            SimSchema schema = SimSchemaGenerator.generateWithInteger(random, status);
            return LogicalPlanGenerator.generateRaw(schema, random, status);
        }
    }
}
