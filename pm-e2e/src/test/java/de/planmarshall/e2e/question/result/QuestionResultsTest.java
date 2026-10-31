/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.e2e.question.result;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.question.driver.Fault;
import de.planmarshall.e2e.question.driver.QuestionDriver;
import de.planmarshall.e2e.question.result.Scale.Verdict;
import de.planmarshall.e2e.question.stub.QuestionStub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The evaluation at the revised scale, on recorded run records and stub logs written into a temporary directory: the
 * verdict of a group (every run passes, disagreement extends the group to 10), the sequential bounds of Q5, the
 * longest-first descent of R1 and R2, the canary detector, the changed R3 criterion and the accepted reference form of
 * Q4. No harness is run.
 */
@DisplayName("QuestionResults: verdicts at the revised scale")
class QuestionResultsTest {

    private static final String VERSION = "2.1.0";
    private static final String WORKER = "w-1";
    private static final String REFERENCE = "[1, 2, 6]";

    @TempDir
    Path directory;

    private int runs;

    /** One event of a stub log. */
    private static ObjectNode event(String name, String worker, int generation, long at) {
        var event = QuestionStub.JSON.createObjectNode();
        event.put("event", name);
        event.put("worker", worker);
        event.put("generation", generation);
        event.put("at", at);
        return event;
    }

    private static ObjectNode submitted(String answer, long at) {
        var event = event(RunReadings.SUBMITTED, WORKER, 1, at);
        event.putObject("args").put("answer", answer);
        return event;
    }

    /** Writes a run's stub log and returns its record. */
    private ObjectNode run(String item, Map<String, Object> fields, List<ObjectNode> events) throws IOException {
        var id = item + "-run-" + runs++;
        var stub = Files.createDirectories(directory.resolve("ws").resolve(id).resolve("stub"));
        var lines = new StringBuilder();
        var records = new StringBuilder();
        for (var event : events) {
            lines.append(QuestionStub.JSON.writeValueAsString(event)).append('\n');
            if (RunReadings.ASKED.equals(event.path("event").asText())) {
                records.append("{\"line\":\"asked\"}\n");
            }
        }
        Files.writeString(stub.resolve(RunView.EVENTS_FILE), lines, StandardCharsets.UTF_8);
        Files.writeString(stub.resolve(RunView.QUESTIONS_FILE), records, StandardCharsets.UTF_8);
        var run = QuestionStub.JSON.createObjectNode();
        run.put("item", item);
        run.put("invocation", "inv-1");
        run.put("host", "claude");
        run.put("model", "claude-haiku-4-5");
        run.put("form", "fresh");
        run.put("continuation", "hold");
        run.put("fixture", "f-1");
        run.put(QuestionResults.CLIENT_VERSION, VERSION);
        run.put(QuestionResults.PARALLEL, 1);
        run.put(RunView.STUB_DIR, stub.toString());
        fields.forEach((key, value) -> run.set(key, QuestionStub.JSON.valueToTree(value)));
        return run;
    }

    private ObjectNode q4Run(String answer) throws IOException {
        return run("Q4", Map.of("reference", REFERENCE, "reply", "option"),
                List.of(event(RunReadings.ASKED, WORKER, 1, 10), submitted(answer, 20)));
    }

    @Nested
    @DisplayName("the verdict of a group")
    class GroupVerdict {

        @ParameterizedTest(name = "{0} runs, {1} passing: {2}")
        @CsvSource({"3,3,PASS", "3,2,EXTEND", "3,0,FAIL", "10,9,FAIL", "10,10,PASS", "0,0,NONE"})
        @DisplayName("every run passes, a disagreeing group is extended to 10")
        void verdict(int runs, int passing, Verdict expected) {
            assertEquals(expected, Scale.verdict(runs, passing));
        }

        @Test
        @DisplayName("a count out of range is refused")
        void outOfRange() {
            assertThrows(IllegalArgumentException.class, () -> Scale.verdict(3, 4));
        }

