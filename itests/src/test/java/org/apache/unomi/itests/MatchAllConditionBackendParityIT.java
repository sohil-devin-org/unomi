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
import org.apache.unomi.api.Persona;
import org.apache.unomi.api.Profile;
import org.apache.unomi.api.Session;
import org.apache.unomi.api.conditions.Condition;
import org.apache.unomi.api.tenants.Tenant;
import org.apache.unomi.api.utils.ConditionBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerSuite;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Checks that the {@code matchAllCondition} query builders of Elasticsearch and OpenSearch return the same items.
 * <p>
 * The test seeds a fixed dataset and runs a table of conditions through {@code query(...)} and
 * {@code queryCount(...)}. Each case has one expected ID set that is the same constant for both engines, so the
 * test passing on both engines proves they return identical results. The in-memory condition evaluator
 * ({@code testMatch}) is checked against the same expectation as the semantic oracle. The actual ID sets per case
 * are written to {@code itests/target/parity/matchall-<engine>.json} so the two runs can be diffed.
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerSuite.class)
public class MatchAllConditionBackendParityIT extends BaseIT {

    private static final String PREFIX = "matchall-parity-";
    private static final String P1 = PREFIX + "profile-1";
    private static final String P2 = PREFIX + "profile-2";
    private static final String P3 = PREFIX + "profile-3";
    private static final String PERSONA = PREFIX + "persona-1";
    private static final String S1 = PREFIX + "session-1";
    private static final String S2 = PREFIX + "session-2";
    private static final String E1 = PREFIX + "event-1";
    private static final String E2 = PREFIX + "event-2";
    private static final String E3 = PREFIX + "event-3";
    private static final String OTHER_TENANT_ID = "matchallparityother";
    private static final String OTHER_PROFILE = PREFIX + "other-tenant-profile";
    private static final String OTHER_SESSION = PREFIX + "other-tenant-session";
    private static final String OTHER_EVENT = PREFIX + "other-tenant-event";

    private static final List<String> ALL_PROFILES = List.of(P1, P2, P3);

    private ConditionBuilder builder;
    private final Map<String, Item> seeded = new LinkedHashMap<>();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private Tenant otherTenant;

    @Before
    public void setUp() throws Exception {
        builder = definitionsService.getConditionBuilder();
        removeItems(Profile.class, Persona.class, Session.class, Event.class);

        Date now = new Date();
        Profile p1 = profile(P1, "female", 30);
        Profile p2 = profile(P2, "male", 40);
        Profile p3 = profile(P3, "female", 20);
        Persona persona = new Persona(PERSONA);
        persona.setProperty("gender", "female");
        persona.setProperty("age", 30);
        Session s1 = new Session(S1, p1, now, "parity");
        Session s2 = new Session(S2, p2, now, "parity");
        Event e1 = new Event(E1, "view", s1, p1, "parity", null, null, now);
        Event e2 = new Event(E2, "view", s2, p2, "parity", null, null, now);
        Event e3 = new Event(E3, "login", s1, p1, "parity", null, null, now);
        for (Item item : List.of(p1, p2, p3, persona, s1, s2, e1, e2, e3)) {
            assertTrue("Could not save " + item.getItemId(), persistenceService.save(item));
            seeded.put(item.getItemId(), item);
        }

        otherTenant = tenantService.getTenant(OTHER_TENANT_ID);
        if (otherTenant == null) {
            otherTenant = tenantService.createTenant(OTHER_TENANT_ID, Collections.emptyMap());
        }
        executionContextManager.executeAsTenant(otherTenant.getItemId(), () -> {
            Profile other = profile(OTHER_PROFILE, "female", 30);
            Session otherSession = new Session(OTHER_SESSION, other, now, "parity");
            persistenceService.save(other);
            persistenceService.save(otherSession);
            persistenceService.save(new Event(OTHER_EVENT, "view", otherSession, other, "parity", null, null, now));
        });
        refreshPersistence(Profile.class, Persona.class, Session.class, Event.class);
    }

