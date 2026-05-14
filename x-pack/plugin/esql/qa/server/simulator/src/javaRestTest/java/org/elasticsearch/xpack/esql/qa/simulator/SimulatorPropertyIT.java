/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import org.apache.http.HttpHost;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.WarningsHandler;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.cluster.local.distribution.DistributionType;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.Order;
import org.elasticsearch.xpack.esql.expression.function.aggregate.AggregateFunction;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.Drop;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;
import org.elasticsearch.xpack.esql.plan.logical.Row;
import org.elasticsearch.xpack.esql.plan.logical.UnaryPlan;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Integration property test: generates random plans and data, then compares simulator results against a real ES cluster. */
@RunWith(JUnitQuickcheck.class)
@TestLogging(value = "org.elasticsearch.xpack.esql:TRACE", reason = "debug")
public class SimulatorPropertyIT {
    // initLogging() must run before any LogManager.getLogger() call — it loads Log4j2 plugins
    // needed by the log4j2-test.properties pattern (e.g. %test_thread_info).
    static {
        SimulatorTestUtils.initLogging();
        SimulatorTestUtils.applyTestLogging(SimulatorPropertyIT.class);
    }

    private static final Logger logger = LogManager.getLogger(SimulatorPropertyIT.class);

    @ClassRule
    public static ElasticsearchCluster cluster = ElasticsearchCluster.local()
        .distribution(DistributionType.DEFAULT)
        .setting("xpack.security.enabled", "false")
        .setting("xpack.license.self_generated.type", "trial")
        .shared(true)
        .build();

    private static RestClient restClient;

    @BeforeClass
    public static void connectClient() {
        String[] addresses = cluster.getHttpAddresses().split(",");
        HttpHost[] hosts = new HttpHost[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            String addr = addresses[i].trim();
            int lastColon = addr.lastIndexOf(':');
            String host = addr.substring(0, lastColon);
            int port = Integer.parseInt(addr.substring(lastColon + 1));
            hosts[i] = new HttpHost(host, port);
        }
        restClient = RestClient.builder(hosts).build();
    }

    @AfterClass
    public static void disconnectClient() throws Exception {
        if (restClient != null) {
            restClient.close();
            restClient = null;
        }
    }

    /** A test case: {@code schema} and {@code data} are null for ROW plans (no index needed), non-null for FROM plans. */
    record TestCase(@Nullable SimSchema schema, @Nullable List<Map<String, Object>> data, LogicalPlan plan, String query) {
        TestCase {
            assert (schema == null) == (data == null);
        }

        @Override
        public String toString() {
            if (schema == null) {
                return Strings.format("%s [ROW plan]", query);
            }
            return Strings.format("%s [%s, %s rows]", query, schema, data.size());
        }
    }

    private static int trialCount;

    // maxShrinks counts every property check during shrinking (not just accepted shrinks); a single
    // shrink step can generate dozens of candidates, so the default of 100 runs out fast.
    @Property(trials = 50, maxShrinks = 5000, maxShrinkDepth = 100, maxShrinkTime = 120000)
    public void simulatorMatchesEs(@From(TestCaseGenerator.class) TestCase tc) throws Exception {
        int trial = ++trialCount;
        logger.info("[Trial {}] {}", trial, tc);
        boolean needsIndex = tc.schema() != null;

        if (needsIndex) {
            // Delete first — a previously crashed trial may have left the index behind
            deleteIndex(tc.schema().indexName());
            createIndex(tc.schema());
        }
        try {
            if (needsIndex) {
                indexData(tc.schema(), tc.data());
            }

            Simulator simulator = needsIndex ? new Simulator(tc.schema(), tc.data()) : new Simulator();
            Result simResult = simulator.simulate(tc.plan());

            Map<String, Object> esResponse;
            try {
                esResponse = runEsqlQuery(tc.query());
            } catch (ResponseException e) {
                // ES may return 500 for certain overflow/edge cases (e.g., LONG overflow in expressions).
                // These are ES-side bugs, not simulator mismatches — skip this trial.
                if (e.getResponse().getStatusLine().getStatusCode() == 500) {
                    logger.warn("ES returned 500 for query [{}], skipping: {}", tc.query(), e.getMessage());
                    return;
                }
                throw e;
            }
            Result esResult = responseToResult(esResponse);

            List<Simulator.Column> simColumns = sortedColumns(simResult.columns());
            List<Simulator.Column> esCols = sortedColumns(esResult.columns());

            if (simColumns.size() != esCols.size()) {
                throw new AssertionError(
                    Strings.format(
                        "Column count mismatch for query [%s]: simulator=%s es=%s\nSimulator columns: %s\nES columns: %s",
                        tc.query(),
                        simColumns.size(),
                        esCols.size(),
                        columnNames(simColumns),
                        columnNames(esCols)
                    )
                );
            }

            for (int c = 0; c < simColumns.size(); c++) {
                Simulator.Column simCol = simColumns.get(c);
                Simulator.Column esCol = esCols.get(c);

                if (simCol.name().equals(esCol.name()) == false) {
                    throw new AssertionError(
                        Strings.format(
                            "Column name mismatch at index %s for query [%s]: simulator=%s es=%s",
                            c,
                            tc.query(),
                            simCol.name(),
                            esCol.name()
                        )
                    );
                }
            }

            List<Order> effectiveSort = findEffectiveSort(tc.plan());
            if (effectiveSort.isEmpty() == false) {
                try {
                    verifySortOrder(effectiveSort, simResult, "simulator", tc.query());
                    verifySortOrder(effectiveSort, esResult, "ES", tc.query());
                } catch (IllegalArgumentException e) {
                    // Safe to skip: sort key was dropped by KEEP/DROP after SORT, so we can't verify
                    // order — but the multiset row comparison below still catches data mismatches.
                    if (e.getMessage() == null || e.getMessage().startsWith("Column not found:") == false) {
                        throw e;
                    }
                }
            }

            // Compare rows as multisets: sort both sides by all columns to handle
            // tied sort keys and no-SORT cases where row order is nondeterministic.
            List<List<Object>> simRows = extractRows(simColumns);
            List<List<Object>> esRows = extractRows(esCols);
            simRows.sort(SimulatorPropertyIT::compareRows);
            esRows.sort(SimulatorPropertyIT::compareRows);

            if (simRows.equals(esRows) == false) {
                throw new AssertionError(
                    Strings.format(
                        "Row mismatch for query [%s] with %s and data %s:\nsimulator=%s\nes=%s",
                        tc.query(),
                        tc.schema(),
                        tc.data(),
                        simRows,
                        esRows
                    )
                );
            }
        } finally {
            if (needsIndex) {
                deleteIndex(tc.schema().indexName());
            }
        }
    }

