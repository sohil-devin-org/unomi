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

package org.apache.unomi.itests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.unomi.api.Profile;
import org.apache.unomi.api.Session;
import org.apache.unomi.api.conditions.Condition;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Runs the same table of {@code geoLocationByPointSessionCondition} queries against a fixed set of sessions and
 * checks the exact matched session IDs. The expected IDs are the same constants for Elasticsearch and OpenSearch,
 * so this test passing on both engines shows that both query builders return identical results.
 * <p>
 * The actual IDs per case are also written to {@code itests/target/parity/geolocation-<engine>.json} so that the
 * output of the two runs can be compared directly.
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerSuite.class)
public class GeoLocationByPointSessionConditionBackendParityIT extends BaseIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeoLocationByPointSessionConditionBackendParityIT.class);

    private static final String CONDITION_TYPE = "geoLocationByPointSessionCondition";
    private static final String SCOPE = "geo-parity-scope";
    private static final String PREFIX = "geo-parity-";
    private static final String ERROR = "ERROR";

    private static final Map<String, double[]> LOCATIONS = new LinkedHashMap<>();

    static {
        LOCATIONS.put("paris", new double[]{48.8566, 2.3522});
        LOCATIONS.put("london", new double[]{51.5074, -0.1278});       // 343.6 km from Paris
        LOCATIONS.put("brussels", new double[]{50.8503, 4.3517});      // 264.0 km from Paris
        LOCATIONS.put("lyon", new double[]{45.7640, 4.8357});          // 391.5 km from Paris
        LOCATIONS.put("fiji", new double[]{-17.7134, 178.0650});
        LOCATIONS.put("samoa", new double[]{-13.7590, -172.1046});     // 1140 km from Fiji, across the antimeridian
        LOCATIONS.put("box-inside", new double[]{5.0, 5.0});
        LOCATIONS.put("box-near-in", new double[]{5.8984, 5.0});       // 99.90 km north of (5,5)
        LOCATIONS.put("box-near-out", new double[]{5.9002, 5.0});      // 100.10 km north of (5,5)
        LOCATIONS.put("box-edge-north", new double[]{10.0, 5.0});      // on the north edge of the (0,0)-(10,10) box
        LOCATIONS.put("box-corner-sw", new double[]{0.0, 0.0});        // on the south-west corner of the box
        LOCATIONS.put("box-edge-east", new double[]{5.0, 10.0});       // on the east edge of the box
        LOCATIONS.put("box-out-east", new double[]{5.0, 10.5});
        LOCATIONS.put("box-out-west", new double[]{5.0, -0.5});
        LOCATIONS.put("box-far-east", new double[]{5.0, 100.0});
    }

    private static final String NO_LOCATION = "no-location";

    private final List<String> seededIds = new ArrayList<>();
    private Date timestamp;

    private static Set<String> ids(String... names) {
        return Arrays.stream(names).map(n -> PREFIX + n).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> allWithLocation() {
        return ids(LOCATIONS.keySet().toArray(new String[0]));
    }

    private static Set<String> allSessions() {
        Set<String> all = allWithLocation();
        all.addAll(ids(NO_LOCATION));
        return all;
    }

    private static Set<String> error() {
        return new TreeSet<>(Collections.singleton(ERROR));
    }

    private static final class Case {
        final String name;
        final Map<String, Object> parameters;
        final Set<String> expected;

        Case(String name, Map<String, Object> parameters, Set<String> expected) {
            this.name = name;
            this.parameters = parameters;
            this.expected = expected;
        }
    }

    private static Map<String, Object> circle(Object lat, Object lon, Object distance) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "circle");
        p.put("name", "properties.location");
        p.put("circleLatitude", lat);
        p.put("circleLongitude", lon);
        p.put("distance", distance);
        return p;
    }

    private static Map<String, Object> rectangle(Object latNE, Object lonNE, Object latSW, Object lonSW) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "rectangle");
        p.put("name", "properties.location");
        p.put("rectLatitudeNE", latNE);
        p.put("rectLongitudeNE", lonNE);
        p.put("rectLatitudeSW", latSW);
        p.put("rectLongitudeSW", lonSW);
        return p;
    }

    private static List<Case> cases() {
        Set<String> paris350 = ids("paris", "brussels", "london");
        Set<String> box = ids("box-inside", "box-near-in", "box-near-out", "box-edge-north", "box-corner-sw", "box-edge-east");
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("circle-paris-300km", circle(48.8566, 2.3522, "300km"), ids("paris", "brussels")));
        cases.add(new Case("circle-paris-350km", circle(48.8566, 2.3522, "350km"), paris350));
        cases.add(new Case("circle-paris-350-space-km", circle(48.8566, 2.3522, "350 km"), paris350));
        cases.add(new Case("circle-paris-217mi", circle(48.8566, 2.3522, "217mi"), paris350));
        cases.add(new Case("circle-paris-189nmi", circle(48.8566, 2.3522, "189nmi"), paris350));
        cases.add(new Case("circle-paris-350000m", circle(48.8566, 2.3522, "350000m"), paris350));
        cases.add(new Case("circle-paris-350000-no-unit", circle(48.8566, 2.3522, "350000"), paris350));
        cases.add(new Case("circle-paris-20000km-skips-no-location", circle(48.8566, 2.3522, "20000km"), allWithLocation()));
        cases.add(new Case("circle-integer-center-100km", circle(5, 5, "100km"), ids("box-inside", "box-near-in")));
        cases.add(new Case("circle-fiji-1200km-antimeridian", circle(-17.7134, 178.0650, "1200km"), ids("fiji", "samoa")));
        cases.add(new Case("rectangle-box-0-10", rectangle(10.0, 10.0, 0.0, 0.0), box));
        cases.add(new Case("rectangle-box-integer-params", rectangle(10, 10, 0, 0), box));
        cases.add(new Case("rectangle-europe", rectangle(51.0, 5.0, 46.0, -1.0), ids("paris", "brussels")));
        cases.add(new Case("rectangle-antimeridian", rectangle(-10.0, -170.0, -20.0, 175.0), ids("fiji", "samoa")));
        cases.add(new Case("rectangle-corners-swapped", rectangle(0.0, 0.0, 10.0, 10.0), error()));
        Map<String, Object> defaultName = circle(48.8566, 2.3522, "350km");
        defaultName.remove("name");
        cases.add(new Case("circle-default-field-name", defaultName, error()));
        Map<String, Object> unknownType = circle(48.8566, 2.3522, "350km");
        unknownType.put("type", "polygon");
        // Both builders return no query for an unknown type, so the condition does not filter anything.
        cases.add(new Case("unknown-type", unknownType, allSessions()));
        return cases;
    }

    @Before
    public void seed() throws InterruptedException {
        assertNotNull(definitionsService.getConditionType(CONDITION_TYPE));
        timestamp = new Date();
        Profile profile = new Profile(PREFIX + "profile");
        for (Map.Entry<String, double[]> e : LOCATIONS.entrySet()) {
            Session session = new Session(PREFIX + e.getKey(), profile, timestamp, SCOPE);
            Map<String, Object> location = new LinkedHashMap<>();
            location.put("lat", e.getValue()[0]);
            location.put("lon", e.getValue()[1]);
            session.setProperty("location", location);
            persistenceService.save(session);
            seededIds.add(session.getItemId());
        }
        Session noLocation = new Session(PREFIX + NO_LOCATION, profile, timestamp, SCOPE);
        persistenceService.save(noLocation);
        seededIds.add(noLocation.getItemId());

        persistenceService.refreshIndex(Session.class, timestamp);
        keepTrying("Seeded sessions not searchable",
                () -> persistenceService.queryCount(scopeCondition(), Session.ITEM_TYPE),
                count -> count == seededIds.size(), DEFAULT_TRYING_TIMEOUT, DEFAULT_TRYING_TRIES);
    }

    @After
    public void cleanup() {
        for (String id : seededIds) {
            persistenceService.remove(id, Session.class);
        }
        seededIds.clear();
        persistenceService.refreshIndex(Session.class, timestamp);
    }

    private Condition scopeCondition() {
        return definitionsService.getConditionBuilder().sessionProperty("scope").equalTo(SCOPE).build();
    }

    private Condition geoCondition(Map<String, Object> parameters) {
        Condition geo = new Condition(definitionsService.getConditionType(CONDITION_TYPE));
        parameters.forEach(geo::setParameter);
        Condition and = new Condition(definitionsService.getConditionType("booleanCondition"));
        and.setParameter("operator", "and");
        and.setParameter("subConditions", Arrays.asList(scopeCondition(), geo));
        return and;
    }

    private Set<String> runQuery(Condition condition) {
        try {
            List<Session> sessions = persistenceService.query(condition, null, Session.class);
            if (sessions == null) {
                return error();
            }
            return sessions.stream().map(Session::getItemId).collect(Collectors.toCollection(TreeSet::new));
        } catch (Exception e) {
            LOGGER.info("Geo parity query failed: {}", e.toString());
            return error();
        }
    }

    private long runCount(Condition condition) {
        try {
            return persistenceService.queryCount(condition, Session.ITEM_TYPE);
        } catch (Exception e) {
            LOGGER.info("Geo parity count failed: {}", e.toString());
            return -1;
        }
    }

    @Test
    public void testSameResultsOnBothBackends() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        for (Case c : cases()) {
            Condition condition = geoCondition(new LinkedHashMap<>(c.parameters));
            Set<String> actual = runQuery(condition);
            long count = runCount(condition);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("parameters", c.parameters);
            entry.put("ids", actual);
            entry.put("count", count);
            report.put(c.name, entry);

            if (!c.expected.equals(actual)) {
                failures.add(c.name + ": expected " + c.expected + " but got " + actual);
            } else if (!actual.contains(ERROR) && count != actual.size()) {
                failures.add(c.name + ": queryCount " + count + " does not match query size " + actual.size());
            }
        }
        writeReport(report);
        assertTrue("Geo condition results differ from expected on " + searchEngine + ":\n" + String.join("\n", failures),
                failures.isEmpty());
        assertEquals(cases().size(), report.size());
    }

    private void writeReport(Map<String, Object> report) {
        try {
            Path dir = parityDir();
            Files.createDirectories(dir);
            Path file = dir.resolve("geolocation-" + searchEngine + ".json");
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(file.toFile(), report);
            LOGGER.info("Geo parity report written to {}", file);
        } catch (Exception e) {
            LOGGER.warn("Could not write geo parity report", e);
        }
    }

    /**
     * The test runs inside the Karaf container under {@code itests/target/exam/<id>}; walk up to {@code target}.
     */
    private static Path parityDir() {
        Path dir = Paths.get(System.getProperty("karaf.home", System.getProperty("user.dir", "."))).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (p.getFileName() != null && "exam".equals(p.getFileName().toString()) && p.getParent() != null) {
                return p.getParent().resolve("parity");
            }
        }
        return dir.resolve("target").resolve("parity");
    }
}
