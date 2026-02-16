/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;

import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.cluster.local.distribution.DistributionType;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class SimulatorPropertyIT {

    static {
        // Initialize ES logging (required outside the ES test framework).
        LogConfigurator.configureESLogging();
        // Force IndexSettings to initialize before IndexMode to break circular class init dependency.
        // IndexMode.<clinit> references IndexSettings fields, and IndexSettings.<clinit> references
        // IndexMode.VALIDATE_WITH_SETTINGS which would be null if IndexMode is still initializing.
        var unused = IndexSettings.MODE;
    }

    private static ElasticsearchCluster cluster;
    private static RestClient restClient;

    @BeforeContainer
    static void startCluster() throws Throwable {
        cluster = ElasticsearchCluster.local()
            .distribution(DistributionType.DEFAULT)
            .setting("xpack.security.enabled", "false")
            .setting("xpack.license.self_generated.type", "trial")
            .shared(true)
            .build();

        // Trigger the JUnit 4 TestRule to initialize the cluster handle.
        // ElasticsearchCluster requires this mechanism to create and start the cluster.
        cluster.apply(new org.junit.runners.model.Statement() {
            @Override
            public void evaluate() {
                // no-op: cluster is started by apply(); since shared=true it won't close
            }
        }, org.junit.runner.Description.createSuiteDescription(SimulatorPropertyIT.class)).evaluate();

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

    @AfterContainer
    static void stopCluster() throws Exception {
        if (restClient != null) {
            restClient.close();
            restClient = null;
        }
        if (cluster != null) {
            cluster.close();
            cluster = null;
        }
    }

    /**
     * A test case containing the generated schema, data, plan, and query string.
     * toString() returns a readable summary for jqwik's counterexample reports.
     */
    record TestCase(SimSchema schema, List<Map<String, Object>> data, LogicalPlan plan, String query) {
        @Override
        public String toString() {
            return query + " [" + schema + ", " + data.size() + " rows]";
        }
    }

    @Property(tries = 10)
    void simulatorMatchesEs(@ForAll("testCases") TestCase tc) throws Exception {
        // Clean up any stale index from a previous run, then set up fresh
        deleteIndex(tc.schema().indexName());
        createIndex(tc.schema());
        try {
            indexData(tc.schema(), tc.data());

            // Run through simulator (with in-memory data)
            Simulator simulator = new Simulator(tc.schema(), tc.data());
            Simulator.Result simResult = simulator.simulate(tc.plan());

            // Run through ES REST API
            Map<String, Object> esResponse = runEsqlQuery(tc.query());
            Simulator.Result esResult = responseToResult(esResponse);

            // Compare (sort columns by name for consistent ordering)
            List<Simulator.Column> simColumns = sortedColumns(simResult.columns());
            List<Simulator.Column> esCols = sortedColumns(esResult.columns());

            if (simColumns.size() != esCols.size()) {
                throw new AssertionError(
                    "Column count mismatch for query ["
                        + tc.query()
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
                    throw new AssertionError(
                        "Column name mismatch at index "
                            + c
                            + " for query ["
                            + tc.query()
                            + "]: simulator="
                            + simCol.name()
                            + " es="
                            + esCol.name()
                    );
                }

                List<Object> simValues = normalizeValues(simCol.values());
                List<Object> esValues = normalizeValues(esCol.values());

                if (simValues.equals(esValues) == false) {
                    throw new AssertionError(
                        "Value mismatch for column ["
                            + simCol.name()
                            + "] in query ["
                            + tc.query()
                            + "] with "
                            + tc.schema()
                            + " and data "
                            + tc.data()
                            + ":\nsimulator="
                            + simValues
                            + "\nes="
                            + esValues
                    );
                }
            }
        } finally {
            deleteIndex(tc.schema().indexName());
        }
    }

    @Provide
    Arbitrary<TestCase> testCases() {
        return SimSchemaGenerator.schemas()
            .flatMap(
                schema -> Combinators.combine(SimDataGenerator.rows(schema), LogicalPlanGenerator.plansFor(schema))
                    .as((data, plan) -> new TestCase(schema, data, plan, LogicalPlanPrinter.print(plan)))
            );
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

    private Map<String, Object> runEsqlQuery(String query) throws IOException {
        Request request = new Request("POST", "/_query");
        request.setJsonEntity("{\"query\": \"" + query.replace("\"", "\\\"") + "\"}");
        request.setOptions(request.getOptions().toBuilder().setWarningsHandler(warnings -> {
            // Allow the "No limit defined" warning from ES|QL
            return warnings.stream().noneMatch(w -> w.contains("No limit defined"));
        }));
        Response response = restClient.performRequest(request);
        assertStatusCode(200, response);
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

    private static void assertStatusCode(int expected, Response response) {
        int actual = response.getStatusLine().getStatusCode();
        if (actual != expected) {
            throw new AssertionError("Expected status code " + expected + " but got " + actual);
        }
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
