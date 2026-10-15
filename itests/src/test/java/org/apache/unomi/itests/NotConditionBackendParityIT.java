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

package org.apache.unomi.itests;

import org.apache.unomi.api.Event;
import org.apache.unomi.api.Item;
import org.apache.unomi.api.Profile;
import org.apache.unomi.api.conditions.Condition;
import org.apache.unomi.api.utils.ConditionBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Checks that the notCondition query builders of Elasticsearch and OpenSearch return the same items.
 * <p>
 * Each case runs one condition against a fixed data set and checks the exact set of matched IDs. The
 * expected IDs are the same constants for both engines, so this test passing on both engines shows that
 * both return identical results. The in-memory condition evaluator is also checked against the same
 * constants, so it acts as the reference for what the condition means. The actual IDs per case are written
 * to {@code itests/target/parity/not-<engine>.json} so that the runs of the two engines can be compared.
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerSuite.class)
public class NotConditionBackendParityIT extends BaseIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotConditionBackendParityIT.class);

    private static final String P1 = "notParity-p1";
    private static final String P2 = "notParity-p2";
    private static final String P3 = "notParity-p3";
    private static final String P4 = "notParity-p4";
    private static final String P5 = "notParity-p5";
    private static final String P6 = "notParity-p6";
    private static final List<String> PROFILE_IDS = Arrays.asList(P1, P2, P3, P4, P5, P6);

    private static final String E1 = "notParity-e1";
    private static final String E2 = "notParity-e2";
    private static final String E3 = "notParity-e3";
    private static final String E4 = "notParity-e4";
    private static final List<String> EVENT_IDS = Arrays.asList(E1, E2, E3, E4);

    private ConditionBuilder builder;
    private final Map<String, Profile> profiles = new LinkedHashMap<>();
    private final Map<String, Event> events = new LinkedHashMap<>();

    @Before
    public void setUp() throws InterruptedException {
        builder = definitionsService.getConditionBuilder();

        // p1: every property set, two interests, two tags
        Profile p1 = new Profile(P1);
        p1.setProperty("gender", "female");
        p1.setProperty("age", 30);
        p1.setProperty("interests", Arrays.asList(interest("cars", 50), interest("football", 15)));
        p1.setProperty("npTags", Arrays.asList("red", "blue"));
        p1.setSegments(new HashSet<>(Arrays.asList("s1", "s2")));
        // p2: other values
        Profile p2 = new Profile(P2);
        p2.setProperty("gender", "male");
        p2.setProperty("age", 40);
        p2.setProperty("interests", Collections.singletonList(interest("cars", 10)));
        p2.setProperty("npTags", Collections.singletonList("green"));
        p2.setSegments(new HashSet<>(Collections.singletonList("s2")));
        // p3: no gender, no interests, no tags, no segments
        Profile p3 = new Profile(P3);
        p3.setProperty("age", 25);
        // p4: no age
        Profile p4 = new Profile(P4);
        p4.setProperty("gender", "female");
        p4.setProperty("npTags", Collections.singletonList("red"));
        p4.setSegments(new HashSet<>(Collections.singletonList("s3")));
        // p5: no properties at all
        Profile p5 = new Profile(P5);
        // p6: a third gender value and a high football interest
        Profile p6 = new Profile(P6);
        p6.setProperty("gender", "other");
        p6.setProperty("age", 30);
        p6.setProperty("interests", Collections.singletonList(interest("football", 70)));

        for (Profile p : Arrays.asList(p1, p2, p3, p4, p5, p6)) {
            profiles.put(p.getItemId(), p);
            persistenceService.save(p);
        }

        Date now = new Date();
        Map<String, Object> flagged = new HashMap<>();
        flagged.put("npFlag", "yes");
        events.put(E1, new Event(E1, "view", null, p1, "notParityScope", null, null, flagged, now, true));
        events.put(E2, new Event(E2, "click", null, p1, "notParityScope", null, null, null, now, true));
        events.put(E3, new Event(E3, "view", null, p2, "notParityScope", null, null, null, now, true));
        events.put(E4, new Event(E4, "login", null, p3, "notParityScope", null, null, flagged, now, true));
        for (Event e : events.values()) {
            persistenceService.save(e);
        }

        refreshPersistence(Profile.class, Event.class);
    }

    @After
    public void tearDown() throws InterruptedException {
        for (String id : PROFILE_IDS) {
            persistenceService.remove(id, Profile.class);
        }
        for (String id : EVENT_IDS) {
            persistenceService.remove(id, Event.class);
        }
        refreshPersistence(Profile.class, Event.class);
    }

    @Test
    public void testNotConditionParity() throws Exception {
        List<ParityCase> cases = new ArrayList<>();

        // Negation over a property: documents without the property must match.
        cases.add(profileCase("not-equals-missing-field-included",
                () -> builder.not(builder.profileProperty("properties.gender").equalTo("female")).build(),
                P2, P3, P5, P6));
        cases.add(profileCase("not-exists",
                () -> builder.not(builder.profileProperty("properties.gender").exists()).build(),
                P3, P5));
        cases.add(profileCase("not-missing",
                () -> builder.not(builder.profileProperty("properties.gender").missing()).build(),
                P1, P2, P4, P6));
        cases.add(profileCase("not-range-missing-field-included",
                () -> builder.not(builder.profileProperty("properties.age").greaterThan(26)).build(),
                P3, P4, P5));
        cases.add(profileCase("not-in-multivalued",
                () -> builder.not(builder.profileProperty("properties.npTags").in("red")).build(),
                P2, P3, P5, P6));

        // Sub-condition matching nothing / everything.
        cases.add(profileCase("not-sub-matches-nothing",
                () -> builder.not(builder.profileProperty("properties.gender").equalTo("nobody")).build(),
                P1, P2, P3, P4, P5, P6));
        cases.add(profileCase("not-match-all",
                () -> builder.not(builder.matchAll()).build()));
        cases.add(profileCase("not-sub-with-unset-parameter",
                () -> {
                    Condition sub = builder.profileProperty("properties.gender").equalTo("female").build();
                    sub.setParameter("propertyValue", "parameter::notParityUnsetParam");
                    Condition not = new Condition(definitionsService.getConditionType("notCondition"));
                    not.setParameter("subCondition", sub);
                    return not;
                }));

        // Both query builders reject a missing subCondition (the evaluator returns false); check they fail the same way.
        ParityCase noSubCondition = profileCase("not-without-sub-condition",
                () -> new Condition(definitionsService.getConditionType("notCondition")));
        noSubCondition.expectedError = IllegalArgumentException.class;
        cases.add(noSubCondition);

        // Double negation.
        cases.add(profileCase("double-not",
                () -> builder.not(builder.not(builder.profileProperty("properties.gender").equalTo("female"))).build(),
                P1, P4));
        cases.add(profileCase("triple-not",
                () -> builder.not(builder.not(builder.not(builder.profileProperty("properties.gender").exists()))).build(),
                P3, P5));

        // Negation of boolean and nested conditions.
        cases.add(profileCase("not-and",
                () -> builder.not(builder.and(builder.profileProperty("properties.gender").equalTo("female"),
                        builder.profileProperty("properties.age").equalTo(30))).build(),
                P2, P3, P4, P5, P6));
        cases.add(profileCase("not-or",
                () -> builder.not(builder.or(builder.profileProperty("properties.gender").equalTo("male"),
                        builder.profileProperty("properties.age").greaterThanOrEqualTo(30))).build(),
                P3, P4, P5));
        cases.add(profileCase("and-with-not-inside",
                () -> builder.and(builder.profileProperty("properties.age").exists(),
                        builder.not(builder.profileProperty("properties.gender").equalTo("female"))).build(),
                P2, P3, P6));
        cases.add(profileCase("not-nested",
                () -> negate(nestedInterest("cars", "greaterThan", 45)),
                P2, P3, P4, P5, P6));
        cases.add(profileCase("not-nested-boolean",
                () -> negate(nestedInterest("football", "greaterThan", 50)),
                P1, P2, P3, P4, P5));
        cases.add(profileCase("not-segment",
                () -> builder.not(builder.condition("profileSegmentCondition").parameter("matchType", "in")
                        .parameter("segments", Collections.singletonList("s2"))).build(),
                P3, P4, P5, P6));

        // Events (rollover index).
        cases.add(eventCase("event-not-type",
                () -> builder.not(builder.condition("eventTypeCondition").parameter("eventTypeId", "view")).build(),
                E2, E4));
        cases.add(eventCase("event-not-property-exists",
                () -> builder.not(builder.eventProperty("properties.npFlag").exists()).build(),
                E2, E3));
        cases.add(eventCase("event-not-and-type-property",
                () -> builder.not(builder.and(builder.condition("eventTypeCondition").parameter("eventTypeId", "view"),
                        builder.eventProperty("properties.npFlag").equalTo("yes"))).build(),
                E2, E3, E4));

        String engine = persistenceService.getName();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("engine", engine);
        Map<String, Object> results = new LinkedHashMap<>();
        report.put("cases", results);
        List<String> failures = new ArrayList<>();

        for (ParityCase c : cases) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("expected", c.expected);
            Condition condition = c.condition.get();
            if (c.expectedError != null) {
                String queryError = errorOf(() -> c.query(condition));
                String countError = errorOf(() -> c.count(condition));
                Set<String> oracle = c.evaluate(condition);
                entry.put("queryError", queryError);
                entry.put("queryCountError", countError);
                entry.put("evaluator", oracle);
                String expectedError = c.expectedError.getName();
                if (queryError == null || !queryError.startsWith(expectedError)) {
                    failures.add(c.name + ": query expected " + expectedError + " but got " + queryError);
                }
                if (countError == null || !countError.startsWith(expectedError)) {
                    failures.add(c.name + ": queryCount expected " + expectedError + " but got " + countError);
                }
                if (!c.expected.equals(oracle)) {
                    failures.add(c.name + ": evaluator expected " + c.expected + " but was " + oracle);
                }
                results.put(c.name, entry);
                continue;
            }
            try {
                Set<String> queried = c.query(condition);
                long count = c.count(condition);
                Set<String> oracle = c.evaluate(condition);
                entry.put("query", queried);
                entry.put("queryCount", count);
                entry.put("evaluator", oracle);
                if (!c.expected.equals(queried)) {
                    failures.add(c.name + ": query expected " + c.expected + " but was " + queried);
                }
                if (count != c.expected.size()) {
                    failures.add(c.name + ": queryCount expected " + c.expected.size() + " but was " + count);
                }
                if (!c.expected.equals(oracle)) {
                    failures.add(c.name + ": evaluator expected " + c.expected + " but was " + oracle);
                }
            } catch (Exception e) {
                entry.put("error", e.getClass().getName() + ": " + e.getMessage());
                failures.add(c.name + ": " + e);
            }
            results.put(c.name, entry);
        }

        writeReport(engine, report);
        assertTrue("notCondition results differ from expected on " + engine + ":\n" + String.join("\n", failures),
                failures.isEmpty());
    }

    private static String errorOf(Runnable call) {
        try {
            call.run();
            return null;
        } catch (Exception e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    private void writeReport(String engine, Map<String, Object> report) throws Exception {
        File dir = new File(findTargetDir(), "parity");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create " + dir);
        }
        File out = new File(dir, "not-" + engine + ".json");
        getObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out, report);
        LOGGER.info("notCondition parity report written to {}", out.getAbsolutePath());
    }

    /** The Karaf data folder sits under itests/target/exam/&lt;id&gt;/data; walk up to itests/target. */
    private File findTargetDir() {
        File dir = new File(karafData()).getAbsoluteFile();
        while (dir != null && !"target".equals(dir.getName())) {
            dir = dir.getParentFile();
        }
        return dir != null ? dir : new File(karafData());
    }

    private Condition negate(Condition subCondition) {
        Condition not = new Condition(definitionsService.getConditionType("notCondition"));
        not.setParameter("subCondition", subCondition);
        return not;
    }

    private Condition nestedInterest(String key, String operator, int value) {
        Condition keyCondition = builder.profileProperty("properties.interests.key").equalTo(key).build();
        Condition valueCondition = new Condition(definitionsService.getConditionType("profilePropertyCondition"));
        valueCondition.setParameter("propertyName", "properties.interests.value");
        valueCondition.setParameter("comparisonOperator", operator);
        valueCondition.setParameter("propertyValueInteger", value);
        Condition and = new Condition(definitionsService.getConditionType("booleanCondition"));
        and.setParameter("operator", "and");
        and.setParameter("subConditions", Arrays.asList(keyCondition, valueCondition));
        Condition nested = new Condition(definitionsService.getConditionType("nestedCondition"));
        nested.setParameter("path", "properties.interests");
        nested.setParameter("subCondition", and);
        return nested;
    }

    private static Map<String, Object> interest(String key, int value) {
        Map<String, Object> interest = new HashMap<>();
        interest.put("key", key);
        interest.put("value", value);
        return interest;
    }

    private ParityCase profileCase(String name, Supplier<Condition> condition, String... expected) {
        return new ParityCase(name, condition, Profile.class, PROFILE_IDS, profiles, expected);
    }

    private ParityCase eventCase(String name, Supplier<Condition> condition, String... expected) {
        return new ParityCase(name, condition, Event.class, EVENT_IDS, events, expected);
    }

    private class ParityCase {
        final String name;
        final Supplier<Condition> condition;
        final Class<? extends Item> itemClass;
        final List<String> scopeIds;
        final Map<String, ? extends Item> items;
        final Set<String> expected;
        Class<? extends Exception> expectedError;

        ParityCase(String name, Supplier<Condition> condition, Class<? extends Item> itemClass, List<String> scopeIds,
                   Map<String, ? extends Item> items, String... expected) {
            this.name = name;
            this.condition = condition;
            this.itemClass = itemClass;
            this.scopeIds = scopeIds;
            this.items = items;
            this.expected = new TreeSet<>(Arrays.asList(expected));
        }

        /** Restricts the condition to the seeded items so data from other tests does not leak in. */
        private Condition scoped(Condition condition) {
            Condition ids = new Condition(definitionsService.getConditionType("idsCondition"));
            ids.setParameter("ids", scopeIds);
            ids.setParameter("match", true);
            Condition and = new Condition(definitionsService.getConditionType("booleanCondition"));
            and.setParameter("operator", "and");
            and.setParameter("subConditions", Arrays.asList(ids, condition));
            return and;
        }

        Set<String> query(Condition condition) {
            return persistenceService.query(scoped(condition), null, itemClass).stream()
                    .map(Item::getItemId).collect(Collectors.toCollection(TreeSet::new));
        }

        long count(Condition condition) {
            return persistenceService.queryCount(scoped(condition), Item.getItemType(itemClass));
        }

        Set<String> evaluate(Condition condition) {
            return items.values().stream().filter(item -> persistenceService.testMatch(condition, item))
                    .map(Item::getItemId).collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
