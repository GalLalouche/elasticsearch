/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.simulator;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.action.EsqlExecutionInfo;
import org.elasticsearch.xpack.esql.action.EsqlQueryRequest;
import org.elasticsearch.xpack.esql.expression.function.EsqlFunctionRegistry;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.planner.mapper.Mapper;
import org.elasticsearch.xpack.esql.plugin.QueryPragmas;
import org.elasticsearch.xpack.esql.session.EsqlSession;
import org.elasticsearch.xpack.esql.session.Result;
import org.elasticsearch.xpack.esql.telemetry.PlanTelemetry;

import java.io.IOException;

import static org.elasticsearch.compute.test.OperatorTestCase.randomPageSize;

public class ProductionRunner {
    public Simulator.Result run(LogicalPlan plan) throws IOException {
        EsqlFunctionRegistry functionRegistry = new EsqlFunctionRegistry();
        EsqlSession session = new EsqlSession(
            "production-runner-for-simulator",
            EsqlTestUtils.configuration(new QueryPragmas(Settings.builder().put("page_size", randomPageSize()).build())),
            null,
            null,
            null,
            null,
            functionRegistry,
            null,
            new Mapper(),
            EsqlTestUtils.TEST_VERIFIER,
            new PlanTelemetry(functionRegistry),
            null,
            EsqlTestUtils.MOCK_TRANSPORT_ACTION_SERVICES
        );
        EsqlQueryRequest request = new EsqlQueryRequest();
        EsqlExecutionInfo info = new EsqlExecutionInfo(false);
        LogicalPlan optimizedPlan = null;
        EsqlSession.PlanRunner runner = (physicalPlan, listener) -> executeSubPlan(
            bigArrays,
            foldCtx,
            physicalOperationProviders,
            physicalPlan,
            listener
        );
        ;
        return toResult(ESTestCase.safeAwait(l -> session.executeOptimizedPlan(request, info, runner, optimizedPlan, l)));
    }

    private static Simulator.Result toResult(Result result) {
        throw new AssertionError("TODO(gal) NOCOMMIT");
    }
}