        @Test
        @DisplayName("a group of 3 passing runs passes, stated with 3 runs and the client version")
        void threePassing() throws Exception {
            var records = List.<JsonNode>of(q4Run(REFERENCE), q4Run(REFERENCE), q4Run(REFERENCE));

            var result = QuestionResults.evaluate("Q4", records);

            var group = result.path("groups").get(0);
            assertAll(
                    () -> assertEquals("pass", group.path("verdict").asText()),
                    () -> assertEquals("pass, 3 runs, client " + VERSION,
                            group.path(QuestionResults.VERDICT_STATEMENT).asText()),
                    () -> assertEquals("pass", result.path("verdict").asText()),
                    () -> assertTrue(result.path(QuestionResults.VERDICT_STATEMENT).asText().contains("3 runs")));
        }

        @Test
        @DisplayName("a group of 2 passing runs and 1 failing run must be extended to 10")
        void disagreeing() throws Exception {
            var records = List.<JsonNode>of(q4Run(REFERENCE), q4Run(REFERENCE), q4Run("[6, 2, 1]"));

            var result = QuestionResults.evaluate("Q4", records);

            var group = result.path("groups").get(0);
            assertAll(
                    () -> assertEquals("extend to 10", group.path("verdict").asText()),
                    () -> assertEquals("extend to 10, 3 runs, 2 of 3 passed, client " + VERSION,
                            group.path(QuestionResults.VERDICT_STATEMENT).asText()),
                    () -> assertEquals("extend to 10", result.path("verdict").asText()),
                    () -> assertEquals(1, group.path(QuestionResults.NOT_FOLLOWING_REFERENCE_RUNS).size()));
        }

        @Test
        @DisplayName("a group of 3 failing runs fails")
        void allFailing() throws Exception {
            var records = List.<JsonNode>of(q4Run("x"), q4Run("y"), q4Run("z"));

            var result = QuestionResults.evaluate("Q4", records);

            assertEquals("fail", result.path("verdict").asText());
        }

        @Test
        @DisplayName("an item fails where a group fails, else extends where a group must be extended")
        void combine() {
            assertAll(
                    () -> assertEquals(Verdict.FAIL, QuestionResults.combine(List.of(Verdict.PASS, Verdict.FAIL,
                            Verdict.EXTEND))),
                    () -> assertEquals(Verdict.EXTEND, QuestionResults.combine(List.of(Verdict.PASS,
                            Verdict.EXTEND))),
                    () -> assertEquals(Verdict.PASS, QuestionResults.combine(List.of(Verdict.PASS, Verdict.NONE))),
                    () -> assertEquals(Verdict.NONE, QuestionResults.combine(List.of())));
        }

        @Test
        @DisplayName("a verdict without a recorded client version says so")
        void noVersion() {
            assertEquals("fail, 1 run, " + Scale.VERSION_NOT_RECORDED,
                    Scale.statement(Verdict.FAIL, 1, 0, List.of("")));
        }

        @Test
        @DisplayName("an unknown item is refused")
        void unknownItem() {
            assertThrows(IllegalArgumentException.class, () -> QuestionResults.evaluate("Q9", List.of()));
        }

        @Test
        @DisplayName("groups are kept apart by fixture")
        void groupedByFixture() throws Exception {
            var other = q4Run(REFERENCE);
            other.put("fixture", "f-2");

            var result = QuestionResults.evaluate("Q4", List.of(q4Run(REFERENCE), other));

            assertEquals(2, result.path("groups").size());
        }
    }

    @Nested
    @DisplayName("the item rules")
    class ItemRules {

        @Test
        @DisplayName("Q1: a run passes when each ask is accepted first or after one refusal and none is lost")
        void q1() throws Exception {
            var accepted = run("Q1", Map.of(), List.of(event("ask_refused", WORKER, 1, 5),
                    event(RunReadings.ASKED, WORKER, 1, 10)));
            var refusedTwice = run("Q1", Map.of(), List.of(event("ask_refused", WORKER, 1, 5),
                    event("ask_refused", WORKER, 1, 6), event(RunReadings.ASKED, WORKER, 1, 10)));
            var accepted2 = run("Q1", Map.of(), List.of(event(RunReadings.ASKED, WORKER, 1, 10)));

            var group = QuestionResults.evaluate("Q1", List.of(accepted, refusedTwice, accepted2)).path("groups")
                    .get(0);

            assertAll(
                    () -> assertEquals(2, group.path(QuestionResults.PASSING_RUNS).asInt()),
                    () -> assertEquals(1, group.path("accepted_after_one_refusal").asInt()),
                    () -> assertEquals(0, group.path("asks_lost").asInt()));
        }

