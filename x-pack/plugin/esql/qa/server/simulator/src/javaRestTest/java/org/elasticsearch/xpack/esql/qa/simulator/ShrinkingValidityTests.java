/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.EdgeCasesMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.lifecycle.PerProperty;
import net.jqwik.api.lifecycle.PropertyExecutionResult;

import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;
import org.elasticsearch.xpack.esql.plan.logical.UnaryPlan;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.stream.Collectors.toMap;

/**
 * Empirical test for whether jqwik shrinking produces plans with stale NameId references.
 * Plans are generated WITHOUT {@link LogicalPlanGenerator#resolveReferences} so that any
 * staleness introduced by shrinking is visible. Bug injection forces property failures
 * to trigger shrinking, and a {@code .map()} validator detects stale references on every
 * candidate plan jqwik considers.
 */
public class ShrinkingValidityTests {
    static {
        LogConfigurator.configureESLogging();
        var unused = IndexSettings.MODE;
    }

    record ValidatedPlan(LogicalPlan plan, List<String> issues) {
        @Override
        public String toString() {
            return LogicalPlanPrinter.print(plan) + (issues.isEmpty() ? " [valid]" : " [STALE: " + issues + "]");
        }
    }

    /**
     * Uses ADD_IS_SUB bug to force property failure, triggering shrinking.
     * Plans are generated WITHOUT resolveReferences.
     * The .map() validation detects any stale references produced during shrinking.
     */
    @Property(tries = 200)
    @PerProperty(StalenessDetector.class)
    void shrinkingProducesStaleReferences(@ForAll("buggablePlans") ValidatedPlan vp) throws IOException {
        var rel = findEsRelation(vp.plan);
        var schema = schemaFromRelation(rel);
        var data = fixedData(schema);
        var correct = new Simulator(schema, data, SimBug.BUG_FREE).simulate(vp.plan);
        var bugged = new Simulator(schema, data, SimBug.ADD_IS_SUB).simulate(vp.plan);
        if (correct.equals(bugged) == false) {
            throw new AssertionError("Divergence (expected — this triggers shrinking)");
        }
    }

    private static final AtomicInteger STALE_COUNT = new AtomicInteger();
    private static final AtomicInteger TOTAL_VALIDATED = new AtomicInteger();

    @Provide
    Arbitrary<ValidatedPlan> buggablePlans() {
        return schemasWithInteger().flatMap(schema -> LogicalPlanGenerator.rawPlansFor(schema, 5).map(plan -> {
            TOTAL_VALIDATED.incrementAndGet();
            var issues = validateReferences(plan);
            if (issues.isEmpty() == false) {
                int count = STALE_COUNT.incrementAndGet();
                System.err.println("STALE CANDIDATE #" + count + ": " + LogicalPlanPrinter.print(plan) + " → " + issues);
            }
            return new ValidatedPlan(plan, issues);
        }));
    }

    static List<String> validateReferences(LogicalPlan plan) {
        if (plan instanceof EsRelation || plan instanceof UnaryPlan == false) return List.of();
        var unary = (UnaryPlan) plan;
        var canonical = unary.child().output().stream().collect(toMap(Attribute::name, a -> a, (a, b) -> a));
        var issues = new ArrayList<>(validateReferences(unary.child()));
        for (Attribute ref : collectAttributeReferences(plan)) {
            var canon = canonical.get(ref.name());
            if (canon == null) {
                issues.add("Missing column '" + ref.name() + "' in " + plan.getClass().getSimpleName());
            } else if (canon.id().equals(ref.id()) == false) {
                issues.add(
                    "Stale NameId for '"
                        + ref.name()
                        + "' in "
                        + plan.getClass().getSimpleName()
                        + " (expected "
                        + canon.id()
                        + ", got "
                        + ref.id()
                        + ")"
                );
            }
        }
        return issues;
    }

    static List<Attribute> collectAttributeReferences(LogicalPlan plan) {
        return switch (plan) {
            case Keep keep -> keep.projections().stream().filter(ne -> ne instanceof Attribute).map(ne -> (Attribute) ne).toList();
            case Filter filter -> collectExprAttrs(filter.condition());
            case Eval eval -> eval.fields().stream().flatMap(a -> collectExprAttrs(a.child()).stream()).toList();
            case OrderBy ob -> ob.order().stream().flatMap(o -> collectExprAttrs(o.child()).stream()).toList();
            default -> List.of();
        };
    }