    @After
    public void tearDown() throws Exception {
        try {
            for (Item item : seeded.values()) {
                persistenceService.remove(item.getItemId(), item.getClass());
            }
            if (otherTenant != null) {
                executionContextManager.executeAsTenant(otherTenant.getItemId(), () -> {
                    persistenceService.remove(OTHER_EVENT, Event.class);
                    persistenceService.remove(OTHER_SESSION, Session.class);
                    persistenceService.remove(OTHER_PROFILE, Profile.class);
                });
                tenantService.deleteTenant(otherTenant.getItemId());
            }
            refreshPersistence(Profile.class, Persona.class, Session.class, Event.class);
        } finally {
            seeded.clear();
        }
    }

    @Test
    public void matchAllReturnsTheSameItemsOnEveryBackend() throws Exception {
        List<String> failures = new ArrayList<>();

        // Plain matchAll: every item of the queried type, only that type, only the current tenant
        check(failures, "profile/matchAll", Profile.class, matchAll(), ALL_PROFILES);
        check(failures, "persona/matchAll", Persona.class, matchAll(), List.of(PERSONA));
        check(failures, "session/matchAll", Session.class, matchAll(), List.of(S1, S2));
        check(failures, "event/matchAll", Event.class, matchAll(), List.of(E1, E2, E3));

        // matchAll inside boolean and not conditions
        Condition female = builder.profileProperty("properties.gender").equalTo("female").build();
        Condition nobody = builder.profileProperty("properties.gender").equalTo("nobody").build();
        Condition olderThan30 = builder.profileProperty("properties.age").greaterThan(30).build();
        check(failures, "profile/and(matchAll,gender=female)", Profile.class, and(matchAll(), female), List.of(P1, P3));
        check(failures, "profile/or(matchAll,gender=nobody)", Profile.class, or(matchAll(), nobody), ALL_PROFILES);
        check(failures, "profile/and(matchAll,matchAll)", Profile.class, and(matchAll(), matchAll()), ALL_PROFILES);
        check(failures, "profile/and(matchAll)", Profile.class, and(matchAll()), ALL_PROFILES);
        check(failures, "profile/not(matchAll)", Profile.class, not(matchAll()), List.of());
        check(failures, "profile/not(not(matchAll))", Profile.class, not(not(matchAll())), ALL_PROFILES);
        check(failures, "profile/and(matchAll,not(matchAll))", Profile.class, and(matchAll(), not(matchAll())), List.of());
        check(failures, "profile/or(not(matchAll),age>30)", Profile.class, or(not(matchAll()), olderThan30), List.of(P2));
        check(failures, "profile/not(and(matchAll,gender=female))", Profile.class, not(and(matchAll(), female)), List.of(P2));
        Condition viewEvents = builder.condition("eventTypeCondition").parameter("eventTypeId", "view").build();
        check(failures, "event/and(matchAll,eventType=view)", Event.class, and(matchAll(), viewEvents), List.of(E1, E2));
        check(failures, "session/not(matchAll)", Session.class, not(matchAll()), List.of());

        // Paging and sorting on top of matchAll
        List<String> sorted = ids(persistenceService.query(matchAll(), "properties.age:asc", Profile.class, 0, 10).getList());
        record("profile/matchAll sorted by age asc", sorted, (long) sorted.size(), sorted);
        if (!List.of(P3, P1, P2).equals(sorted)) {
            failures.add("profile/matchAll sorted by age asc: expected [" + P3 + ", " + P1 + ", " + P2 + "] but got " + sorted);
        }
        List<String> page = ids(persistenceService.query(matchAll(), "properties.age:asc", Profile.class, 1, 1).getList());
        record("profile/matchAll offset=1 size=1", page, (long) page.size(), page);
        if (!List.of(P1).equals(page)) {
            failures.add("profile/matchAll offset=1 size=1: expected [" + P1 + "] but got " + page);
        }

        // The other tenant sees only its own items
        executionContextManager.executeAsTenant(otherTenant.getItemId(), () -> {
            checkActual(failures, "otherTenant/profile/matchAll", Profile.class, matchAll(), List.of(OTHER_PROFILE));
            checkActual(failures, "otherTenant/session/matchAll", Session.class, matchAll(), List.of(OTHER_SESSION));
            checkActual(failures, "otherTenant/event/matchAll", Event.class, matchAll(), List.of(OTHER_EVENT));
        });

        writeReport();
        assertTrue("matchAllCondition results differ from the expected IDs on " + searchEngine + ":\n"
                + String.join("\n", failures), failures.isEmpty());
    }

