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

import org.apache.unomi.api.Item;
import org.apache.unomi.api.Profile;
import org.apache.unomi.api.Session;
import org.apache.unomi.api.conditions.Condition;
import org.apache.unomi.api.conditions.ConditionType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerSuite;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.Assert.assertTrue;

/**
 * Runs a fixed table of {@code idsCondition} queries against a fixed data set and checks the exact
 * matched item IDs and counts. The expected values are the same constants for Elasticsearch and
 * OpenSearch, so this test passing on both engines shows both return identical results.
 * <p>
 * The actual per-case results are also written to {@code itests/target/parity/ids-<engine>.json}
 * so that the two runs can be diffed directly. The in-memory condition evaluator result is
 * recorded next to each case as a semantic reference.
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerSuite.class)
public class IdsConditionBackendParityIT extends BaseIT {

    private static final String MARKER_PROPERTY = "idsParityMarker";
    private static final String MARKER = "ids-parity";

    private static final String P1 = "ids-parity-p1";
    private static final String P2 = "ids-parity-p2";
    private static final String P3 = "ids-parity-p3";
    private static final String P4 = "ids-parity-p4";
    private static final String P5 = "ids-parity-p5";
    private static final String S1 = "ids-parity-s1";
    private static final String S2 = "ids-parity-s2";
    private static final String MISSING = "ids-parity-missing";

    private static final List<String> PROFILE_IDS = List.of(P1, P2, P3, P4, P5);
    private static final List<String> SESSION_IDS = List.of(S1, S2);

    private static final String LEGACY_TYPE_FILE = "data/tmp/conditions/testIdsConditionLegacy.json";
    private static final String NEW_TYPE_FILE = "data/tmp/conditions/testIdsConditionNew.json";

    private final List<Profile> profiles = new ArrayList<>();
    private final List<Session> sessions = new ArrayList<>();
    private final List<String> registeredConditionTypes = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        for (String id : PROFILE_IDS) {
            Profile profile = new Profile(id);
            profile.setProperty(MARKER_PROPERTY, MARKER);
            persistenceService.save(profile);
            profiles.add(profile);
        }
        Date timestamp = new Date();
        for (String id : SESSION_IDS) {
            Session session = new Session(id, profiles.get(0), timestamp, "ids-parity-scope");
            session.setProperty(MARKER_PROPERTY, MARKER);
            persistenceService.save(session);
            sessions.add(session);
        }
        registerConditionType(LEGACY_TYPE_FILE);
        registerConditionType(NEW_TYPE_FILE);
        persistenceService.refreshIndex(Profile.class);
        persistenceService.refreshIndex(Session.class);
        keepTrying("Seeded profiles not searchable",
                () -> persistenceService.queryCount(marker(Profile.ITEM_TYPE), Profile.ITEM_TYPE),
                count -> count == PROFILE_IDS.size(), DEFAULT_TRYING_TIMEOUT, DEFAULT_TRYING_TRIES);
        keepTrying("Seeded sessions not searchable",
                () -> persistenceService.queryCount(marker(Session.ITEM_TYPE), Session.ITEM_TYPE),
                count -> count == SESSION_IDS.size(), DEFAULT_TRYING_TIMEOUT, DEFAULT_TRYING_TRIES);
    }

    @After
    public void tearDown() {
        for (String id : PROFILE_IDS) {
            persistenceService.remove(id, Profile.class);
        }
        for (String id : SESSION_IDS) {
            persistenceService.remove(id, Session.class);
        }
        for (String typeId : registeredConditionTypes) {
            definitionsService.removeConditionType(typeId);
        }
        persistenceService.refreshIndex(Profile.class);
        persistenceService.refreshIndex(Session.class);
        profiles.clear();
        sessions.clear();
        registeredConditionTypes.clear();
    }

    @Test
    public void testIdsConditionBackendParity() throws Exception {
        List<String> bigList = IntStream.range(0, 4999).mapToObj(i -> "ids-parity-filler-" + i).collect(Collectors.toList());
        List<String> atLimit = new ArrayList<>(bigList);
        atLimit.add(P2);
        List<String> overLimit = new ArrayList<>(atLimit);
        overLimit.add("ids-parity-filler-overflow");

        List<Case> cases = List.of(
                // match=true
                Case.profiles("match-single", "idsCondition", List.of(P1), true, set(P1), 1),
                Case.profiles("match-multiple", "idsCondition", List.of(P1, P3, P5), true, set(P1, P3, P5), 3),
                Case.profiles("match-with-unknown-id", "idsCondition", List.of(P2, MISSING), true, set(P2), 1),
                Case.profiles("match-only-unknown-id", "idsCondition", List.of(MISSING), true, set(), 0),
                Case.profiles("match-duplicate-ids", "idsCondition", List.of(P4, P4), true, set(P4), 1),
                Case.profiles("match-wrong-case", "idsCondition", List.of(P1.toUpperCase(Locale.ROOT)), true, set(), 0),
                Case.profiles("match-empty-list", "idsCondition", List.of(), true, set(), 0),
                Case.profiles("match-session-id-on-profiles", "idsCondition", List.of(S1), true, set(), 0),
                Case.sessions("match-session", "idsCondition", List.of(S2), true, set(S2), 1),
                Case.sessions("match-profile-id-on-sessions", "idsCondition", List.of(P1), true, set(), 0),
                // match=false
                Case.profiles("not-match-single", "idsCondition", List.of(P1), false, set(P2, P3, P4, P5), 4),
                Case.profiles("not-match-multiple", "idsCondition", List.of(P1, P3, P5), false, set(P2, P4), 2),
                Case.profiles("not-match-with-unknown-id", "idsCondition", List.of(P2, MISSING), false, set(P1, P3, P4, P5), 4),
                Case.profiles("not-match-all-ids", "idsCondition", PROFILE_IDS, false, set(), 0),
                Case.profiles("not-match-empty-list", "idsCondition", List.of(), false, set(), 0),
                Case.profiles("match-ids-omitted", "idsCondition", null, true, set(), 0),
                Case.profiles("not-match-ids-omitted", "idsCondition", null, false, set(), 0),
                Case.profiles("match-omitted", "idsCondition", List.of(P3), null, set(P3), 1),
                Case.sessions("not-match-session", "idsCondition", List.of(S2), false, set(S1), 1),
                // maximum ids limit (default 5000)
                Case.profiles("match-at-limit-5000", "idsCondition", atLimit, true, set(P2), 1),
                Case.profiles("not-match-at-limit-5000", "idsCondition", atLimit, false, set(P1, P3, P4, P5), 4),
                Case.profiles("match-over-limit-5001", "idsCondition", overLimit, true, null, -1)
                        .expectError("UnsupportedOperationException"),
                Case.profiles("not-match-over-limit-5001", "idsCondition", overLimit, false, null, -1)
                        .expectError("UnsupportedOperationException"),
                // legacy and new test condition types (queryBuilder id mapping)
                Case.profiles("legacy-type-match", "testIdsConditionLegacy", List.of(P1, P2), true, set(P1, P2), 2),
                Case.profiles("legacy-type-not-match", "testIdsConditionLegacy", List.of(P1, P2), false, set(P3, P4, P5), 3),
                Case.profiles("new-type-match", "testIdsConditionNew", List.of(P1, P2), true, set(P1, P2), 2),
                Case.profiles("new-type-not-match", "testIdsConditionNew", List.of(P1, P2), false, set(P3, P4, P5), 3),
                Case.profiles("legacy-type-match-omitted", "testIdsConditionLegacy", List.of(P3), null, set(P3), 1),
                Case.profiles("new-type-match-omitted", "testIdsConditionNew", List.of(P3), null, set(P3), 1)
        );

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("engine", searchEngine);
        Map<String, Object> results = new LinkedHashMap<>();
        report.put("cases", results);
        List<String> failures = new ArrayList<>();

        for (Case c : cases) {
            Outcome actual = run(c);
            results.put(c.name, actual.toMap());
            if (!actual.matches(c)) {
                failures.add(c.name + ": expected " + Outcome.expected(c).toMap() + " but got " + actual.toMap());
            }
            if (c.expectedError == null && !Objects.equals(c.expectedIds, actual.evaluatorIds)) {
                failures.add(c.name + ": expected IDs " + c.expectedIds + " do not match the in-memory evaluator " + actual.evaluatorIds);
            }
        }

        writeReport(report);
        assertTrue("IdsCondition results on " + searchEngine + " differ from the expected constants:\n"
                + String.join("\n", failures), failures.isEmpty());
    }

    private Outcome run(Case c) {
        Outcome outcome = new Outcome();
        Condition ids = idsCondition(c);
        Condition scoped = and(marker(c.itemType), ids);
        Class<? extends Item> clazz = Profile.ITEM_TYPE.equals(c.itemType) ? Profile.class : Session.class;
        try {
            outcome.ids = persistenceService.query(scoped, null, clazz, 0, 100).getList().stream()
                    .map(Item::getItemId).collect(Collectors.toCollection(TreeSet::new));
        } catch (RuntimeException e) {
            outcome.queryError = rootCauseName(e);
        }
        try {
            outcome.count = persistenceService.queryCount(scoped, c.itemType);
        } catch (RuntimeException e) {
            outcome.countError = rootCauseName(e);
        }
        outcome.evaluatorIds = evaluate(c);
        return outcome;
    }

    /**
     * Uses the in-memory condition evaluator (the same one used by ConditionEvaluatorIT) as a semantic reference.
     */
    private Set<String> evaluate(Case c) {
        Condition ids = new Condition(definitionsService.getConditionType("idsCondition"));
        if (c.ids != null) {
            ids.setParameter("ids", c.ids);
        }
        if (c.match != null) {
            ids.setParameter("match", c.match);
        }
        List<? extends Item> items = Profile.ITEM_TYPE.equals(c.itemType) ? profiles : sessions;
        Set<String> matched = new TreeSet<>();
        try {
            for (Item item : items) {
                if (persistenceService.testMatch(ids, item)) {
                    matched.add(item.getItemId());
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return matched;
    }

    private Condition idsCondition(Case c) {
        Condition condition = new Condition(definitionsService.getConditionType(c.conditionTypeId));
        if (c.ids != null) {
            condition.setParameter("ids", c.ids);
        }
        if (c.match != null) {
            condition.setParameter("match", c.match);
        }
        return condition;
    }

    private Condition marker(String itemType) {
        String conditionTypeId = Profile.ITEM_TYPE.equals(itemType) ? "profilePropertyCondition" : "sessionPropertyCondition";
        Condition condition = new Condition(definitionsService.getConditionType(conditionTypeId));
        condition.setParameter("propertyName", "properties." + MARKER_PROPERTY);
        condition.setParameter("comparisonOperator", "equals");
        condition.setParameter("propertyValue", MARKER);
        return condition;
    }

    private Condition and(Condition... subConditions) {
        Condition condition = new Condition(definitionsService.getConditionType("booleanCondition"));
        condition.setParameter("operator", "and");
        condition.setParameter("subConditions", Arrays.asList(subConditions));
        return condition;
    }

    private void registerConditionType(String file) throws Exception {
        ConditionType type = getObjectMapper().readValue(new File(file).toURI().toURL(), ConditionType.class);
        definitionsService.setConditionType(type);
        registeredConditionTypes.add(type.getItemId());
    }

    private void writeReport(Map<String, Object> report) throws Exception {
        Path dir = findTargetDir().resolve("parity");
        Files.createDirectories(dir);
        Path file = dir.resolve("ids-" + searchEngine + ".json");
        getObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
        System.out.println("IdsCondition parity results written to " + file.toAbsolutePath());
    }

    /**
     * Karaf runs from itests/target/exam/&lt;id&gt;, so walk up to itests/target.
     */
    private static Path findTargetDir() {
        Path start = Paths.get(System.getProperty("karaf.home", System.getProperty("user.dir"))).toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            if ("target".equals(String.valueOf(p.getFileName()))) {
                return p;
            }
        }
        return start;
    }

    private static String rootCauseName(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    private static Set<String> set(String... ids) {
        return new TreeSet<>(Arrays.asList(ids));
    }

    private static final class Case {
        final String name;
        final String itemType;
        final String conditionTypeId;
        final List<String> ids;
        final Boolean match;
        final Set<String> expectedIds;
        final long expectedCount;
        String expectedError;

        private Case(String name, String itemType, String conditionTypeId, List<String> ids, Boolean match,
                     Set<String> expectedIds, long expectedCount) {
            this.name = name;
            this.itemType = itemType;
            this.conditionTypeId = conditionTypeId;
            this.ids = ids;
            this.match = match;
            this.expectedIds = expectedIds;
            this.expectedCount = expectedCount;
        }

        static Case profiles(String name, String type, List<String> ids, Boolean match, Set<String> expected, long count) {
            return new Case(name, Profile.ITEM_TYPE, type, ids, match, expected, count);
        }

        static Case sessions(String name, String type, List<String> ids, Boolean match, Set<String> expected, long count) {
            return new Case(name, Session.ITEM_TYPE, type, ids, match, expected, count);
        }

        Case expectError(String errorName) {
            this.expectedError = errorName;
            return this;
        }
    }

    private static final class Outcome {
        Set<String> ids;
        String queryError;
        Long count;
        String countError;
        Set<String> evaluatorIds;

        static Outcome expected(Case c) {
            Outcome o = new Outcome();
            if (c.expectedError != null) {
                o.queryError = c.expectedError;
                o.count = c.expectedCount;
            } else {
                o.ids = c.expectedIds;
                o.count = c.expectedCount;
            }
            return o;
        }

        boolean matches(Case c) {
            Outcome e = expected(c);
            return Objects.equals(e.ids, ids) && Objects.equals(e.queryError, queryError)
                    && Objects.equals(e.count, count) && countError == null;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ids", queryError != null ? "error:" + queryError : ids);
            m.put("count", countError != null ? "error:" + countError : count);
            if (evaluatorIds != null) {
                m.put("evaluatorIds", evaluatorIds);
            }
            return m;
        }
    }
}