    public static class TestCaseGenerator extends Generator<TestCase> {
        // Static: seed is applied once across all generator instances so subsequent trials explore new territory
        private static boolean seedApplied;

        public TestCaseGenerator() {
            super(TestCase.class);
        }

        @Override
        public TestCase generate(SourceOfRandomness random, GenerationStatus status) {
            String seedProp = System.getProperty("simulator.seed");
            if (seedProp != null && seedApplied == false) {
                random.setSeed(Long.parseLong(seedProp));
                seedApplied = true;
            }
            SimSchema schema = SimSchemaGenerator.generate(random);
            LogicalPlan plan = LogicalPlanGenerator.generate(schema, random);
            if (isRowPlan(plan)) {
                return new TestCase(null, null, plan, LogicalPlanPrinter.print(plan));
            }
            List<Map<String, Object>> data = SimDataGenerator.generate(schema, random);
            return new TestCase(schema, data, plan, LogicalPlanPrinter.print(plan));
        }

        @Override
        public List<TestCase> doShrink(SourceOfRandomness random, TestCase larger) {
            logger.info("[Shrink] current failing case: {}", larger);
            List<TestCase> candidates = new ArrayList<>();

            // Strategy 1: Drop outermost pipeline layer
            // InlineStats wraps an Aggregate as its child — the actual plan below is aggregate.child().
            if (larger.plan() instanceof InlineStats is) {
                addPlanCandidate(candidates, larger, LogicalPlanGenerator.resolveReferences(is.aggregate().child()));
            } else if (larger.plan() instanceof UnaryPlan unary) {
                addPlanCandidate(candidates, larger, LogicalPlanGenerator.resolveReferences(unary.child()));
            }

            // Strategy 2: Remove operators from the MIDDLE of the pipeline. Keep/Drop/Filter are always
            // safe (they don't introduce attributes). InlineStats/Aggregate introduce attributes, so
            // only drop them when no downstream stage references those names.
            List<LogicalPlan> stages = flattenStages(larger.plan());
            for (int i = 1; i < stages.size() - 1; i++) { // skip outermost (Strategy 1) and leaf (FROM/ROW)
                if (isSafeToRemoveFromMiddle(stages.get(i), stages.subList(0, i))) {
                    List<LogicalPlan> reduced = new ArrayList<>(stages);
                    reduced.remove(i);
                    addPlanCandidate(candidates, larger, LogicalPlanGenerator.resolveReferences(rebuildFromStages(reduced)));
                }
            }

            // Strategy 3: Remove individual EVAL fields (keep N-1)
            if (larger.plan() instanceof Eval eval && eval.fields().size() > 1) {
                for (int f = 0; f < eval.fields().size(); f++) {
                    List<Alias> reduced = new ArrayList<>(eval.fields());
                    reduced.remove(f);
                    addPlanCandidate(
                        candidates,
                        larger,
                        LogicalPlanGenerator.resolveReferences(new Eval(eval.source(), eval.child(), reduced))
                    );
                }
            }

            // Strategy 4: Remove individual aggregates from INLINE STATS / STATS (keep N-1)
            removeIndividualAggregates(candidates, larger);

            // Strategy 4b: Shrink the BY clause of the root STATS / INLINE STATS — drop the entire
            // BY, or drop one grouping at a time. Root-only mirrors strategies 3 and 4: avoids the
            // downstream-reference checks middle-stage shrinks require.
            addGroupingShrinks(candidates, larger);

            // Strategy 5: Simplify expressions to sub-expressions (all stages)
            addExpressionShrinks(candidates, larger);

            // Strategy 5b: Simplify sub-expressions to literals (all stages, recursive)
            addLiteralShrinks(candidates, larger);

            // Strategy 6: Remove any data row (not just last)
            if (larger.data() != null && larger.data().size() > 1) {
                for (int r = 0; r < larger.data().size(); r++) {
                    List<Map<String, Object>> smaller = new ArrayList<>(larger.data());
                    smaller.remove(r);
                    candidates.add(new TestCase(larger.schema(), smaller, larger.plan(), larger.query()));
                }
            }

            // Strategy 7: Fill in a null (absent) field with a non-null value
            if (larger.data() != null) {
                for (int r = 0; r < larger.data().size(); r++) {
                    Map<String, Object> row = larger.data().get(r);
                    for (SimSchema.SimColumn col : larger.schema().columns()) {
                        if (row.containsKey(col.name()) == false) {
                            List<Map<String, Object>> filledData = larger.data()
                                .stream()
                                .<Map<String, Object>>map(LinkedHashMap::new)
                                .toList();
                            Object fillValue = switch (col.type()) {
                                case INTEGER -> 1;
                                case LONG -> 1L;
                                case DOUBLE -> 1.0;
                                case KEYWORD -> "foo";
                                default -> throw new UnsupportedOperationException("Unsupported type: " + col.type());
                            };
                            filledData.get(r).put(col.name(), fillValue);
                            candidates.add(new TestCase(larger.schema(), filledData, larger.plan(), larger.query()));
                            break; // Only try the first null per row to limit candidate explosion
                        }
                    }
                }
            }

            // Strategy 7.5: Shrink numeric values in data toward smaller magnitudes
            if (larger.data() != null) {
                for (int r = 0; r < larger.data().size(); r++) {
                    for (Map.Entry<String, Object> entry : larger.data().get(r).entrySet()) {
                        if (entry.getValue() instanceof Integer intVal) {
                            for (int candidate : intShrinkCandidates(intVal)) {
                                List<Map<String, Object>> shrunkData = larger.data()
                                    .stream()
                                    .<Map<String, Object>>map(LinkedHashMap::new)
                                    .toList();
                                shrunkData.get(r).put(entry.getKey(), candidate);
                                candidates.add(new TestCase(larger.schema(), shrunkData, larger.plan(), larger.query()));
                            }
                        } else if (entry.getValue() instanceof Long longVal) {
                            for (long candidate : longShrinkCandidates(longVal)) {
                                List<Map<String, Object>> shrunkData = larger.data()
                                    .stream()
                                    .<Map<String, Object>>map(LinkedHashMap::new)
                                    .toList();
                                shrunkData.get(r).put(entry.getKey(), candidate);
                                candidates.add(new TestCase(larger.schema(), shrunkData, larger.plan(), larger.query()));
                            }
                        } else if (entry.getValue() instanceof Double doubleVal) {
                            for (double candidate : doubleShrinkCandidates(doubleVal)) {
                                List<Map<String, Object>> shrunkData = larger.data()
                                    .stream()
                                    .<Map<String, Object>>map(LinkedHashMap::new)
                                    .toList();
                                shrunkData.get(r).put(entry.getKey(), candidate);
                                candidates.add(new TestCase(larger.schema(), shrunkData, larger.plan(), larger.query()));
                            }
                        }
                    }
                }
            }

            // Strategy 7.6: Shrink numeric literals in the plan toward smaller magnitudes
            addNumericLiteralShrinks(candidates, larger);

            // Strategy 8: Remove unused schema columns
            if (larger.schema() != null && larger.schema().columns().size() > 1) {
                for (int c = 0; c < larger.schema().columns().size(); c++) {
                    String colName = larger.schema().columns().get(c).name();
                    List<SimSchema.SimColumn> reducedCols = new ArrayList<>(larger.schema().columns());
                    reducedCols.remove(c);
                    SimSchema reducedSchema = new SimSchema(larger.schema().indexName(), reducedCols);
                    List<Map<String, Object>> reducedData = larger.data().stream().<Map<String, Object>>map(row -> {
                        var copy = new LinkedHashMap<>(row);
                        copy.remove(colName);
                        return copy;
                    }).toList();
                    candidates.add(new TestCase(reducedSchema, reducedData, larger.plan(), larger.query()));
                }
            }
            return candidates;
        }

