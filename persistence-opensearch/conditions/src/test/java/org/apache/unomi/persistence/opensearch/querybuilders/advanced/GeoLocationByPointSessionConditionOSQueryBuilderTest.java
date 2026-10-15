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
 * limitations under the License
 */

package org.apache.unomi.persistence.opensearch.querybuilders.advanced;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import jakarta.json.stream.JsonGenerator;
import org.apache.unomi.api.conditions.Condition;
import org.junit.Test;

import java.io.StringWriter;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The Elasticsearch and OpenSearch versions of this test use the same expected JSON, so both builders must
 * produce the same query.
 */
public class GeoLocationByPointSessionConditionOSQueryBuilderTest {

    private final GeoLocationByPointSessionConditionOSQueryBuilder builder = new GeoLocationByPointSessionConditionOSQueryBuilder();

    @Test
    public void circleWithDoubleCenter() throws Exception {
        assertJson("{\"geo_distance\":{\"properties.location\":{\"lat\":48.8566,\"lon\":2.3522},\"distance\":\"350km\"}}",
                build(circle(48.8566, 2.3522, "350km")));
    }

    @Test
    public void circleWithIntegerCenterAndNumericDistance() throws Exception {
        assertJson("{\"geo_distance\":{\"properties.location\":{\"lat\":5.0,\"lon\":5.0},\"distance\":\"100000\"}}",
                build(circle(5, 5, 100000)));
    }

    @Test
    public void rectangleUsesSouthWestLongitudeAsLeftEdge() throws Exception {
        assertJson("{\"geo_bounding_box\":{\"properties.location\":{\"top\":10.0,\"left\":0.0,\"bottom\":0.0,\"right\":10.0}}}",
                build(rectangle(10.0, 10.0, 0.0, 0.0)));
    }

    @Test
    public void rectangleAcceptsIntegerCorners() throws Exception {
        assertJson("{\"geo_bounding_box\":{\"properties.location\":{\"top\":10.0,\"left\":0.0,\"bottom\":0.0,\"right\":10.0}}}",
                build(rectangle(10, 10, 0, 0)));
    }

    @Test
    public void rectangleAcrossAntimeridian() throws Exception {
        assertJson("{\"geo_bounding_box\":{\"properties.location\":{\"top\":-10.0,\"left\":175.0,\"bottom\":-20.0,\"right\":-170.0}}}",
                build(rectangle(-10.0, -170.0, -20.0, 175.0)));
    }

    @Test
    public void defaultFieldIsLocation() throws Exception {
        Condition condition = circle(48.8566, 2.3522, "350km");
        condition.getParameterValues().remove("name");
        assertJson("{\"geo_distance\":{\"location\":{\"lat\":48.8566,\"lon\":2.3522},\"distance\":\"350km\"}}",
                build(condition));
    }

    @Test
    public void unknownTypeBuildsNoQuery() {
        Condition condition = circle(48.8566, 2.3522, "350km");
        condition.setParameter("type", "polygon");
        assertNull(builder.buildQuery(condition, Collections.emptyMap(), null));
    }

    @Test
    public void rectangleWithMissingCornerBuildsNoQuery() {
        Condition condition = rectangle(10.0, 10.0, 0.0, 0.0);
        condition.getParameterValues().remove("rectLongitudeSW");
        assertNull(builder.buildQuery(condition, Collections.emptyMap(), null));
    }

    private static Condition circle(Object lat, Object lon, Object distance) {
        Condition condition = new Condition();
        condition.setParameter("type", "circle");
        condition.setParameter("name", "properties.location");
        condition.setParameter("circleLatitude", lat);
        condition.setParameter("circleLongitude", lon);
        condition.setParameter("distance", distance);
        return condition;
    }

    private static Condition rectangle(Object latNE, Object lonNE, Object latSW, Object lonSW) {
        Condition condition = new Condition();
        condition.setParameter("type", "rectangle");
        condition.setParameter("name", "properties.location");
        condition.setParameter("rectLatitudeNE", latNE);
        condition.setParameter("rectLongitudeNE", lonNE);
        condition.setParameter("rectLatitudeSW", latSW);
        condition.setParameter("rectLongitudeSW", lonSW);
        return condition;
    }

    private String build(Condition condition) {
        Query query = builder.buildQuery(condition, Collections.emptyMap(), null);
        JsonpMapper mapper = new JacksonJsonpMapper();
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = mapper.jsonProvider().createGenerator(writer)) {
            query.serialize(generator, mapper);
        }
        return writer.toString();
    }

    private static void assertJson(String expected, String actual) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        assertEquals(objectMapper.readTree(expected), objectMapper.readTree(actual));
    }
}