        @Test
        @DisplayName("Q4: an answer stated as f(x) == <reference> follows the reference")
        void q4ReferenceForm() throws Exception {
            var records = List.<JsonNode>of(q4Run("f(7) == " + REFERENCE), q4Run(REFERENCE), q4Run(REFERENCE));

            var result = QuestionResults.evaluate("Q4", records);

            var group = result.path("groups").get(0);
            assertAll(
                    () -> assertEquals("pass", group.path("verdict").asText()),
                    () -> assertEquals(1, group.path("reference_form_runs").asInt()),
                    () -> assertTrue(RunReadings.followsReference(REFERENCE, "F(7)  ==  [1,  2, 6]")),
                    () -> assertFalse(RunReadings.followsReference(REFERENCE, "f(7) == [6, 2, 1]")),
                    () -> assertFalse(RunReadings.followsReference(REFERENCE, "x = " + REFERENCE)),
                    () -> assertFalse(RunReadings.followsReference("", REFERENCE)));
        }

        private ObjectNode q5Run(int asks, int bound) throws IOException {
            var events = new ArrayList<ObjectNode>();
            for (var index = 0; index < asks; index++) {
                var asked = event(RunReadings.ASKED, WORKER, 1, 10 + index);
                asked.putArray("question_texts").add("question " + index);
                events.add(asked);
            }
            return run("Q5", Map.of("bound", false, QuestionResults.QUESTION_BOUND, bound), events);
        }

        @Test
        @DisplayName("Q5: without a run that reached bound 3, bounds 4 and 5 are not scheduled")
        void q5BoundNotReached() throws Exception {
            var records = List.<JsonNode>of(q5Run(2, 3), q5Run(2, 3), q5Run(2, 3));

            var result = QuestionResults.evaluate("Q5", records);

            assertAll(
                    () -> assertEquals(List.of(3), values(result.path(QuestionResults.Q5_BOUNDS))),
                    () -> assertEquals("pass", result.path("verdict").asText()),
                    () -> assertEquals(0, result.path("groups").get(0).path("bound_reached").asInt()));
        }

        @Test
        @DisplayName("Q5: a run that reached bound 3 schedules bound 4")
        void q5BoundReached() throws Exception {
            var records = List.<JsonNode>of(q5Run(3, 3), q5Run(2, 3), q5Run(2, 3));

            var result = QuestionResults.evaluate("Q5", records);

            assertEquals(List.of(3, 4), values(result.path(QuestionResults.Q5_BOUNDS)));
        }

        @Test
        @DisplayName("Q5: the bounds run sequentially up to 5")
        void q5Sequence() {
            assertAll(
                    () -> assertEquals(List.of(3), Scale.q5Bounds(Set.of())),
                    () -> assertEquals(List.of(3, 4), Scale.q5Bounds(Set.of(3))),
                    () -> assertEquals(List.of(3, 4, 5), Scale.q5Bounds(Set.of(3, 4, 5))),
                    () -> assertEquals(List.of(3), Scale.q5Bounds(Set.of(4))),
                    () -> assertEquals(Optional.empty(), Scale.nextQ5Bound(5, true)));
        }

        private ObjectNode r1Run(long duration, boolean continued) throws IOException {
            var acknowledged = event(RunReadings.ACKNOWLEDGED, WORKER, 1, 30);
            acknowledged.put("kind", "answer");
            var submit = event(RunReadings.SUBMITTED, WORKER, 1, 40);
            submit.putObject("args").put("canary", continued ? "canary-1" : "none");
            return run("R1", Map.of("duration_s", duration, "canary", "canary-1"),
                    List.of(event(RunReadings.ASKED, WORKER, 1, 10), acknowledged, submit));
        }