        private static void addPlanCandidate(List<TestCase> candidates, TestCase original, LogicalPlan plan) {
            candidates.add(new TestCase(original.schema(), original.data(), plan, LogicalPlanPrinter.print(plan)));
        }

        /** Tries replacing expressions with sub-expressions in all stages. */
        private static void addExpressionShrinks(List<TestCase> candidates, TestCase larger) {
            addExpressionShrinksWith(candidates, larger, TestCaseGenerator::collectSubExpressions);
        }

        /** Tries replacing sub-expressions with literals in all stages. */
        private static void addLiteralShrinks(List<TestCase> candidates, TestCase larger) {
            addExpressionShrinksWith(candidates, larger, TestCaseGenerator::literalSimplifications);
        }

        /**
         * Generic expression shrinking: for each stage in the pipeline, tries replacing
         * expressions using the given candidate generator function.
         */
        private static void addExpressionShrinksWith(
            List<TestCase> candidates,
            TestCase larger,
            Function<Expression, List<Expression>> exprCandidates
        ) {
            List<LogicalPlan> stages = flattenStages(larger.plan());
            for (int s = 0; s < stages.size() - 1; s++) { // skip leaf (FROM/ROW)
                for (LogicalPlan modified : stageExpressionCandidates(stages.get(s), exprCandidates)) {
                    List<LogicalPlan> newStages = new ArrayList<>(stages);
                    newStages.set(s, modified);
                    addPlanCandidate(candidates, larger, LogicalPlanGenerator.resolveReferences(rebuildFromStages(newStages)));
                }
            }
        }

