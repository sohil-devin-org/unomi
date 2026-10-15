/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.unomi.persistence.opensearch.querybuilders.advanced;

import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.apache.unomi.api.ExecutionContext;
import org.apache.unomi.api.conditions.Condition;
import org.apache.unomi.api.services.ExecutionContextManager;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * The expected query shapes in this test are kept identical to the ones in the
 * IdsCondition query builder test of the other search engine module.
 */
public class IdsConditionOSQueryBuilderTest {

    private IdsConditionOSQueryBuilder builder;

    @Before
    public void setUp() {
        builder = new IdsConditionOSQueryBuilder();
        builder.setExecutionContextManager(new FixedTenantContextManager("tenantA"));
    }

    @Test
    public void matchTrueBuildsIdsQueryWithTenantPrefix() {
        assertEquals("ids[tenantA_p1, tenantA_p2]", describe(build(List.of("p1", "p2"), true)));
    }

    @Test
    public void matchFalseBuildsNegatedIdsQuery() {
        assertEquals("not(ids[tenantA_p1, tenantA_p2])", describe(build(List.of("p1", "p2"), false)));
    }

    @Test
    public void omittedMatchDefaultsToTrue() {
        assertEquals("ids[tenantA_p1]", describe(build(List.of("p1"), null)));
    }

    @Test
    public void emptyIdsMatchNothing() {
        assertEquals("match_none", describe(build(Collections.emptyList(), true)));
        assertEquals("match_none", describe(build(Collections.emptyList(), false)));
        assertEquals("match_none", describe(build(null, false)));
    }

    @Test
    public void maximumIdsQueryCountIsEnforced() {
        builder.setMaximumIdsQueryCount(2);
        assertEquals("ids[tenantA_a, tenantA_b]", describe(build(List.of("a", "b"), true)));
        assertThrows(UnsupportedOperationException.class, () -> build(List.of("a", "b", "c"), true));
        assertThrows(UnsupportedOperationException.class, () -> build(List.of("a", "b", "c"), false));
    }

    private Query build(List<String> ids, Boolean match) {
        Condition condition = new Condition();
        condition.setConditionTypeId("idsCondition");
        if (ids != null) {
            condition.setParameter("ids", new ArrayList<>(ids));
        }
        if (match != null) {
            condition.setParameter("match", match);
        }
        return builder.buildQuery(condition, Collections.emptyMap(), null);
    }

    private static String describe(Query query) {
        if (query.isIds()) {
            return "ids" + query.ids().values();
        }
        if (query.isMatchNone()) {
            return "match_none";
        }
        if (query.isBool() && query.bool().must().isEmpty() && query.bool().should().isEmpty()
                && query.bool().filter().isEmpty() && query.bool().mustNot().size() == 1) {
            return "not(" + describe(query.bool().mustNot().get(0)) + ")";
        }
        return "unexpected:" + query._kind();
    }

    private static final class FixedTenantContextManager implements ExecutionContextManager {
        private final ExecutionContext context;

        FixedTenantContextManager(String tenantId) {
            this.context = new ExecutionContext(tenantId, Collections.emptySet(), Collections.emptySet());
        }

        @Override
        public ExecutionContext getCurrentContext() {
            return context;
        }

        @Override
        public void setCurrentContext(ExecutionContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T executeAsSystem(Supplier<T> operation) {
            return operation.get();
        }

        @Override
        public void executeAsSystem(Runnable operation) {
            operation.run();
        }

        @Override
        public <T> T executeAsTenant(String tenantId, Supplier<T> operation) {
            return operation.get();
        }

        @Override
        public void executeAsTenant(String tenantId, Runnable operation) {
            operation.run();
        }

        @Override
        public ExecutionContext createContext(String tenantId) {
            return new ExecutionContext(tenantId, Collections.emptySet(), Collections.emptySet());
        }
    }
}