        @Test
        @DisplayName("R1: durations run longest first and descend only on a failure")
        void r1Descent() throws Exception {
            var failing = List.<JsonNode>of(r1Run(3600, false), r1Run(3600, false), r1Run(3600, false));
            var passing = List.<JsonNode>of(r1Run(3600, true), r1Run(3600, true), r1Run(3600, true));

            var afterFailure = QuestionResults.evaluate("R1", failing);
            var afterPass = QuestionResults.evaluate("R1", passing);

            var key = "claude claude-haiku-4-5 fresh";
            assertAll(
                    () -> assertEquals(1800, afterFailure.path(QuestionResults.DESCENT).path(key).path("next")
                            .asLong()),
                    () -> assertEquals("none", afterPass.path(QuestionResults.DESCENT).path(key).path("next")
                            .asText()),
                    () -> assertEquals(3600, afterPass.path("max_hold_seconds").path("claude claude-haiku-4-5")
                            .asLong()),
                    () -> assertEquals(Optional.of(900L), Scale.nextDuration(1800, false)),
                    () -> assertEquals(Optional.empty(), Scale.nextDuration(300, false)),
                    () -> assertThrows(IllegalArgumentException.class, () -> Scale.nextDuration(42, false)));
        }

        @Test
        @DisplayName("R3: a call cut the worker survives by waiting again is no lost continuation and passes")
        void r3CutSurvived() throws Exception {
            var cut = event(QuestionDriver.FAULT_INJECTED, WORKER, 1, 20);
            cut.put("fault", Fault.CALL_CUT.name());
            cut.put("cut_by", "host");
            var reWait = event(QuestionDriver.CALL, WORKER, 1, 25);
            reWait.put("tool", "pm_wait");
            var acknowledged = event(RunReadings.ACKNOWLEDGED, WORKER, 1, 40);
            acknowledged.put("kind", "answer");
            var survived = run("R3", Map.of("fault", Fault.CALL_CUT.name()), List.of(
                    event(RunReadings.ASKED, WORKER, 1, 10), cut, reWait,
                    event(RunReadings.ANSWER_DELIVERED, WORKER, 1, 30), acknowledged));
            var undetected = run("R3", Map.of("fault", Fault.CALL_CUT.name()),
                    List.of(event(RunReadings.ASKED, WORKER, 1, 10)));

            var group = QuestionResults.evaluate("R3", List.of(survived, undetected)).path("groups").get(0);

            assertAll(
                    () -> assertEquals(1, group.path(QuestionResults.PASSING_RUNS).asInt()),
                    () -> assertEquals(1, group.path("passed_by_surviving_cut").asInt()),
                    () -> assertEquals(1, group.path(QuestionResults.CUT_SURVIVED).asInt()),
                    () -> assertEquals(1, group.path(QuestionResults.CUT_BY_HOST).asInt()));
        }

        @Test
        @DisplayName("R4 has no verdict but reports its figures")
        void r4() throws Exception {
            var record = run("R4", Map.of("size", "large", "reference", "cache"),
                    List.of(event(RunReadings.ASKED, WORKER, 1, 10), submitted("cache", 20)));

            var result = QuestionResults.evaluate("R4", List.of(record));

            assertAll(
                    () -> assertEquals("none", result.path("verdict").asText()),
                    () -> assertEquals(1, result.path("groups").get(0).path("answer_follows_reference").asInt()));
        }
    }

    @Nested
    @DisplayName("the canary detector")
    class Canary {

        private JsonNode output(long at, String line) {
            var node = QuestionStub.JSON.createObjectNode();
            node.put("at", at);
            node.put("line", line);
            return node;
        }

        private String toolUse(String name) {
            return "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"" + name
                    + "\",\"input\":{\"file_path\":\"/w/canary.txt\"}}]}}";
        }