        /** Returns candidate stages where one expression has been replaced using the given function. */
        private static List<LogicalPlan> stageExpressionCandidates(
            LogicalPlan stage,
            Function<Expression, List<Expression>> exprCandidates
        ) {
            List<LogicalPlan> result = new ArrayList<>();
            switch (stage) {
                case Eval eval -> {
                    for (int f = 0; f < eval.fields().size(); f++) {
                        Alias alias = eval.fields().get(f);
                        for (Expression sub : exprCandidates.apply(alias.child())) {
                            List<Alias> newFields = new ArrayList<>(eval.fields());
                            newFields.set(f, new Alias(alias.source(), alias.name(), sub));
                            result.add(new Eval(eval.source(), eval.child(), newFields));
                        }
                    }
                }
                case Filter filter -> {
                    for (Expression sub : exprCandidates.apply(filter.condition())) {
                        result.add(new Filter(filter.source(), filter.child(), sub));
                    }
                }
                case OrderBy orderBy -> {
                    for (int k = 0; k < orderBy.order().size(); k++) {
                        Order order = orderBy.order().get(k);
                        for (Expression sub : exprCandidates.apply(order.child())) {
                            List<Order> newOrders = new ArrayList<>(orderBy.order());
                            newOrders.set(k, new Order(order.source(), sub, order.direction(), order.nullsPosition()));
                            result.add(new OrderBy(orderBy.source(), orderBy.child(), newOrders));
                        }
                    }
                }
                case InlineStats is -> {
                    Aggregate agg = is.aggregate();
                    for (Aggregate modified : aggExpressionCandidates(agg, exprCandidates)) {
                        result.add(new InlineStats(is.source(), modified));
                    }
                }
                case Aggregate agg -> result.addAll(aggExpressionCandidates(agg, exprCandidates));
                default -> {
                }
            }
            return result;
        }

        /** Returns candidate Aggregates where one aggregate field expression has been replaced. */
        private static List<Aggregate> aggExpressionCandidates(Aggregate agg, Function<Expression, List<Expression>> exprCandidates) {
            List<Aggregate> result = new ArrayList<>();
            for (int a = 0; a < agg.aggregates().size(); a++) {
                if (agg.aggregates().get(a) instanceof Alias alias && alias.child() instanceof AggregateFunction aggFunc) {
                    for (Expression sub : exprCandidates.apply(aggFunc.field())) {
                        List<NamedExpression> newAggs = new ArrayList<>(agg.aggregates());
                        newAggs.set(a, new Alias(alias.source(), alias.name(), aggFunc.withField(sub)));
                        result.add(new Aggregate(agg.source(), agg.child(), agg.groupings(), newAggs));
                    }
                }
            }
            return result;
        }

        /** Tries removing individual aggregate aliases from INLINE STATS or STATS. */
        private static void removeIndividualAggregates(List<TestCase> candidates, TestCase larger) {
            Aggregate agg;
            if (larger.plan() instanceof InlineStats is) {
                agg = is.aggregate();
            } else if (larger.plan() instanceof Aggregate aggregate) {
                agg = aggregate;
            } else {
                return;
            }
            // Aggregate aliases come before grouping keys in the aggregates list
            int numGroupings = agg.groupings().size();
            int numAggAliases = agg.aggregates().size() - numGroupings;
            if (numAggAliases <= 1) {
                return;
            }
            for (int i = 0; i < numAggAliases; i++) {
                List<NamedExpression> reduced = new ArrayList<>(agg.aggregates());
                reduced.remove(i);
                Aggregate newAgg = new Aggregate(agg.source(), agg.child(), agg.groupings(), reduced);
                addPlanCandidate(candidates, larger, LogicalPlanGenerator.resolveReferences(wrapAggregate(larger.plan(), newAgg)));
            }
        }

        /** Wraps a new Aggregate in InlineStats if the original plan was InlineStats, otherwise returns it as-is. */
        private static LogicalPlan wrapAggregate(LogicalPlan original, Aggregate newAgg) {
            return original instanceof InlineStats is ? new InlineStats(is.source(), newAgg) : newAgg;
        }

