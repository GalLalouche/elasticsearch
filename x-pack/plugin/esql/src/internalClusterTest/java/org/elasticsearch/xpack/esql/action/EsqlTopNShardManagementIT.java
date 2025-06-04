/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.action;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xpack.core.async.GetAsyncResultRequest;
import org.elasticsearch.xpack.esql.plugin.QueryPragmas;

import java.util.concurrent.TimeUnit;

import static org.elasticsearch.core.TimeValue.timeValueMillis;
import static org.elasticsearch.core.TimeValue.timeValueSeconds;

public class EsqlTopNShardManagementIT extends AbstractPausableIntegTestCase {
    private static final String QUERY =
        "from test | sort foo * date_extract(\"nano_of_second\", now()) + 12345 | limit 1 | where pause_me + 1 > 42 | stats sum(pause_me)";

    public void testRunningQueries() throws Exception {
        String id = null;
        try (var initialResponse = sendAsyncQuery()) {
            id = initialResponse.asyncExecutionId().get();
            var getResultsRequest = new GetAsyncResultRequest(id);
            getResultsRequest.setWaitForCompletionTimeout(timeValueMillis(100));
            var result = client().execute(EsqlAsyncGetResultAction.INSTANCE, getResultsRequest).get();
            System.out.println(result);
        } finally {
            if (id != null) {
                // Finish the query.
                scriptPermits.release(numberOfDocs());
                var getResultsRequest = new GetAsyncResultRequest(id);
                getResultsRequest.setWaitForCompletionTimeout(timeValueSeconds(60));
                client().execute(EsqlAsyncGetResultAction.INSTANCE, getResultsRequest).get().close();
            }
            scriptPermits.drainPermits();
        }
    }

    private EsqlQueryResponse sendAsyncQuery() {
        scriptPermits.drainPermits();
        scriptPermits.release(between(1, 5));
        return EsqlQueryRequestBuilder.newAsyncEsqlQueryRequestBuilder(client())
            .query(QUERY)
            .pragmas(
                new QueryPragmas(
                    // "pragma": { "max_concurrent_nodes_per_cluster": 1, "task_concurrency": 1 },
                    Settings.builder()
                        .put(QueryPragmas.MAX_CONCURRENT_NODES_PER_CLUSTER.getKey(), 1)
                        .put(QueryPragmas.TASK_CONCURRENCY.getKey(), 1)
                        .build()
                )
            )
            .execute()
            .actionGet(60, TimeUnit.SECONDS);
    }
}