    static List<Attribute> collectExprAttrs(Expression expr) {
        if (expr instanceof Attribute a) return List.of(a);
        if (expr instanceof Literal) return List.of();
        var result = new ArrayList<Attribute>();
        for (Expression child : expr.children()) {
            result.addAll(collectExprAttrs(child));
        }
        return result;
    }

    public static class StalenessDetector implements PerProperty.Lifecycle {
        @Override
        public PropertyExecutionResult onFailure(PropertyExecutionResult result) {
            System.out.println("Plans validated (generation + shrinking): " + TOTAL_VALIDATED.get());
            System.out.println("Stale candidates found: " + STALE_COUNT.get());
            var shrunk = result.shrunkSample().orElse(null);
            if (shrunk == null) {
                System.out.println("RESULT: No shrunk sample available");
                // return result.mapToSuccessful();
                return result;
            }
            var vp = (ValidatedPlan) shrunk.parameters().get(0);
            if (vp.issues.isEmpty()) {
                System.out.println("RESULT: Shrunk plan is VALID (no stale refs): " + vp);
            } else {
                System.out.println("RESULT: Shrunk plan is STALE: " + vp);
                System.out.println("Issues: " + vp.issues);
            }
            // return result.mapToSuccessful();
            return result;
        }

        @Override
        public void onSuccess() {
            System.out.println("Plans validated (generation only, no failure triggered): " + TOTAL_VALIDATED.get());
            System.out.println("Stale candidates found: " + STALE_COUNT.get());
        }
    }