        /** Drops the BY clause entirely, then each grouping individually, from the root Aggregate / InlineStats. */
        private static void addGroupingShrinks(List<TestCase> candidates, TestCase larger) {
            Aggregate agg;
            if (larger.plan() instanceof InlineStats is) {
                agg = is.aggregate();
            } else if (larger.plan() instanceof Aggregate aggregate) {
                agg = aggregate;
            } else {
                return;
            }
            if (agg.groupings().isEmpty()) {
                return;
            }
            addPlanCandidate(candidates, larger, LogicalPlanGenerator.resolveReferences(wrapAggregate(larger.plan(), dropAllGroupings(agg))));
            // With a single grouping, drop-one collapses to drop-all (already added above).
            if (agg.groupings().size() > 1) {
                for (int g = 0; g < agg.groupings().size(); g++) {
                    addPlanCandidate(
                        candidates,
                        larger,
                        LogicalPlanGenerator.resolveReferences(wrapAggregate(larger.plan(), dropGroupingAt(agg, g)))
                    );
                }
            }
        }

        /**
         * {@code agg.aggregates()} ends with one bare {@link Attribute} per grouping (mirroring
         * {@code agg.groupings()} in order); these helpers preserve that invariant when removing groupings.
         */
        private static Aggregate dropAllGroupings(Aggregate agg) {
            int numAliases = agg.aggregates().size() - agg.groupings().size();
            return new Aggregate(agg.source(), agg.child(), List.of(), List.copyOf(agg.aggregates().subList(0, numAliases)));
        }

        private static Aggregate dropGroupingAt(Aggregate agg, int index) {
            List<Expression> newGroupings = new ArrayList<>(agg.groupings());
            newGroupings.remove(index);
            int numAliases = agg.aggregates().size() - agg.groupings().size();
            List<NamedExpression> newAggs = new ArrayList<>(agg.aggregates());
            newAggs.remove(numAliases + index);
            return new Aggregate(agg.source(), agg.child(), newGroupings, newAggs);
        }

        /** Flattens the plan into pipeline stages (root first, FROM/ROW last). InlineStats skips its inner Aggregate. */
        private static List<LogicalPlan> flattenStages(LogicalPlan plan) {
            List<LogicalPlan> stages = new ArrayList<>();
            LogicalPlan current = plan;
            while (current instanceof UnaryPlan unary) {
                stages.add(current);
                if (current instanceof InlineStats is) {
                    current = is.aggregate().child();
                } else {
                    current = unary.child();
                }
            }
            stages.add(current); // FROM or ROW
            return stages;
        }

        /** Rebuilds a plan from bottom (last) to top (first), reconnecting stages. */
        private static LogicalPlan rebuildFromStages(List<LogicalPlan> stages) {
            LogicalPlan rebuilt = stages.getLast(); // FROM or ROW
            for (int j = stages.size() - 2; j >= 0; j--) {
                LogicalPlan stage = stages.get(j);
                if (stage instanceof InlineStats is) {
                    Aggregate agg = is.aggregate();
                    Aggregate newAgg = new Aggregate(agg.source(), rebuilt, agg.groupings(), agg.aggregates());
                    rebuilt = new InlineStats(is.source(), newAgg);
                } else if (stage instanceof Aggregate agg) {
                    rebuilt = new Aggregate(agg.source(), rebuilt, agg.groupings(), agg.aggregates());
                } else {
                    rebuilt = ((UnaryPlan) stage).replaceChild(rebuilt);
                }
            }
            return rebuilt;
        }

