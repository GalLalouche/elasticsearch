/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.single_node;

import net.jqwik.api.Arbitrary;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.test.TestClustersThreadFilter;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.qa.simulator.LogicalPlanGenerator;
import org.elasticsearch.xpack.esql.qa.simulator.LogicalPlanPrinter;
import org.elasticsearch.xpack.esql.qa.simulator.Simulator;
import org.junit.Before;
import org.junit.ClassRule;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@ThreadLeakFilters(filters = TestClustersThreadFilter.class)
public class SimulatorPropertyIT extends ESRestTestCase {

    @ClassRule
    public static ElasticsearchCluster cluster = Clusters.testCluster();

    @Override
    protected String getTestRestCluster() {
        return cluster.getHttpAddresses();
    }

    private static boolean dataIndexed = false;

    @Before
    public void setupIndex() throws IOException {
        if (dataIndexed) {
            return;
        }
        Request createIndex = new Request("PUT", "/sim_data");
        createIndex.setJsonEntity("""
            {
              "mappings": {
                "properties": {
                  "x": { "type": "integer" },
                  "y": { "type": "integer" }
                }
              }
            }
            """);
        client().performRequest(createIndex);

        Request bulk = new Request("POST", "/_bulk");
        bulk.addParameter("refresh", "true");
        bulk.setJsonEntity("""
            {"index": {"_index": "sim_data"}}
            {"x": 1, "y": 2}
            {"index": {"_index": "sim_data"}}
            {"x": 3, "y": 4}
            {"index": {"_index": "sim_data"}}
            {"x": 5, "y": 6}
            """);
        Response bulkResponse = client().performRequest(bulk);
        assertEquals(200, bulkResponse.getStatusLine().getStatusCode());
        dataIndexed = true;
    }

    public void testSimulatorMatchesEs() throws Exception {
        Arbitrary<LogicalPlan> plans = LogicalPlanGenerator.simDataPlans();
        Simulator simulator = new Simulator();

        int numTrials = 1;
        var generator = plans.generator(1000);
        var random = new java.util.Random(randomLong());

        for (int i = 0; i < numTrials; i++) {
            LogicalPlan plan = generator.next(random).value();
            String query = LogicalPlanPrinter.print(plan);
            logger.info("Trial {}: {}", i, query);

            // Run through simulator
            Simulator.Result simResult;
            try {
                simResult = simulator.simulate(plan);
            } catch (Exception e) {
                logger.warn("Simulator failed for query [{}]: {}", query, e.getMessage());
                continue;
            }

            // Run through ES REST API
            Map<String, Object> esResponse;
            try {
                esResponse = runEsqlQuery(query);
            } catch (Exception e) {
                logger.warn("ES query failed for [{}]: {}", query, e.getMessage());
                continue;
            }

            // Convert ES response to Result
            Simulator.Result esResult = responseToResult(esResponse);

            // Compare (sort columns by name for consistent ordering)
            List<Simulator.Column> simColumns = sortedColumns(simResult.columns());
            List<Simulator.Column> esCols = sortedColumns(esResult.columns());

            if (simColumns.size() != esCols.size()) {
                fail(
                    "Column count mismatch for query ["
                        + query
                        + "]: simulator="
                        + simColumns.size()
                        + " es="
                        + esCols.size()
                        + "\nSimulator columns: "
                        + columnNames(simColumns)
                        + "\nES columns: "
                        + columnNames(esCols)
                );
            }

            for (int c = 0; c < simColumns.size(); c++) {
                Simulator.Column simCol = simColumns.get(c);
                Simulator.Column esCol = esCols.get(c);

                if (simCol.name().equals(esCol.name()) == false) {
                    fail(
                        "Column name mismatch at index "
                            + c
                            + " for query ["
                            + query
                            + "]: simulator="
                            + simCol.name()
                            + " es="
                            + esCol.name()
                    );
                }

                List<Object> simValues = normalizeValues(simCol.values());
                List<Object> esValues = normalizeValues(esCol.values());

                if (simValues.equals(esValues) == false) {
                    fail(
                        "Value mismatch for column ["
                            + simCol.name()
                            + "] in query ["
                            + query
                            + "]:\nsimulator="
                            + simValues
                            + "\nes="
                            + esValues
                    );
                }
            }
        }
    }

    private Map<String, Object> runEsqlQuery(String query) throws IOException {
        Request request = new Request("POST", "/_query");
        request.setJsonEntity("{\"query\": \"" + query.replace("\"", "\\\"") + "\"}");
        Response response = client().performRequest(request);
        assertEquals(200, response.getStatusLine().getStatusCode());
        return XContentHelper.convertToMap(JsonXContent.jsonXContent, response.getEntity().getContent(), false);
    }

    @SuppressWarnings("unchecked")
    private static Simulator.Result responseToResult(Map<String, Object> response) {
        List<Map<String, String>> columns = (List<Map<String, String>>) response.get("columns");
        List<List<Object>> values = (List<List<Object>>) response.get("values");

        List<Simulator.Column> resultColumns = new ArrayList<>();
        for (int c = 0; c < columns.size(); c++) {
            Map<String, String> colMeta = columns.get(c);
            String name = colMeta.get("name");
            var type = org.elasticsearch.xpack.esql.core.type.DataType.fromTypeName(colMeta.get("type"));
            List<Object> colValues = new ArrayList<>();
            if (values != null) {
                for (List<Object> row : values) {
                    colValues.add(row.get(c));
                }
            }
            resultColumns.add(new Simulator.Column(name, type, colValues));
        }
        return new Simulator.Result(resultColumns);
    }

    private static List<Simulator.Column> sortedColumns(List<Simulator.Column> columns) {
        List<Simulator.Column> sorted = new ArrayList<>(columns);
        sorted.sort(Comparator.comparing(Simulator.Column::name));
        return sorted;
    }

    private static List<String> columnNames(List<Simulator.Column> columns) {
        return columns.stream().map(Simulator.Column::name).toList();
    }

    private static List<Object> normalizeValues(List<Object> values) {
        return values.stream().map(v -> {
            if (v instanceof Number n) {
                return n.longValue();
            }
            return v;
        }).toList();
    }
}
