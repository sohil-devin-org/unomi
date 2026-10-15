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
package org.apache.unomi.persistence.opensearch.querybuilders.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.unomi.api.conditions.Condition;
import org.apache.unomi.persistence.opensearch.ConditionOSQueryBuilderDispatcher;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks the JSON produced by the nested condition builder. The same expected JSON is used in
 * NestedConditionESQueryBuilderTest so both backends are held to one shape.
 */
class NestedConditionOSQueryBuilderTest {

    static final String EXPECTED_JSON = "{\"nested\":{\"path\":\"properties.interests\","
            + "\"query\":{\"term\":{\"properties.interests.key\":{\"value\":\"cars\"}}},\"score_mode\":\"avg\"}}";

    private final ConditionOSQueryBuilderDispatcher dispatcher = new ConditionOSQueryBuilderDispatcher() {
        @Override
        public Query buildFilter(Condition condition, Map<String, Object> context) {
            return Query.of(q -> q.term(t -> t.field("properties.interests.key").value(FieldValue.of("cars"))));
        }
    };

    @Test
    void buildQuery_wrapsSubConditionInNestedQueryWithPathAndAvgScore() throws Exception {
        Condition condition = new Condition();
        condition.setParameter("path", "properties.interests");
        condition.setParameter("subCondition", new Condition());

        Query query = new NestedConditionOSQueryBuilder().buildQuery(condition, Collections.emptyMap(), dispatcher);

        ObjectMapper mapper = new ObjectMapper();
        assertEquals(mapper.readTree(EXPECTED_JSON), mapper.readTree(query.toJsonString()));
    }

    @Test
    void buildQuery_missingPath_throwsIllegalArgumentException() {
        Condition condition = new Condition();
        condition.setParameter("subCondition", new Condition());
        assertThrows(IllegalArgumentException.class,
                () -> new NestedConditionOSQueryBuilder().buildQuery(condition, Collections.emptyMap(), dispatcher));
    }
}