        /**
         * Returns true if {@code stage} can be removed from the middle of the pipeline.
         * Keep/Drop/Filter are always safe — they don't introduce attributes. InlineStats and
         * Aggregate introduce attributes (the aggregate aliases), so they're only safe to remove
         * when no downstream stage in {@code upstreamStages} references any introduced name.
         */
        private static boolean isSafeToRemoveFromMiddle(LogicalPlan stage, List<LogicalPlan> upstreamStages) {
            if (stage instanceof Keep || stage instanceof Drop || stage instanceof Filter) {
                return true;
            }
            Aggregate agg = stage instanceof InlineStats is ? is.aggregate() : stage instanceof Aggregate a ? a : null;
            if (agg == null) {
                return false;
            }
            Set<String> introduced = agg.aggregates()
                .stream()
                .filter(ne -> ne instanceof Alias)
                .map(NamedExpression::name)
                .collect(Collectors.toSet());
            for (LogicalPlan upstream : upstreamStages) {
                for (Attribute ref : SimulatorTestUtils.collectAttributeReferences(upstream)) {
                    if (introduced.contains(ref.name())) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** Recursively collects all sub-expressions (children and their descendants). */
        private static List<Expression> collectSubExpressions(Expression expr) {
            List<Expression> subs = new ArrayList<>();
            collectSubExpressionsRecursive(subs, expr);
            return subs;
        }

        private static void collectSubExpressionsRecursive(List<Expression> out, Expression expr) {
            for (Expression child : expr.children()) {
                out.add(child);
                collectSubExpressionsRecursive(out, child);
            }
        }

        /**
         * Returns candidate expressions where exactly one non-literal sub-expression
         * has been replaced with a literal of matching type, preserving the surrounding structure.
         */
        private static List<Expression> literalSimplifications(Expression expr) {
            List<Expression> candidates = new ArrayList<>();
            addLiteralSimplificationsRecursive(candidates, expr);
            return candidates;
        }

        private static void addLiteralSimplificationsRecursive(List<Expression> candidates, Expression expr) {
            List<Expression> children = expr.children();
            for (int i = 0; i < children.size(); i++) {
                Expression child = children.get(i);
                if (child instanceof Literal) continue;

                // Try replacing this child with a literal
                Literal lit = literalForType(child.dataType());
                if (lit != null) {
                    List<Expression> newChildren = new ArrayList<>(children);
                    newChildren.set(i, lit);
                    candidates.add(expr.replaceChildren(newChildren));
                }

                // Recursively try simplifying within this child
                List<Expression> innerCandidates = new ArrayList<>();
                addLiteralSimplificationsRecursive(innerCandidates, child);
                for (Expression simplifiedChild : innerCandidates) {
                    List<Expression> newChildren = new ArrayList<>(children);
                    newChildren.set(i, simplifiedChild);
                    candidates.add(expr.replaceChildren(newChildren));
                }
            }
        }

        private static Literal literalForType(DataType type) {
            return switch (type) {
                case INTEGER -> new Literal(Source.EMPTY, 1, DataType.INTEGER);
                case LONG -> new Literal(Source.EMPTY, 1L, DataType.LONG);
                case DOUBLE -> new Literal(Source.EMPTY, 1.0, DataType.DOUBLE);
                case BOOLEAN -> new Literal(Source.EMPTY, true, DataType.BOOLEAN);
                default -> null; // Can't simplify unknown types
            };
        }

        /**
         * Returns candidates whose absolute value is strictly less than {@code |value|}.
         * Crucial: junit-quickcheck accepts any failing candidate, so non-strictly-smaller
         * candidates would let the shrinker oscillate (e.g., {@code 0 → 1 → 0 → ...}) and
         * exhaust its budget without making real progress.
         */
        private static List<Integer> intShrinkCandidates(int value) {
            long abs = Math.abs((long) value);
            LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
            if (abs > 0) {
                candidates.add(0);
            }
            if (abs > 1) {
                candidates.add(1);
                candidates.add(-1);
            }
            int half = value / 2;
            if (Math.abs((long) half) < abs) {
                candidates.add(half);
            }
            return new ArrayList<>(candidates);
        }

        private static List<Long> longShrinkCandidates(long value) {
            // Math.abs(Long.MIN_VALUE) overflows back to Long.MIN_VALUE; clamp it.
            long abs = value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
            LinkedHashSet<Long> candidates = new LinkedHashSet<>();
            if (abs > 0L) {
                candidates.add(0L);
            }
            if (abs > 1L) {
                candidates.add(1L);
                candidates.add(-1L);
            }
            long half = value / 2;
            long absHalf = half == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(half);
            if (absHalf < abs) {
                candidates.add(half);
            }
            return new ArrayList<>(candidates);
        }

        private static List<Double> doubleShrinkCandidates(double value) {
            double abs = Math.abs(value);
            LinkedHashSet<Double> candidates = new LinkedHashSet<>();
            if (abs > 0.0) {
                candidates.add(0.0);
            }
            if (abs > 1.0) {
                candidates.add(1.0);
                candidates.add(-1.0);
            }
            double half = value / 2;
            if (Math.abs(half) < abs) {
                candidates.add(half);
            }
            return new ArrayList<>(candidates);
        }

        /**
         * Tries shrinking each numeric literal in the plan toward smaller magnitudes.
         * Reuses addExpressionShrinksWith for non-leaf stages, then handles the leaf (ROW) separately.
         */
        private static void addNumericLiteralShrinks(List<TestCase> candidates, TestCase larger) {
            addExpressionShrinksWith(candidates, larger, TestCaseGenerator::shrinkNumericLiteralsInExpr);
            // Include the leaf (ROW) stage — ROW literals need shrinking too
            List<LogicalPlan> stages = flattenStages(larger.plan());
            LogicalPlan leaf = stages.getLast();
            if (leaf instanceof Row row) {
                for (int f = 0; f < row.fields().size(); f++) {
                    Alias alias = row.fields().get(f);
                    if (alias.child() instanceof Literal lit) {
                        List<Alias> newFields;
                        if (lit.dataType() == DataType.INTEGER && lit.value() instanceof Integer intVal) {
                            for (int candidate : intShrinkCandidates(intVal)) {
                                newFields = new ArrayList<>(row.fields());
                                newFields.set(
                                    f,
                                    new Alias(alias.source(), alias.name(), new Literal(Source.EMPTY, candidate, DataType.INTEGER))
                                );
                                List<LogicalPlan> newStages = new ArrayList<>(stages);
                                newStages.set(stages.size() - 1, new Row(row.source(), newFields));
                                addPlanCandidate(candidates, larger, LogicalPlanGenerator.resolveReferences(rebuildFromStages(newStages)));
                            }
                        } else if (lit.dataType() == DataType.LONG && lit.value() instanceof Long longVal) {
                            for (long candidate : longShrinkCandidates(longVal)) {
                                newFields = new ArrayList<>(row.fields());
                                newFields.set(
                                    f,
                                    new Alias(alias.source(), alias.name(), new Literal(Source.EMPTY, candidate, DataType.LONG))
                                );
                                List<LogicalPlan> newStages = new ArrayList<>(stages);
                                newStages.set(stages.size() - 1, new Row(row.source(), newFields));
                                addPlanCandidate(candidates, larger, LogicalPlanGenerator.resolveReferences(rebuildFromStages(newStages)));
                            }
                        } else if (lit.dataType() == DataType.DOUBLE && lit.value() instanceof Double doubleVal) {
                            for (double candidate : doubleShrinkCandidates(doubleVal)) {
                                newFields = new ArrayList<>(row.fields());
                                newFields.set(
                                    f,
                                    new Alias(alias.source(), alias.name(), new Literal(Source.EMPTY, candidate, DataType.DOUBLE))
                                );
                                List<LogicalPlan> newStages = new ArrayList<>(stages);
                                newStages.set(stages.size() - 1, new Row(row.source(), newFields));
                                addPlanCandidate(candidates, larger, LogicalPlanGenerator.resolveReferences(rebuildFromStages(newStages)));
                            }
                        }
                    }
                }
            }
        }

        /**
         * Returns candidate expressions where exactly one numeric literal has been replaced
         * with a smaller-magnitude value, preserving the surrounding expression structure.
         */
        private static List<Expression> shrinkNumericLiteralsInExpr(Expression expr) {
            List<Expression> result = new ArrayList<>();
            // If the expression itself is a numeric literal, try shrinking it directly
            if (expr instanceof Literal lit) {
                if (lit.dataType() == DataType.INTEGER && lit.value() instanceof Integer intVal) {
                    for (int candidate : intShrinkCandidates(intVal)) {
                        result.add(new Literal(Source.EMPTY, candidate, DataType.INTEGER));
                    }
                    return result;
                } else if (lit.dataType() == DataType.LONG && lit.value() instanceof Long longVal) {
                    for (long candidate : longShrinkCandidates(longVal)) {
                        result.add(new Literal(Source.EMPTY, candidate, DataType.LONG));
                    }
                    return result;
                } else if (lit.dataType() == DataType.DOUBLE && lit.value() instanceof Double doubleVal) {
                    for (double candidate : doubleShrinkCandidates(doubleVal)) {
                        result.add(new Literal(Source.EMPTY, candidate, DataType.DOUBLE));
                    }
                    return result;
                }
            }
            // Otherwise, try shrinking each child and rebuilding the parent
            List<Expression> children = expr.children();
            for (int i = 0; i < children.size(); i++) {
                Expression child = children.get(i);
                for (Expression shrunkChild : shrinkNumericLiteralsInExpr(child)) {
                    List<Expression> newChildren = new ArrayList<>(children);
                    newChildren.set(i, shrunkChild);
                    result.add(expr.replaceChildren(newChildren));
                }
            }
            return result;
        }
    }

    private static boolean isRowPlan(LogicalPlan plan) {
        LogicalPlan current = plan;
        while (current instanceof UnaryPlan unary) {
            current = unary.child();
        }
        return current instanceof Row;
    }

    private static void createIndex(SimSchema schema) throws IOException {
        Request createIndex = new Request("PUT", "/" + schema.indexName());
        StringBuilder mappings = new StringBuilder("{\"mappings\":{\"properties\":{");
        List<SimSchema.SimColumn> columns = schema.columns();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                mappings.append(",");
            }
            SimSchema.SimColumn col = columns.get(i);
            mappings.append("\"").append(col.name()).append("\":{\"type\":\"").append(col.type().typeName()).append("\"}");
        }
        mappings.append("}}}");
        createIndex.setJsonEntity(mappings.toString());
        restClient.performRequest(createIndex);
    }