    /** With edge cases (default MIXIN): staleness IS expected. */
    @Property(tries = 1000)
    @PerProperty(ExpectStaleness.class)
    void generatedPlansAreValid(@ForAll("rawPlans") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) {
            throw new AssertionError("Stale during generation: " + vp);
        }
    }

    /** Without edge cases: staleness is still present. */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ExpectStaleness.class)
    void generatedPlansAreValidWithoutEdgeCases(@ForAll("rawPlans") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) {
            throw new AssertionError("Stale during generation (edge cases disabled): " + vp);
        }
    }

    public static class ExpectStaleness implements PerProperty.Lifecycle {
        @Override
        public void onSuccess() {
            throw new AssertionError("Expected staleness during generation, but all plans were valid");
        }

        @Override
        public PropertyExecutionResult onFailure(PropertyExecutionResult result) {
            return result.mapToSuccessful();
        }
    }

    @Provide
    Arbitrary<ValidatedPlan> rawPlans() {
        return schemasWithInteger().flatMap(
            schema -> LogicalPlanGenerator.rawPlansFor(schema, 5).map(plan -> new ValidatedPlan(plan, validateReferences(plan)))
        );
    }

    // ---- Narrowing tests: isolate which variables cause staleness ----

    private static final SimSchema FIXED_SCHEMA = new SimSchema("test", List.of(new SimSchema.SimColumn("a", DataType.INTEGER)));

    /** Fixed schema, depth 1 (single flatMap, no schema randomness). */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void fixedSchemaDepth1(@ForAll("fixedD1") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    /** Fixed schema, depth 2. */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void fixedSchemaDepth2(@ForAll("fixedD2") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    /** Fixed schema, depth 3. */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void fixedSchemaDepth3(@ForAll("fixedD3") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    /** Fixed schema, depth 5 (same depth as rawPlans, but no schema flatMap). */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void fixedSchemaDepth5(@ForAll("fixedD5") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    /** Random schema (with outer flatMap), depth 1. */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void randomSchemaDepth1(@ForAll("randomD1") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    @Provide
    Arbitrary<ValidatedPlan> fixedD1() {
        return validated(FIXED_SCHEMA, 1);
    }

    @Provide
    Arbitrary<ValidatedPlan> fixedD2() {
        return validated(FIXED_SCHEMA, 2);
    }

    @Provide
    Arbitrary<ValidatedPlan> fixedD3() {
        return validated(FIXED_SCHEMA, 3);
    }

    @Provide
    Arbitrary<ValidatedPlan> fixedD5() {
        return validated(FIXED_SCHEMA, 5);
    }

    @Provide
    Arbitrary<ValidatedPlan> randomD1() {
        return schemasWithInteger().flatMap(s -> validated(s, 1));
    }

    private static Arbitrary<ValidatedPlan> validated(SimSchema schema, int depth) {
        return LogicalPlanGenerator.rawPlansFor(schema, depth).map(plan -> new ValidatedPlan(plan, validateReferences(plan)));
    }

    public static class ReportStaleness implements PerProperty.Lifecycle {
        @Override
        public void onSuccess() {
            System.out.println("  → No staleness detected");
        }

        @Override
        public PropertyExecutionResult onFailure(PropertyExecutionResult result) {
            boolean staleness = result.throwable().map(t -> t.getMessage().startsWith("Stale")).orElse(false);
            var shrunk = result.shrunkSample().orElse(null);
            var msg = result.throwable().map(Throwable::getMessage).orElse("(no message)");
            if (staleness) {
                System.out.println("  → STALENESS DETECTED: " + msg);
            } else {
                System.out.println("  → FAILED (not staleness): " + msg);
            }
            // return result.mapToSuccessful();
            return result;
        }
    }

    // ---- Handcrafted minimal chains: zero randomness ----

    /**
     * Handcrafted: base → flatMap(EVAL z = a + a) → flatMap(KEEP z).
     * No randomness at all — every flatMap returns Arbitraries.just().
     * If this shows staleness, the issue is purely in flatMap re-evaluation
     * creating new Alias NameIds.
     */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void handcraftedEvalKeep(@ForAll("evalKeepChain") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    /**
     * Handcrafted: base → flatMap(KEEP a) → flatMap(KEEP a).
     * No new NameIds are ever allocated — KEEP only references existing attributes.
     * If this shows staleness, the issue is NOT about NameId allocation.
     */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void handcraftedKeepKeep(@ForAll("keepKeepChain") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    @Provide
    Arbitrary<ValidatedPlan> evalKeepChain() {
        var base = Arbitraries.just(LogicalPlanGenerator.buildEsRelation(FIXED_SCHEMA));
        return base.flatMap(rel -> {
            var a = rel.output().get(0);
            var add = new Add(Source.EMPTY, a, a, EsqlTestUtils.TEST_CFG);
            return Arbitraries.just((LogicalPlan) new Eval(Source.EMPTY, rel, List.of(new Alias(Source.EMPTY, "z", add))));
        }).flatMap(eval -> {
            var z = eval.output().stream().filter(attr -> attr.name().equals("z")).findFirst().orElseThrow();
            return Arbitraries.just((LogicalPlan) new Keep(Source.EMPTY, eval, List.of((NamedExpression) z)));
        }).map(plan -> new ValidatedPlan(plan, validateReferences(plan)));
    }

    @Provide
    Arbitrary<ValidatedPlan> keepKeepChain() {
        var base = Arbitraries.just(LogicalPlanGenerator.buildEsRelation(FIXED_SCHEMA));
        return base.flatMap(rel -> {
            var projections = rel.output().stream().map(a -> (NamedExpression) a).toList();
            return Arbitraries.just((LogicalPlan) new Keep(Source.EMPTY, rel, projections));
        }).flatMap(keep -> {
            var projections = keep.output().stream().map(a -> (NamedExpression) a).toList();
            return Arbitraries.just((LogicalPlan) new Keep(Source.EMPTY, keep, projections));
        }).map(plan -> new ValidatedPlan(plan, validateReferences(plan)));
    }

    /**
     * Uses actual wrapLayer in a plain flatMap chain — NO Arbitraries.recursive.
     * Isolates whether recursive() is needed to trigger staleness, or if plain flatMap suffices.
     */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void plainFlatMapDepth2(@ForAll("plainFlatMap2") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    /**
     * EVAL always created (same output), inner and outer flatMaps have high-cardinality noise.
     * Tests whether mere randomness (without structural variation) triggers staleness.
     */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void evalKeepWithHighNoise(@ForAll("evalKeepHighNoise") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    /**
     * oneOf choosing between EVAL (adds column z, new NameId) and identity (no new column).
     * This gives structural variation in the inner flatMap output schema.
     * High-cardinality noise ensures jqwik runs many tries.
     */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void oneOfStructuralVariation(@ForAll("oneOfStructural") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    /**
     * oneOf where BOTH options produce EVAL (same output schema, different NameIds per instance).
     * Tests whether oneOf itself triggers staleness, or if different output schemas are needed.
     */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void oneOfSameStructure(@ForAll("oneOfSame") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    @Provide
    Arbitrary<ValidatedPlan> plainFlatMap2() {
        var rel = LogicalPlanGenerator.buildEsRelation(FIXED_SCHEMA);
        return Arbitraries.just(rel)
            .flatMap(LogicalPlanGenerator::wrapLayer)
            .flatMap(LogicalPlanGenerator::wrapLayer)
            .map(plan -> new ValidatedPlan(plan, validateReferences(plan)));
    }

    @Provide
    Arbitrary<ValidatedPlan> evalKeepHighNoise() {
        var rel = LogicalPlanGenerator.buildEsRelation(FIXED_SCHEMA);
        return Arbitraries.just(rel).flatMap(r -> {
            var a = r.output().get(0);
            var add = new Add(Source.EMPTY, a, a, EsqlTestUtils.TEST_CFG);
            return Arbitraries.integers()
                .between(1, 1000)
                .map(ignore -> (LogicalPlan) new Eval(Source.EMPTY, r, List.of(new Alias(Source.EMPTY, "z", add))));
        }).flatMap(eval -> {
            var z = eval.output().stream().filter(attr -> attr.name().equals("z")).findFirst().orElseThrow();
            return Arbitraries.integers()
                .between(1, 1000)
                .map(ignore -> (LogicalPlan) new Keep(Source.EMPTY, eval, List.of((NamedExpression) z)));
        }).map(plan -> new ValidatedPlan(plan, validateReferences(plan)));
    }

    @Provide
    Arbitrary<ValidatedPlan> oneOfStructural() {
        var rel = LogicalPlanGenerator.buildEsRelation(FIXED_SCHEMA);
        return Arbitraries.just(rel).flatMap(r -> {
            var a = r.output().get(0);
            var add = new Add(Source.EMPTY, a, a, EsqlTestUtils.TEST_CFG);
            return Arbitraries.oneOf(
                // Option 1: EVAL (adds column z with fresh NameId)
                Arbitraries.integers()
                    .between(1, 100)
                    .map(i -> (LogicalPlan) new Eval(Source.EMPTY, r, List.of(new Alias(Source.EMPTY, "z", add)))),
                // Option 2: identity (no new column)
                Arbitraries.integers().between(1, 100).map(i -> r)
            );
        }).flatMap(plan -> {
            var projections = plan.output().stream().map(a -> (NamedExpression) a).toList();
            return Arbitraries.integers().between(1, 100).map(i -> (LogicalPlan) new Keep(Source.EMPTY, plan, projections));
        }).map(plan -> new ValidatedPlan(plan, validateReferences(plan)));
    }

    @Provide
    Arbitrary<ValidatedPlan> oneOfSame() {
        var rel = LogicalPlanGenerator.buildEsRelation(FIXED_SCHEMA);
        return Arbitraries.just(rel).flatMap(r -> {
            var a = r.output().get(0);
            var add = new Add(Source.EMPTY, a, a, EsqlTestUtils.TEST_CFG);
            return Arbitraries.oneOf(
                // Both options produce EVAL with same output schema, but different NameIds
                Arbitraries.integers()
                    .between(1, 100)
                    .map(i -> (LogicalPlan) new Eval(Source.EMPTY, r, List.of(new Alias(Source.EMPTY, "z", add)))),
                Arbitraries.integers()
                    .between(1, 100)
                    .map(i -> (LogicalPlan) new Eval(Source.EMPTY, r, List.of(new Alias(Source.EMPTY, "z", add))))
            );
        }).flatMap(eval -> {
            var z = eval.output().stream().filter(attr -> attr.name().equals("z")).findFirst().orElseThrow();
            return Arbitraries.integers()
                .between(1, 100)
                .map(i -> (LogicalPlan) new Keep(Source.EMPTY, eval, List.of((NamedExpression) z)));
        }).map(plan -> new ValidatedPlan(plan, validateReferences(plan)));
    }

    /**
     * Same as plainFlatMapDepth2, but validation happens INSIDE the property test,
     * not in a .map() wrapper. Tests whether the .map() wrapping affects staleness.
     */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void plainFlatMapDepth2_noMap(@ForAll("plainFlatMap2_raw") LogicalPlan plan) {
        var issues = validateReferences(plan);
        if (issues.isEmpty() == false) throw new AssertionError("Stale: " + issues + " plan: " + LogicalPlanPrinter.print(plan));
    }

    @Provide
    Arbitrary<LogicalPlan> plainFlatMap2_raw() {
        var rel = LogicalPlanGenerator.buildEsRelation(FIXED_SCHEMA);
        return Arbitraries.just(rel).flatMap(LogicalPlanGenerator::wrapLayer).flatMap(LogicalPlanGenerator::wrapLayer);
    }

    /**
     * Simplified wrapLayer with only identity, keep, and eval.
     * Uses same jqwik combinators (set(), oneOf) as the real wrapLayer.
     */
    private static Arbitrary<LogicalPlan> testWrapLayer(LogicalPlan current) {
        var available = current.output();
        var integerAttrs = available.stream().filter(a -> a.dataType() == DataType.INTEGER).toList();
        var options = new ArrayList<Arbitrary<LogicalPlan>>();
        options.add(Arbitraries.just(current)); // identity
        options.add(
            // keep: non-empty subset
            Arbitraries.of(available)
                .set()
                .ofMinSize(1)
                .ofMaxSize(available.size())
                .map(set -> (LogicalPlan) new Keep(Source.EMPTY, current, List.copyOf(set).stream().map(a -> (NamedExpression) a).toList()))
        );
        if (integerAttrs.isEmpty() == false) {
            options.add(
                // eval: z = attr + attr
                Arbitraries.of(integerAttrs).map(attr -> {
                    var add = new Add(Source.EMPTY, attr, attr, EsqlTestUtils.TEST_CFG);
                    return (LogicalPlan) new Eval(Source.EMPTY, current, List.of(new Alias(Source.EMPTY, "z", add)));
                })
            );
        }
        return Arbitraries.oneOf(options);
    }

    /**
     * Deep instrumentation: logs identity hashes and NameIds through the flatMap chain.
     * On stale detection, dumps the full object graph to pinpoint the mechanism.
     */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void instrumentedFlatMapDepth2(@ForAll("instrumentedFlatMap2") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    private static final AtomicInteger STALE_TRACE_COUNT = new AtomicInteger();
    private static final AtomicInteger INNER_MAPPER_CALLS = new AtomicInteger();
    private static final AtomicInteger OUTER_MAPPER_CALLS = new AtomicInteger();

    @Provide
    Arbitrary<ValidatedPlan> instrumentedFlatMap2() {
        STALE_TRACE_COUNT.set(0);
        INNER_MAPPER_CALLS.set(0);
        OUTER_MAPPER_CALLS.set(0);
        var rel = LogicalPlanGenerator.buildEsRelation(FIXED_SCHEMA);
        return Arbitraries.just(rel).flatMap(r -> {
            int call = INNER_MAPPER_CALLS.incrementAndGet();
            if (call <= 5) {
                System.err.println("  INNER flatMap #" + call + ": rel@" + System.identityHashCode(r));
            }
            return LogicalPlanGenerator.wrapLayer(r);
        }).flatMap(plan -> {
            int call = OUTER_MAPPER_CALLS.incrementAndGet();
            var ids = plan.output().stream().map(a -> a.name() + ":id=" + a.id()).toList();
            if (call <= 5) {
                System.err.println(
                    "  OUTER flatMap #"
                        + call
                        + ": plan@"
                        + System.identityHashCode(plan)
                        + " class="
                        + plan.getClass().getSimpleName()
                        + " output="
                        + ids
                );
            }
            return LogicalPlanGenerator.wrapLayer(plan);
        }).map(plan -> {
            var issues = validateReferences(plan);
            if (issues.isEmpty() == false && STALE_TRACE_COUNT.incrementAndGet() <= 3) {
                System.err.println("=== STALE DETECTION #" + STALE_TRACE_COUNT.get() + " ===");
                System.err.println("Plan: " + LogicalPlanPrinter.print(plan));
                System.err.println("Issues: " + issues);
                dumpPlanGraph(plan, 0);
                new Exception("Stack trace").printStackTrace(System.err);
                System.err.println("=== END ===");
            }
            return new ValidatedPlan(plan, issues);
        });
    }

    private static void dumpPlanGraph(LogicalPlan plan, int depth) {
        var indent = "  ".repeat(depth);
        var ids = plan.output().stream().map(a -> a.name() + ":id=" + a.id() + "@" + System.identityHashCode(a)).toList();
        System.err.println(indent + plan.getClass().getSimpleName() + "@" + System.identityHashCode(plan) + " output=" + ids);
        if (plan instanceof Keep keep) {
            var projIds = keep.projections().stream().map(ne -> ne.name() + ":id=" + ne.id() + "@" + System.identityHashCode(ne)).toList();
            System.err.println(indent + "  projections=" + projIds);
        }
        if (plan instanceof Eval eval) {
            var fieldIds = eval.fields()
                .stream()
                .map(a -> a.name() + ":id=" + a.id() + " toAttr@" + System.identityHashCode(a.toAttribute()))
                .toList();
            System.err.println(indent + "  fields=" + fieldIds);
        }
        if (plan instanceof Filter filter) {
            var condAttrs = collectExprAttrs(filter.condition()).stream()
                .map(a -> a.name() + ":id=" + a.id() + "@" + System.identityHashCode(a))
                .toList();
            System.err.println(indent + "  conditionAttrs=" + condAttrs);
        }
        if (plan instanceof OrderBy ob) {
            var orderAttrs = ob.order()
                .stream()
                .flatMap(o -> collectExprAttrs(o.child()).stream())
                .map(a -> a.name() + ":id=" + a.id() + "@" + System.identityHashCode(a))
                .toList();
            System.err.println(indent + "  orderAttrs=" + orderAttrs);
        }
        if (plan instanceof UnaryPlan unary) {
            dumpPlanGraph(unary.child(), depth + 1);
        }
    }

    /**
     * FROM | EVAL z = a + n | KEEP z. No shrinking, no wrapLayer.
     * Randomness only in the literal 'n' (1..10), giving jqwik something to vary.
     */
    @Property(tries = 1000, shrinking = ShrinkingMode.OFF, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void fromEvalKeepNoShrinking(@ForAll("fromEvalKeep") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    /** Step 1: wrapLayer chain, no bug, no shrinking. */
    @Property(tries = 1000, shrinking = ShrinkingMode.OFF, edgeCases = EdgeCasesMode.NONE, seed = "12345")
    @PerProperty(ReportStaleness.class)
    void step1_noBugNoShrinking(@ForAll("wrapLayerPlans") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    /** Step 2: wrapLayer chain, bug, no shrinking. */
    @Property(tries = 1000, shrinking = ShrinkingMode.OFF, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void step2_bugNoShrinking(@ForAll("wrapLayerPlans") ValidatedPlan vp) throws IOException {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
        var rel = findEsRelation(vp.plan);
        var schema = schemaFromRelation(rel);
        var data = fixedData(schema);
        var correct = new Simulator(schema, data, SimBug.BUG_FREE).simulate(vp.plan);
        var bugged = new Simulator(schema, data, SimBug.ADD_IS_SUB).simulate(vp.plan);
        if (correct.equals(bugged) == false) throw new AssertionError("Divergence");
    }

    /** Step 3: wrapLayer chain, bug, shrinking ON. */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void step3_bugWithShrinking(@ForAll("wrapLayerPlans") ValidatedPlan vp) throws IOException {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
        var rel = findEsRelation(vp.plan);
        var schema = schemaFromRelation(rel);
        var data = fixedData(schema);
        var correct = new Simulator(schema, data, SimBug.BUG_FREE).simulate(vp.plan);
        var bugged = new Simulator(schema, data, SimBug.ADD_IS_SUB).simulate(vp.plan);
        if (correct.equals(bugged) == false) throw new AssertionError("Divergence");
    }

    @Provide
    Arbitrary<ValidatedPlan> wrapLayerPlans() {
        var rel = LogicalPlanGenerator.buildEsRelation(FIXED_SCHEMA);
        return Arbitraries.just(rel)
            .flatMap(LogicalPlanGenerator::wrapLayer)
            .flatMap(LogicalPlanGenerator::wrapLayer)
            .map(plan -> new ValidatedPlan(plan, validateReferences(plan)));
    }

    /** Old test kept for reference. */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void fromEvalKeepBugWithShrinking(@ForAll("fromEvalKeepBugged") ValidatedPlan vp) throws IOException {
        var rel = findEsRelation(vp.plan);
        var schema = schemaFromRelation(rel);
        var data = fixedData(schema);
        var correct = new Simulator(schema, data, SimBug.BUG_FREE).simulate(vp.plan);
        var bugged = new Simulator(schema, data, SimBug.ADD_IS_SUB).simulate(vp.plan);
        if (correct.equals(bugged) == false) {
            throw new AssertionError("Divergence");
        }
    }

    @Provide
    Arbitrary<ValidatedPlan> fromEvalKeep() {
        return fromEvalKeepArbitrary();
    }

    @Provide
    Arbitrary<ValidatedPlan> fromEvalKeepBugged() {
        return fromEvalKeepArbitrary();
    }

    private static Arbitrary<ValidatedPlan> fromEvalKeepArbitrary() {
        var rel = LogicalPlanGenerator.buildEsRelation(FIXED_SCHEMA);
        return Arbitraries.just(rel).flatMap(r -> {
            var a = r.output().get(0);
            return Arbitraries.integers().between(1, 10).map(n -> {
                var add = new Add(Source.EMPTY, a, new Literal(Source.EMPTY, n, DataType.INTEGER), EsqlTestUtils.TEST_CFG);
                return (LogicalPlan) new Eval(Source.EMPTY, r, List.of(new Alias(Source.EMPTY, "z", add)));
            });
        }).flatMap(eval -> {
            var z = eval.output().stream().filter(attr -> attr.name().equals("z")).findFirst().orElseThrow();
            return Arbitraries.just((LogicalPlan) new Keep(Source.EMPTY, eval, List.of((NamedExpression) z)));
        }).map(plan -> new ValidatedPlan(plan, validateReferences(plan)));
    }

    /** wrapLayer for INNER, simple KEEP for outer. Isolates inner wrapLayer. */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void wrapLayerInnerOnly(@ForAll("wrapInner") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    /** Simple EVAL for inner, wrapLayer for OUTER. Isolates outer wrapLayer. */
    @Property(tries = 1000, edgeCases = EdgeCasesMode.NONE)
    @PerProperty(ReportStaleness.class)
    void wrapLayerOuterOnly(@ForAll("wrapOuter") ValidatedPlan vp) {
        if (vp.issues.isEmpty() == false) throw new AssertionError("Stale: " + vp);
    }

    @Provide
    Arbitrary<ValidatedPlan> wrapInner() {
        var rel = LogicalPlanGenerator.buildEsRelation(FIXED_SCHEMA);
        return Arbitraries.just(rel).flatMap(LogicalPlanGenerator::wrapLayer).flatMap(plan -> {
            var projections = plan.output().stream().map(a -> (NamedExpression) a).toList();
            return Arbitraries.just((LogicalPlan) new Keep(Source.EMPTY, plan, projections));
        }).map(plan -> new ValidatedPlan(plan, validateReferences(plan)));
    }

    @Provide
    Arbitrary<ValidatedPlan> wrapOuter() {
        var rel = LogicalPlanGenerator.buildEsRelation(FIXED_SCHEMA);
        return Arbitraries.just(rel).flatMap(r -> {
            var a = r.output().get(0);
            var add = new Add(Source.EMPTY, a, a, EsqlTestUtils.TEST_CFG);
            return Arbitraries.integers()
                .between(1, 100)
                .map(i -> (LogicalPlan) new Eval(Source.EMPTY, r, List.of(new Alias(Source.EMPTY, "z", add))));
        }).flatMap(LogicalPlanGenerator::wrapLayer).map(plan -> new ValidatedPlan(plan, validateReferences(plan)));
    }

    private static Arbitrary<SimSchema> schemasWithInteger() {
        return SimSchemaGenerator.schemas().filter(s -> s.columns().stream().anyMatch(c -> c.type() == DataType.INTEGER));
    }

    private static EsRelation findEsRelation(LogicalPlan plan) {
        if (plan instanceof EsRelation rel) return rel;
        if (plan instanceof UnaryPlan unary) return findEsRelation(unary.child());
        throw new IllegalArgumentException("No EsRelation found in plan: " + plan);
    }

    private static SimSchema schemaFromRelation(EsRelation rel) {
        var cols = rel.output().stream().map(a -> new SimSchema.SimColumn(a.name(), a.dataType())).toList();
        return new SimSchema(rel.indexPattern(), cols);
    }

    /** Fixed data with non-zero integers to ensure ADD_IS_SUB bug is detectable. */
    private static List<Map<String, Object>> fixedData(SimSchema schema) {
        var row = new java.util.LinkedHashMap<String, Object>();
        for (var col : schema.columns()) {
            row.put(col.name(), col.type() == DataType.INTEGER ? 3 : "foo");
        }
        return List.of(row);
    }
}