    private void check(List<String> failures, String name, Class<? extends Item> clazz, Condition condition, List<String> expected) {
        Set<String> oracle = seeded.values().stream()
                .filter(item -> clazz.equals(item.getClass()))
                .filter(item -> persistenceService.testMatch(condition, item))
                .map(Item::getItemId)
                .collect(Collectors.toCollection(TreeSet::new));
        if (!oracle.equals(new TreeSet<>(expected))) {
            failures.add(name + ": in-memory evaluator returned " + oracle + " but the test expects " + new TreeSet<>(expected));
        }
        checkActual(failures, name, clazz, condition, expected);
    }

    private void checkActual(List<String> failures, String name, Class<? extends Item> clazz, Condition condition, List<String> expected) {
        Set<String> expectedSet = new TreeSet<>(expected);
        @SuppressWarnings("unchecked")
        List<String> actual = ids(persistenceService.query(condition, null, (Class<Item>) clazz));
        long count = persistenceService.queryCount(condition, Item.getItemType(clazz));
        Set<String> actualSet = new TreeSet<>(actual);
        record(name, actual, count, expectedSet);
        if (!expectedSet.equals(actualSet) || actual.size() != actualSet.size()) {
            failures.add(name + ": query expected " + expectedSet + " but got " + actual);
        }
        if (count != expectedSet.size()) {
            failures.add(name + ": queryCount expected " + expectedSet.size() + " but got " + count);
        }
    }

    private void record(String name, List<String> actual, Long count, java.util.Collection<String> expected) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ids", name.contains("sorted") || name.contains("offset") ? actual : new ArrayList<>(new TreeSet<>(actual)));
        entry.put("count", count);
        entry.put("expected", new ArrayList<>(expected));
        report.put(name, entry);
    }

    private void writeReport() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("engine", searchEngine);
        root.put("conditionType", "matchAllCondition");
        root.put("cases", report);
        Path dir = targetDir().resolve("parity");
        Files.createDirectories(dir);
        Path file = dir.resolve("matchall-" + searchEngine + ".json");
        Files.write(file, getObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(root).getBytes(StandardCharsets.UTF_8));
    }

    /** Finds {@code itests/target} from the Karaf home ({@code itests/target/exam/<id>}) of the test container. */
    private static Path targetDir() {
        String karafHome = System.getProperty("karaf.home");
        if (karafHome != null) {
            for (File dir = new File(karafHome).getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
                if ("target".equals(dir.getName())) {
                    return dir.toPath();
                }
            }
        }
        return Paths.get(System.getProperty("user.dir", "."), "target");
    }

    private static List<String> ids(List<? extends Item> items) {
        return items.stream().map(Item::getItemId).collect(Collectors.toList());
    }

    private static Profile profile(String id, String gender, int age) {
        Profile profile = new Profile(id);
        profile.setProperty("gender", gender);
        profile.setProperty("age", age);
        return profile;
    }

    private Condition matchAll() {
        return builder.matchAll().build();
    }

    private Condition and(Condition... conditions) {
        return compound("and", conditions);
    }

    private Condition or(Condition... conditions) {
        return compound("or", conditions);
    }

    private Condition compound(String operator, Condition... conditions) {
        Condition condition = new Condition(definitionsService.getConditionType("booleanCondition"));
        condition.setParameter("operator", operator);
        condition.setParameter("subConditions", List.of(conditions));
        return condition;
    }

    private Condition not(Condition subCondition) {
        Condition condition = new Condition(definitionsService.getConditionType("notCondition"));
        condition.setParameter("subCondition", subCondition);
        return condition;
    }
}