    private static void indexData(SimSchema schema, List<Map<String, Object>> rows) throws IOException {
        if (rows.isEmpty()) {
            return;
        }
        Request bulk = new Request("POST", "/_bulk");
        bulk.addParameter("refresh", "true");
        StringBuilder body = new StringBuilder();
        for (Map<String, Object> row : rows) {
            body.append("{\"index\":{\"_index\":\"").append(schema.indexName()).append("\"}}\n");
            body.append("{");
            boolean first = true;
            for (var entry : row.entrySet()) {
                if (first == false) {
                    body.append(",");
                }
                body.append("\"").append(entry.getKey()).append("\":");
                Object value = entry.getValue();
                if (value instanceof String s) {
                    body.append("\"").append(s).append("\"");
                } else {
                    body.append(value);
                }
                first = false;
            }
            body.append("}\n");
        }
        bulk.setJsonEntity(body.toString());
        Response bulkResponse = restClient.performRequest(bulk);
        assertStatusCode(200, bulkResponse);
        Map<String, Object> bulkResult = XContentHelper.convertToMap(
            JsonXContent.jsonXContent,
            bulkResponse.getEntity().getContent(),
            false
        );
        if (Boolean.TRUE.equals(bulkResult.get("errors"))) {
            throw new AssertionError("Bulk indexing had errors: " + bulkResult);
        }
    }

    private static void deleteIndex(String indexName) throws IOException {
        try {
            restClient.performRequest(new Request("DELETE", "/" + indexName));
        } catch (Exception e) {
            // Best-effort cleanup
        }
    }

    private static Map<String, Object> runEsqlQuery(String query) throws IOException {
        Request request = new Request("POST", "/_query");
        request.setJsonEntity("{\"query\": \"" + query.replace("\"", "\\\"") + "\"}");
        request.setOptions(request.getOptions().toBuilder().setWarningsHandler(WarningsHandler.PERMISSIVE));
        Response response = restClient.performRequest(request);
        assertStatusCode(200, response);
        return XContentHelper.convertToMap(JsonXContent.jsonXContent, response.getEntity().getContent(), false);
    }

