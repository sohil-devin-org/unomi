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

import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.apache.unomi.api.conditions.Condition;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SourceEventPropertyConditionOSQueryBuilderTest {

    private final SourceEventPropertyConditionOSQueryBuilder builder = new SourceEventPropertyConditionOSQueryBuilder();

    @Test
    public void mapsEachParameterToTheSourceItemFieldAndFoldsTheValue() {
        assertEquals(Map.of("source.itemId", "page-home"), terms(build("id", "Page-Home")));
        assertEquals(Map.of("source.properties.pageInfo.pagePath", "/home"), terms(build("path", "/Home")));
        assertEquals(Map.of("source.scope", "sitea"), terms(build("scope", "SiteA")));
        assertEquals(Map.of("source.itemType", "page"), terms(build("type", "Page")));
        assertEquals(Map.of("source.scope", "site"), terms(build("scope", "Sité")));
    }

    @Test
    public void combinesSeveralParametersWithMust() {
        Query query = build("id", "page-home", "path", "/home", "scope", "sitea", "type", "page");
        assertTrue(query.isBool());
        assertEquals(4, query.bool().must().size());
        assertEquals(Map.of("source.itemId", "page-home", "source.properties.pageInfo.pagePath", "/home",
                "source.scope", "sitea", "source.itemType", "page"), terms(query));
    }

    @Test
    public void ignoresNullAndEmptyParameters() {
        assertTrue(build().isMatchAll());
        assertTrue(build("id", "").isMatchAll());
        assertTrue(build("id", null, "type", "").isMatchAll());
        assertEquals(Map.of("source.scope", "sitea"), terms(build("id", "", "path", null, "scope", "sitea")));
    }

    private Query build(String... keyValues) {
        Condition condition = new Condition();
        for (int i = 0; i < keyValues.length; i += 2) {
            condition.setParameter(keyValues[i], keyValues[i + 1]);
        }
        return builder.buildQuery(condition, Collections.emptyMap(), null);
    }

    private static Map<String, String> terms(Query query) {
        Map<String, String> terms = new LinkedHashMap<>();
        if (query.isBool()) {
            query.bool().must().forEach(q -> terms.putAll(terms(q)));
        } else {
            assertTrue(query.isTerm(), "Expected a term query: " + query);
            terms.put(query.term().field(), query.term().value().stringValue());
        }
        return terms;
    }
}