        @Test
        @DisplayName("only the worker's own tool calls after the ask count")
        void ownToolCallsOnly() {
            var read = List.of(output(20, toolUse("Read")));
            var stubCall = List.of(output(20, toolUse("mcp__plan-marshall__pm_do")));
            var beforeAsk = List.of(output(5, toolUse("Read")));
            var prose = List.of(output(20, "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\","
                    + "\"text\":\"I read canary.txt before\"}]}}"));
            var openCode = List.of(output(20, "{\"type\":\"tool_use\",\"part\":{\"type\":\"tool\",\"tool\":\"read\","
                    + "\"state\":{\"input\":{\"path\":\"canary.txt\"}}}}"));
            var openCodeStub = List.of(output(20, "{\"type\":\"tool_use\",\"part\":{\"type\":\"tool\","
                    + "\"tool\":\"execute\",\"state\":{\"input\":{\"code\":\"pm_do('canary.txt')\"}}}}"));

            assertAll(
                    () -> assertTrue(RunReadings.rereadsCanary(read, 10)),
                    () -> assertFalse(RunReadings.rereadsCanary(stubCall, 10)),
                    () -> assertFalse(RunReadings.rereadsCanary(beforeAsk, 10)),
                    () -> assertFalse(RunReadings.rereadsCanary(prose, 10)),
                    () -> assertTrue(RunReadings.rereadsCanary(openCode, 10)),
                    () -> assertFalse(RunReadings.rereadsCanary(openCodeStub, 10)),
                    () -> assertFalse(RunReadings.readsFile("not json canary.txt", "canary.txt")));
        }
    }

    @Nested
    @DisplayName("counts and bookkeeping")
    class Bookkeeping {

        @Test
        @DisplayName("a run without its stub log counts as evidence_missing, never as no_ask")
        void evidenceMissing() throws Exception {
            var missing = q4Run(REFERENCE);
            missing.put(RunView.STUB_DIR, directory.resolve("gone").resolve("stub").toString());

            var result = QuestionResults.evaluate("Q4", List.of(missing));

            var group = result.path("groups").get(0);
            assertAll(
                    () -> assertEquals(1, group.path(QuestionResults.EVIDENCE_MISSING).asInt()),
                    () -> assertEquals(0, group.path("no_ask").asInt()),
                    () -> assertEquals(1, result.path(QuestionResults.EVIDENCE_MISSING).asInt()),
                    () -> assertTrue(result.has(QuestionResults.VERDICT_EVIDENCE)));
        }

        @Test
        @DisplayName("an invocation with fewer records than planned is flagged incomplete with a limit")
        void incomplete() throws Exception {
            var record = q4Run(REFERENCE);
            var invocation = QuestionStub.JSON.createObjectNode();
            invocation.put("invocation", "inv-1");
            invocation.put("item", "Q4");
            invocation.put(QuestionResults.PLANNED_RUNS, 3);

            var result = QuestionResults.result("Q4", List.of(record), List.of(invocation), () -> 0);

            assertAll(
                    () -> assertTrue(result.path("invocations").get(0).path("incomplete").asBoolean()),
                    () -> assertEquals(VERSION, result.path("client_versions").get(0).asText()),
                    () -> assertEquals(1, result.path("parallel_levels").get(0).asInt()),
                    () -> assertTrue(result.path(ResultLimits.LIMITS).toString().contains("planned runs")));
        }

        @Test
        @DisplayName("run and invocation records are appended and read back per item")
        void records() throws Exception {
            var record = q4Run(REFERENCE);
            var invocation = QuestionStub.JSON.createObjectNode();
            invocation.put("invocation", "inv-1");
            invocation.put("item", "Q4");

            QuestionResults.recordRun(directory, record);
            QuestionResults.recordInvocation(directory, invocation);
            QuestionResults.recordInvocation(directory, invocation.deepCopy().put("planned_runs", 3));

            assertAll(
                    () -> assertEquals(1, QuestionResults.runs(directory, "Q4").size()),
                    () -> assertEquals(0, QuestionResults.runs(directory, "Q1").size()),
                    () -> assertEquals(3, QuestionResults.invocations(directory, "Q4").getFirst()
                            .path("planned_runs").asInt()));
        }
    }

    private static List<Integer> values(JsonNode array) {
        var values = new ArrayList<Integer>();
        array.forEach(value -> values.add(value.asInt()));
        return values;
    }
}