    @SuppressWarnings("unchecked")
    private static Result responseToResult(Map<String, Object> response) {
        List<Map<String, String>> columns = (List<Map<String, String>>) response.get("columns");
        List<List<Object>> values = (List<List<Object>>) response.get("values");

        List<Simulator.Column> resultColumns = new ArrayList<>();
        for (int c = 0; c < columns.size(); c++) {
            Map<String, String> colMeta = columns.get(c);
            String name = colMeta.get("name");
            DataType type = DataType.fromTypeName(colMeta.get("type"));
            List<Object> colValues = new ArrayList<>();
            if (values != null) {
                for (List<Object> row : values) {
                    colValues.add(row.get(c));
                }
            }
            resultColumns.add(new Simulator.Column(name, type, colValues));
        }
        return new Result(resultColumns);
    }

    private static List<Simulator.Column> sortedColumns(List<Simulator.Column> columns) {
        return columns.stream().sorted(Comparator.comparing(Simulator.Column::name)).toList();
    }

    private static List<String> columnNames(List<Simulator.Column> columns) {
        return columns.stream().map(Simulator.Column::name).toList();
    }

    private static void assertStatusCode(int expected, Response response) {
        int actual = response.getStatusLine().getStatusCode();
        if (actual != expected) {
            throw new AssertionError(Strings.format("Expected status code %s but got %s", expected, actual));
        }
    }

    private static Object normalizeValue(Object v) {
        return v instanceof Integer n ? n.longValue() : v;
    }

    /**
     * Finds the effective sort order by walking the plan from root toward leaves.
     * Returns the sort keys of the first {@link OrderBy} encountered, or empty if
     * an {@link Aggregate} (STATS) is reached first (which destroys row order).
     */
    private static List<Order> findEffectiveSort(LogicalPlan plan) {
        LogicalPlan current = plan;
        while (current instanceof UnaryPlan unary) {
            if (current instanceof OrderBy orderBy) {
                return orderBy.order();
            }
            if (current instanceof Aggregate) {
                return List.of();
            }
            current = unary.child();
        }
        return List.of();
    }

    /**
     * Verifies that the result rows are sorted according to the given sort keys.
     * Rows with equal sort key values (ties) may appear in any relative order.
     * Generated queries omit explicit {@code NULLS FIRST/LAST}, so ES defaults apply
     * (nulls last for ASC, first for DESC).
     */
    private static void verifySortOrder(List<Order> orders, Result result, String label, String query) {
        int numRows = result.numRows();
        if (numRows <= 1) {
            return;
        }
        List<List<Object>> sortKeyValues = orders.stream().map(o -> result.evaluate(o.child(), SimBug.BUG_FREE).values()).toList();
        for (int r = 0; r < numRows - 1; r++) {
            for (int k = 0; k < orders.size(); k++) {
                Object curr = normalizeValue(sortKeyValues.get(k).get(r));
                Object next = normalizeValue(sortKeyValues.get(k).get(r + 1));
                boolean asc = orders.get(k).direction() == Order.OrderDirection.ASC;
                int cmp = compareSortValues(curr, next, asc);
                if (cmp > 0) {
                    throw new AssertionError(
                        Strings.format(
                            "%s result not sorted correctly at rows %d-%d for query [%s]: values [%s, %s] (expected %s)",
                            label,
                            r,
                            r + 1,
                            query,
                            curr,
                            next,
                            asc ? "ASC" : "DESC"
                        )
                    );
                }
                if (cmp != 0) {
                    break; // This key determined the order, skip remaining keys
                }
            }
        }
    }

    /**
     * Compares two sort key values respecting null ordering: nulls last for ASC, nulls first for DESC.
     * Returns negative if a should come before b, positive if after, zero if equal.
     */
    private static int compareSortValues(Object a, Object b, boolean asc) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return asc ? 1 : -1;  // null last for ASC, first for DESC
        }
        if (b == null) {
            return asc ? -1 : 1;
        }
        int cmp = nullSafeCompareValues(a, b);
        return asc ? cmp : -cmp;
    }

    /** Builds rows from columnar data, normalizing values (Integer → Long). */
    private static List<List<Object>> extractRows(List<Simulator.Column> columns) {
        int numRows = columns.isEmpty() ? 0 : columns.getFirst().values().size();
        List<List<Object>> rows = new ArrayList<>(numRows);
        for (int r = 0; r < numRows; r++) {
            List<Object> row = new ArrayList<>(columns.size());
            for (Simulator.Column col : columns) {
                row.add(normalizeValue(col.values().get(r)));
            }
            rows.add(row);
        }
        return rows;
    }

    private static int compareRows(List<Object> a, List<Object> b) {
        for (int i = 0; i < a.size(); i++) {
            int cmp = nullSafeCompareValues(a.get(i), b.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static int nullSafeCompareValues(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Comparable<?> ac) {
            return ((Comparable) ac).compareTo(b);
        }
        return a.toString().compareTo(b.toString());
    }
}
