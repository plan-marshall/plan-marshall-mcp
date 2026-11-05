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

    /** Writes a run's stub log and one worker output file of the given lines, each arriving at 20. */
    private ObjectNode run(String item, Map<String, Object> fields, List<ObjectNode> events, List<String> outputs)
            throws IOException {
        var run = run(item, fields, events);
        var file = Path.of(run.path(RunView.STUB_DIR).asText()).resolveSibling("w-1.jsonl");
        var lines = new StringBuilder();
        for (var output : outputs) {
            var stamped = QuestionStub.JSON.createObjectNode();
            stamped.put("at", 20);
            stamped.put("line", output);
            lines.append(QuestionStub.JSON.writeValueAsString(stamped)).append('\n');
        }
        Files.writeString(file, lines, StandardCharsets.UTF_8);
        run.putArray("workers").addObject().put("worker", WORKER).put("generation", 1).put("output", file.toString());
        return run;
    }

    private static ObjectNode event(String name, String worker, int generation, long at, String field,
            String value) {
        var event = event(name, worker, generation, at);
        event.put(field, value);
        return event;
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
            var runRecord = run("R4", Map.of("size", "large", "reference", "cache"),
                    List.of(event(RunReadings.ASKED, WORKER, 1, 10), submitted("cache", 20)));

            var result = QuestionResults.evaluate("R4", List.of(runRecord));

            assertAll(
                    () -> assertEquals("none", result.path("verdict").asText()),
                    () -> assertEquals(1, result.path("groups").get(0).path("answer_follows_reference").asInt()));
        }
    }

    @Nested
    @DisplayName("the item rules read from the stub log and the worker output")
    class ItemChecks {

        private static final String TOOL_USE = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":"
                + "\"tool_use\",\"name\":\"AskUserQuestion\",\"input\":{}}]}}";

        private ObjectNode closed(String task, String outcome, long at) {
            return event("closed", WORKER, 1, at, "task", task).put("outcome", outcome);
        }

        @Test
        @DisplayName("Q2: a fixture run asks and guesses nothing, a control does not ask, and no host tool or prose")
        void q2() throws Exception {
            var asking = run("Q2", Map.of(), List.of(event(RunReadings.ASKED, WORKER, 1, 10)));
            var guessing = run("Q2", Map.of(), List.of(submitted("guess", 20)));
            var hostTool = run("Q2", Map.of(), List.of(event(RunReadings.ASKED, WORKER, 1, 10)),
                    List.of(TOOL_USE, "{\"type\":\"user\",\"is_error\":true}"));
            var hanging = run("Q2", Map.of(QuestionResults.TIMED_OUT, true), List.of(), List.of(TOOL_USE));
            var prose = run("Q2", Map.of(), List.of(event(RunReadings.ASKED, WORKER, 1, 10)),
                    List.of("{\"type\":\"result\",\"result\":\"Which one shall I take?\"}"));
            var quietControl = run("Q2", Map.of("control", true), List.of());
            var askingControl = run("Q2", Map.of("control", true), List.of(event(RunReadings.ASKED, WORKER, 1, 10)));

            var group = QuestionResults.evaluate("Q2", List.of(asking, guessing, hostTool, hanging, prose,
                    quietControl, askingControl)).path("groups").get(0);

            assertAll(
                    () -> assertEquals(2, group.path(QuestionResults.PASSING_RUNS).asInt()),
                    () -> assertEquals(2, group.path("host_question_tool_calls").asInt()),
                    () -> assertEquals(1, group.path("host_question_tool_behaviour").path("fails").asInt()),
                    () -> assertEquals(1, group.path("host_question_tool_behaviour").path("hangs").asInt()),
                    () -> assertEquals(1, group.path("prose_questions").asInt()),
                    () -> assertEquals(5, group.path("q1_fixture_runs").asInt()),
                    () -> assertEquals(1, group.path("guessed_decisions").asInt()),
                    () -> assertEquals(2, group.path("control_runs").asInt()),
                    () -> assertEquals(1, group.path("control_asks").asInt()));
        }

        @Test
        @DisplayName("Q3: a warm worker stops at its next pm_wait with its worktree unchanged")
        void q3Warm() throws Exception {
            var warm = run("Q3", Map.of("form", "warm"), List.of(
                    event("worktree_digest", WORKER, 1, 5, "digest", "a"),
                    event(RunReadings.ASKED, WORKER, 1, 10),
                    event("wait_started", WORKER, 1, 15),
                    event("worktree_digest", WORKER, 1, 16, "digest", "a")));

            var group = QuestionResults.evaluate("Q3", List.of(warm)).path("groups").get(0);

            assertAll(
                    () -> assertEquals(1, group.path(QuestionResults.PASSING_RUNS).asInt()),
                    () -> assertEquals(1, group.path("stopped").asInt()),
                    () -> assertEquals(1, group.path("worktree_unchanged").asInt()),
                    () -> assertEquals(1, group.path("asked_then_pm_wait").asInt()),
                    () -> assertEquals(5, group.path("per_run").get(0)
                            .path(QuestionResults.ASK_TO_NEXT_PM_WAIT_MS).asLong()),
                    () -> assertEquals("pass", group.path(QuestionResults.VERDICT_BY_NEXT_PM_WAIT).asText()));
        }

        @Test
        @DisplayName("Q3: a fresh worker that exits with a changed worktree fails, and a run without an ask fails")
        void q3Fresh() throws Exception {
            var changed = run("Q3", Map.of(), List.of(
                    event("worktree_digest", WORKER, 1, 5, "digest", "a"),
                    event(RunReadings.ASKED, WORKER, 1, 10),
                    event(RunReadings.WORKER_EXITED, WORKER, 1, 30),
                    event("worktree_digest", WORKER, 1, 31, "digest", "b"),
                    event("answer_sent", "operator", 0, 50)));
            var silent = run("Q3", Map.of(), List.of());

            var group = QuestionResults.evaluate("Q3", List.of(changed, silent)).path("groups").get(0);

            var perRun = group.path("per_run");
            assertAll(
                    () -> assertEquals(0, group.path(QuestionResults.PASSING_RUNS).asInt()),
                    () -> assertEquals(1, group.path("stopped").asInt()),
                    () -> assertEquals(0, group.path("worktree_unchanged").asInt()),
                    () -> assertEquals(1, group.path("exits_observed").asInt()),
                    () -> assertEquals(1, group.path("exits_before_answer").asInt()),
                    () -> assertEquals(20, perRun.get(0).path(QuestionResults.ASK_TO_EXIT_MS).asLong()),
                    () -> assertTrue(perRun.get(0).path(QuestionResults.ASK_TO_NEXT_PM_WAIT_MS).isNull()),
                    () -> assertTrue(perRun.get(1).path(QuestionResults.ASK_TO_EXIT_MS).isNull()));
        }

        @Test
        @DisplayName("Q6: votes valid within the re-offer budget, the decision applied, no mix-up")
        void q6() throws Exception {
            var valid = run("Q6", Map.of("reference", "B"), List.of(
                    event("vote_refused", WORKER, 1, 5, "task", "vote-2"),
                    event("vote_refused", WORKER, 1, 6, "task", "vote-2"),
                    closed("vote-1", RunReadings.SUBMITTED, 10),
                    closed("vote-2", RunReadings.SUBMITTED, 11),
                    closed("plan-1", RunReadings.SUBMITTED, 12),
                    event("adjudicated", WORKER, 1, 13, "outcome", "decided"),
                    event("consulted", WORKER, 1, 14, "question", "Which?"),
                    event("consultation_delivered", WORKER, 1, 15),
                    submitted("B", 20)));
            var mixed = run("Q6", Map.of("reference", "B"), List.of(
                    closed("vote-1", "lost", 10),
                    event("link_refused", WORKER, 1, 11, "state", "consulting"),
                    event("consulted", WORKER, 1, 12, "question", "Which?"),
                    asked("which?", 13),
                    submitted("A", 20)));

            var group = QuestionResults.evaluate("Q6", List.of(valid, mixed)).path("groups").get(0);

            assertAll(
                    () -> assertEquals(1, group.path(QuestionResults.PASSING_RUNS).asInt()),
                    () -> assertEquals(3, group.path("votes").asInt()),
                    () -> assertEquals(2, group.path("valid_votes").asInt()),
                    () -> assertEquals(1, group.path("decided_by_quorum").asInt()),
                    () -> assertEquals(3, group.path("consultation_question_mixups").asInt()),
                    () -> assertEquals(1, group.path(QuestionResults.NOT_FOLLOWING_REFERENCE_RUNS).size()));
        }

        @Test
        @DisplayName("Q6: a vote run without a vote and a consult run that never consulted do not pass")
        void q6NotExercised() throws Exception {
            var consulting = run("Q6", Map.of("reference", "B", "kind", "consult"), List.of(
                    event("consulted", WORKER, 1, 10, "question", "Which?"),
                    event("consultation_answered", WORKER, 1, 11),
                    event("consultation_delivered", WORKER, 1, 12),
                    submitted("B", 20)));
            var neverConsulted = run("Q6", Map.of("reference", "B", "kind", "consult"),
                    List.of(submitted("B", 20)));
            var noVote = run("Q6", Map.of("reference", "B", "kind", "vote"), List.of(
                    event("adjudicated", WORKER, 1, 13, "outcome", "kind_ask"),
                    submitted("B", 20)));

            var groups = QuestionResults.evaluate("Q6", List.of(consulting, neverConsulted, noVote)).path("groups");

            assertAll(
                    () -> assertEquals(1, sum(groups, QuestionResults.PASSING_RUNS)),
                    () -> assertEquals(3, sum(groups, "runs")),
                    () -> assertEquals(0, sum(groups, "votes")),
                    () -> assertEquals(0, sum(groups, "consultation_question_mixups")),
                    () -> assertTrue(QuestionResults.q6Exercised(new RunView(consulting, List.of(
                            event("consulted", WORKER, 1, 10)), List.of(), List.of()), 0)),
                    () -> assertFalse(QuestionResults.q6Exercised(new RunView(neverConsulted, List.of(), List.of(),
                            List.of()), 0)),
                    () -> assertFalse(QuestionResults.q6Exercised(new RunView(noVote, List.of(), List.of(),
                            List.of()), 0)),
                    () -> assertTrue(QuestionResults.q6Exercised(new RunView(noVote, List.of(), List.of(),
                            List.of()), 2)));
        }

        private int sum(JsonNode groups, String field) {
            var total = 0;
            for (var group : groups) {
                total += group.path(field).asInt();
            }
            return total;
        }

        @Test
        @DisplayName("R1: a duration whose only run the usage limit refused runs again instead of descending")
        void r1NotRunRepeats() throws Exception {
            var refused = Map.<String, Object>of("duration_s", 3600, "canary", "canary-1", QuestionDriver.QUOTA_ERROR,
                    true, QuestionDriver.QUOTA_RULE, QuestionDriver.RATE_LIMIT_EVENT, RunView.NOT_RUN, true);
            var notRun = run("R1", refused, List.of());
            var failed = run("R1", Map.of("duration_s", 3600, "canary", "canary-1"), List.of());
            var notRunShorter = run("R1", Map.of("duration_s", 1800, "canary", "canary-1", QuestionDriver.QUOTA_ERROR,
                    true, QuestionDriver.QUOTA_RULE, QuestionDriver.RATE_LIMIT_EVENT, RunView.NOT_RUN, true), List.of());

            var longest = QuestionResults.evaluate("R1", List.of(notRun));
            var afterFailure = QuestionResults.evaluate("R1", List.of(failed, notRunShorter));

            var key = "claude claude-haiku-4-5 fresh";
            assertAll(
                    () -> assertEquals("none", longest.path(QuestionResults.DESCENT).path(key)
                            .path("shortest_verdict").asText()),
                    () -> assertEquals(3600, longest.path(QuestionResults.DESCENT).path(key).path("next").asLong()),
                    () -> assertEquals(1800, afterFailure.path(QuestionResults.DESCENT).path(key).path("next")
                            .asLong()));
        }

        private ObjectNode asked(String question, long at) {
            var asked = event(RunReadings.ASKED, WORKER, 1, at);
            asked.putArray("question_texts").add(question);
            asked.put("decision", "d1");
            return asked;
        }

        private List<ObjectNode> q7Events(boolean leaks) {
            var events = new ArrayList<ObjectNode>();
            events.add(asked("Which?", 10));
            if (leaks) {
                events.add(event(QuestionDriver.CONNECTED, "w-2", 1, 12));
                events.add(event("wait_started", "w-2", 1, 13));
            }
            events.add(event(QuestionDriver.OPEN_TIME_STARTED, "", 0, 15));
            if (leaks) {
                events.add(event("wait_end", "w-2", 1, 18));
            }
            events.add(event(RunReadings.WORKER_EXITED, "w-warm", 1, 20));
            events.add(event("stub_restart", "", 0, 25));
            events.add(event(QuestionResults.CONTINUED, "w-3", 1, 30, "task", "t-c").put("decision", "d1"));
            events.add(event(QuestionResults.OFFERED, "w-3", 1, 32, "task", "t-c"));
            events.add(event(RunReadings.ACKNOWLEDGED, "w-3", 1, 33, "task", "t-c"));
            if (leaks) {
                events.add(event(QuestionResults.OFFERED, "w-4", 1, 34, "task", "t-c"));
                events.add(event("released", "w-4", 1, 35, "task", "t-c").put("reason", "fenced"));
                events.add(event(QuestionResults.OFFERED, "w-5", 1, 36, "task", "t1-x"));
            }
            events.add(event("answer_recorded", "", 0, 40));
            return events;
        }

        @Test
        @DisplayName("Q7: no slot held while open, the warm worker recycled, the stub restarted, one delivery")
        void q7() throws Exception {
            var fields = Map.<String, Object>of("warm_worker", "w-warm", "task_id", "t1");
            var clean = run("Q7", Map.of("warm_worker", "w-warm", "task_id", "t1",
                    QuestionDriver.ASKER_ENDED_BY_DRIVER, true), q7Events(false));
            var leaking = run("Q7", fields, q7Events(true));
            var unanswered = run("Q7", fields, List.of(asked("Which?", 10)));

            var group = QuestionResults.evaluate("Q7", List.of(clean, leaking, unanswered)).path("groups").get(0);

            var leaked = group.path(QuestionResults.Q7_RUNS).get(1);
            assertAll(
                    () -> assertEquals(1, group.path(QuestionResults.PASSING_RUNS).asInt()),
                    () -> assertEquals(2, group.path(QuestionResults.LEASES_WHILE_OPEN).asInt()),
                    () -> assertEquals(2, group.path("warm_worker_recycled").asInt()),
                    () -> assertEquals(2, group.path("stub_restarted_while_open").asInt()),
                    () -> assertEquals(1, group.path("answer_delivered_exactly_once").asInt()),
                    () -> assertEquals(1, group.path("open_time_unavailable").asInt()),
                    () -> assertEquals(1, group.path(QuestionResults.HELD_WAIT_GENERATIONS).asLong()),
                    () -> assertEquals(1, group.path(QuestionResults.FENCED_UNACKNOWLEDGED_OFFER_RUNS).asInt()),
                    () -> assertEquals(1, group.path(QuestionDriver.ASKER_ENDED_BY_DRIVER).asInt()),
                    () -> assertEquals(1, leaked.path("offers_while_open").asLong()),
                    () -> assertEquals(1, leaked.path(QuestionResults.WORKERS_CONNECTED_WHILE_OPEN).asLong()),
                    () -> assertEquals(3, leaked.path(QuestionResults.HELD_WAIT_MS).asLong()),
                    () -> assertTrue(leaked.path(QuestionResults.OFFER_COUNTED_TWICE_FENCED).asBoolean()),
                    () -> assertEquals(QuestionResults.UNAVAILABLE, group.path(QuestionResults.Q7_RUNS).get(2)
                            .path(QuestionResults.HELD_WAIT_MS).asText()));
        }

        @Test
        @DisplayName("Q7: a group without any open time reports its held figures as unavailable")
        void q7Unavailable() throws Exception {
            var unanswered = run("Q7", Map.of("warm_worker", "w-warm"), List.of(asked("Which?", 10)));

            var group = QuestionResults.evaluate("Q7", List.of(unanswered)).path("groups").get(0);

            assertAll(
                    () -> assertEquals(QuestionResults.UNAVAILABLE, group.path(QuestionResults.HELD_WAIT_MS).asText()),
                    () -> assertEquals(QuestionResults.UNAVAILABLE,
                            group.path(QuestionResults.WORKERS_CONNECTED_WHILE_OPEN).asText()));
        }

        private ObjectNode resumed(String name, String worker, int generation, long at) {
            var event = event(name, worker, generation, at);
            event.put("resumed", true);
            return event;
        }

        @Test
        @DisplayName("R2: a positive run continues from the session, a negative resume must not hang")
        void r2() throws Exception {
            var acknowledged = event(RunReadings.ACKNOWLEDGED, WORKER, 2, 35, "kind", "answer");
            acknowledged.put("after_ms", 500);
            var canary = event(RunReadings.SUBMITTED, WORKER, 2, 40);
            canary.putObject("args").put("canary", "canary-1");
            var fields = Map.<String, Object>of("age_s", 3600, "canary", "canary-1", "restart", true, "negative",
                    false);
            var continued = run("R2", fields, List.of(event(RunReadings.ASKED, WORKER, 1, 10),
                    resumed(RunReadings.ANSWER_DELIVERED, WORKER, 2, 30), acknowledged, canary));
            var lost = run("R2", Map.of("age_s", 3600, "canary", "canary-1", "negative", false),
                    List.of(event(RunReadings.ASKED, WORKER, 1, 10)));
            var failedAtOnce = run("R2", Map.of("age_s", 3600, "negative", true), List.of(
                    resumed(RunReadings.WORKER_STARTED, WORKER, 2, 10),
                    event(RunReadings.WORKER_EXITED, WORKER, 2, 20).put("exit", 1)));
            var hung = run("R2", Map.of("age_s", 3600, "negative", true), List.of());

            var result = QuestionResults.evaluate("R2", List.of(continued, lost, failedAtOnce, hung));

            var negative = group(result, "negative=true");
            var positive = group(result, "negative=false");
            assertAll(
                    () -> assertEquals(1, positive.path(QuestionResults.PASSING_RUNS).asInt()),
                    () -> assertEquals(500, positive.path("ack_after_ms_median").asLong()),
                    () -> assertEquals(1, positive.path(QuestionResults.RESTART_BREAKDOWN).path("true")
                            .path("continued_from_session").asInt()),
                    () -> assertEquals(1, positive.path(QuestionResults.RESTART_BREAKDOWN).path("false")
                            .path("runs").asInt()),
                    () -> assertEquals(1, negative.path("negative_outcomes").path(RunReadings.FAILED_AT_ONCE)
                            .asInt()),
                    () -> assertEquals(1, negative.path("hung_resumes").asInt()),
                    () -> assertEquals(1, negative.path(QuestionResults.PASSING_RUNS).asInt()),
                    () -> assertTrue(result.has("max_age_seconds")),
                    () -> assertEquals(1, result.path(QuestionResults.DESCENT).size()));
        }

        private JsonNode group(JsonNode result, String part) {
            for (var group : result.path("groups")) {
                if (group.path("group").asText().contains(part)) {
                    return group;
                }
            }
            throw new AssertionError("no group with " + part);
        }

        @Test
        @DisplayName("R3: a fault detected within the deadline and continued once passes; early or undetected fails")
        void r3Detection() throws Exception {
            var fault = Map.<String, Object>of("fault", Fault.KILL.name());
            var detected = event(QuestionDriver.LOST_DETECTED, WORKER, 1, 100, "by", "silence");
            detected.put("after_ms", 20_000);
            var withinDeadline = run("R3", fault, List.of(event(RunReadings.ASKED, WORKER, 1, 10),
                    event(QuestionDriver.FAULT_INJECTED, WORKER, 1, 50),
                    event(RunReadings.ANSWER_DELIVERED, WORKER, 1, 60),
                    event(RunReadings.WORKER_EXITED, WORKER, 1, 90), detected,
                    event(QuestionResults.CONTINUED, "w-2", 1, 110)));
            var tooEarly = event(QuestionDriver.LOST_DETECTED, WORKER, 1, 100, "by", "ack");
            tooEarly.put("after_ms", 5_000);
            var early = run("R3", fault, List.of(event(RunReadings.ASKED, WORKER, 1, 10), tooEarly));
            var undetected = run("R3", fault, List.of(event(RunReadings.ASKED, WORKER, 1, 10),
                    event(QuestionDriver.FAULT_INJECTED, WORKER, 1, 50)));
            var nothing = run("R3", fault, List.of());

            var group = QuestionResults.evaluate("R3", List.of(withinDeadline, early, undetected, nothing))
                    .path("groups").get(0);

            var exits = group.path(QuestionResults.EXIT_OBSERVATION);
            assertAll(
                    () -> assertEquals(1, group.path(QuestionResults.PASSING_RUNS).asInt()),
                    () -> assertEquals(1, group.path("detected_within_deadline").asInt()),
                    () -> assertEquals(1, group.path("detected_early").asInt()),
                    () -> assertEquals(1, group.path("continued_exactly_once").asInt()),
                    () -> assertEquals(1, exits.path("exits_observed").asInt()),
                    () -> assertEquals(1, exits.path("exits_before_detection").asInt()),
                    () -> assertEquals("silence", exits.path("runs").get(0).path("detection_by").asText()),
                    () -> assertTrue(exits.path("runs").get(2).path("detection_by").isNull()),
                    () -> assertTrue(exits.path("runs").get(3).path("worker").isNull()));
        }

        @Test
        @DisplayName("the counts keep quota errors, failed setups and continuation exits apart and add verdicts")
        void counts() throws Exception {
            var passing = run("Q1", Map.of(), List.of(event(RunReadings.ASKED, WORKER, 1, 10)));
            var quota = run("Q1", Map.of(QuestionDriver.QUOTA_ERROR, true, QuestionDriver.QUOTA_RULE,
                    QuestionDriver.HARNESS_ERROR_OUTPUT), List.of());
            var exited = run("Q1", Map.of(QuestionDriver.CONTINUATION_EARLY_EXIT, true), List.of());
            var timedOut = run("Q1", Map.of(QuestionResults.TIMED_OUT, true), List.of(
                    event(RunReadings.ASKED, WORKER, 1, 10), event(QuestionResults.CONTINUED, WORKER, 2, 20),
                    submitted("x", 30)));
            var setup = run("Q1", Map.of(RunView.SETUP_FAILED, true), List.of());

            var result = QuestionResults.evaluate("Q1", List.of(passing, quota, exited, timedOut, setup));

            var group = result.path("groups").get(0);
            assertAll(
                    () -> assertEquals(5, group.path("runs").asInt()),
                    () -> assertEquals(1, group.path(QuestionDriver.QUOTA_ERROR).asInt()),
                    () -> assertEquals(1, group.path(RunView.SETUP_FAILED).asInt()),
                    () -> assertEquals(1, group.path(QuestionDriver.CONTINUATION_EARLY_EXIT).asInt()),
                    () -> assertEquals(1, group.path(QuestionResults.TIMED_OUT).asInt()),
                    () -> assertEquals(1, group.path(QuestionResults.TIMED_OUT_RUNS).size()),
                    () -> assertEquals(1, group.path(QuestionResults.CONTINUED).asInt()),
                    () -> assertEquals(4, group.path("judged_runs").asInt()),
                    () -> assertTrue(group.has(QuestionResults.VERDICT_WITHOUT_QUOTA_ERROR)),
                    () -> assertTrue(result.has(QuestionResults.VERDICT_WITHOUT_QUOTA_ERROR)));
        }

        @Test
        @DisplayName("a run the usage limit refused is not run: in no count, no figure and no verdict")
        void notRun() throws Exception {
            var passing = run("Q1", Map.of(), List.of(event(RunReadings.ASKED, WORKER, 1, 10)));
            var refused = run("Q1", Map.of(QuestionDriver.QUOTA_ERROR, true, QuestionDriver.QUOTA_RULE,
                    QuestionDriver.RATE_LIMIT_EVENT, RunView.NOT_RUN, true, QuestionDriver.RESETS_AT,
                    "2026-11-01T08:00:00Z"), List.of());

            var result = QuestionResults.evaluate("Q1", List.of(passing, refused));

            var group = result.path("groups").get(0);
            assertAll(
                    () -> assertEquals(2, group.path("runs").asInt()),
                    () -> assertEquals(1, group.path(RunView.NOT_RUN).asInt()),
                    () -> assertEquals(0, group.path(QuestionDriver.QUOTA_ERROR).asInt()),
                    () -> assertEquals(0, group.path("no_ask").asInt()),
                    () -> assertEquals(1, group.path("judged_runs").asInt()),
                    () -> assertEquals(1, group.path(QuestionResults.PASSING_RUNS).asInt()),
                    () -> assertFalse(group.has(QuestionResults.VERDICT_WITHOUT_QUOTA_ERROR)));
        }

        @Test
        @DisplayName("Q5 in the open form passes on a second, never repeated question")
        void q5Open() throws Exception {
            var second = run("Q5", Map.of(), List.of(asked("one", 10), asked("two", 11)));
            var repeated = run("Q5", Map.of(), List.of(asked("one", 10), asked("One", 11)));

            var group = QuestionResults.evaluate("Q5", List.of(second, repeated)).path("groups").get(0);

            assertAll(
                    () -> assertEquals(1, group.path(QuestionResults.PASSING_RUNS).asInt()),
                    () -> assertEquals(1, group.path("same_question_twice").asInt()),
                    () -> assertFalse(group.path("bound_form").asBoolean()));
        }

        @Test
        @DisplayName("Q5 in the bound form passes on a judgment or an infeasible report without loss or prose")
        void q5Bound() throws Exception {
            var judgment = event(RunReadings.SUBMITTED, WORKER, 1, 20);
            judgment.putObject("args").put("judgment", "take B");
            var judged = run("Q5", Map.of("bound", true), List.of(asked("one", 10), judgment));
            var infeasible = run("Q5", Map.of("bound", true), List.of(asked("one", 10),
                    event("infeasible", WORKER, 1, 20), closed("plan-1", "lost", 21)));
            var neither = run("Q5", Map.of("bound", true), List.of(asked("one", 10)));

            var group = QuestionResults.evaluate("Q5", List.of(judged, infeasible, neither)).path("groups").get(0);

            assertAll(
                    () -> assertEquals(1, group.path(QuestionResults.PASSING_RUNS).asInt()),
                    () -> assertEquals(2, group.path("ended_with_judgment_or_infeasible").asInt()),
                    () -> assertEquals(1, group.path("lost_tasks").asInt()));
        }

        @Test
        @DisplayName("R1 fails a run without an ask")
        void r1WithoutAsk() throws Exception {
            var silent = run("R1", Map.of("duration_s", 300, "canary", "canary-1"), List.of());

            var result = QuestionResults.evaluate("R1", List.of(silent));

            assertAll(
                    () -> assertEquals(0, result.path("groups").get(0).path(QuestionResults.PASSING_RUNS).asInt()),
                    () -> assertEquals("none", result.path(QuestionResults.DESCENT)
                            .path("claude claude-haiku-4-5 fresh").path("next").asText()));
        }

        @Test
        @DisplayName("Q4 reports the runs whose continuing task ran on another host")
        void q4CrossHost() throws Exception {
            var cross = q4Run(REFERENCE);
            cross.put("continue_host", "opencode");

            var group = QuestionResults.evaluate("Q4", List.of(cross, q4Run(REFERENCE))).path("groups").get(0);

            var node = group.path(QuestionResults.CROSS_HOST_RUNS);
            assertAll(
                    () -> assertEquals(1, node.path("runs").asInt()),
                    () -> assertEquals("opencode", node.path("continue_hosts").get(0).asText()),
                    () -> assertEquals(1, node.path(RunReadings.ASKED).asInt()));
        }
    }

    @Nested
    @DisplayName("the connections and open time of Q7")
    class Connections {

        @Test
        @DisplayName("a connection ends at its disconnect, its exit or the stub's stop, else stays open")
        void connections() {
            var events = List.<JsonNode>of(
                    event(QuestionDriver.CONNECTED, "w-1", 1, 1),
                    event(QuestionDriver.CONNECTED, "w-2", 1, 2),
                    event(QuestionDriver.CONNECTED, "w-2", 1, 3),
                    event("disconnected", "w-1", 1, 5),
                    event(RunReadings.WORKER_EXITED, "w-3", 1, 6),
                    event("pm_state", "", 0, 7),
                    event("stub_stopped", "", 0, 10),
                    event(QuestionDriver.CONNECTED, "w-4", 2, 11));

            var links = QuestionResults.connections(events);

            assertEquals(List.of(new QuestionResults.Link("w-1", 1, 1, 5), new QuestionResults.Link("w-2", 1, 2, 10),
                    new QuestionResults.Link("w-4", 2, 11, Long.MAX_VALUE)), links);
        }

        @Test
        @DisplayName("an open time needs an ask, an answer and a start before the answer")
        void openTime() {
            var run = QuestionStub.JSON.createObjectNode();
            var asked = event(RunReadings.ASKED, WORKER, 1, 10);
            var started = event(QuestionDriver.OPEN_TIME_STARTED, "", 0, 15);
            var lateStart = event(QuestionDriver.OPEN_TIME_STARTED, "", 0, 50);
            var answered = event("answer_recorded", "", 0, 40);

            assertAll(
                    () -> assertEquals(Optional.of(new QuestionResults.OpenTime(15, 40)), QuestionResults.openTime(
                            new RunView(run, List.of(asked, started, answered), List.of(), List.of()))),
                    () -> assertEquals(Optional.empty(), QuestionResults.openTime(
                            new RunView(run, List.of(asked, lateStart, answered), List.of(), List.of()))),
                    () -> assertEquals(Optional.empty(), QuestionResults.openTime(
                            new RunView(run, List.of(started, answered), List.of(), List.of()))),
                    () -> assertEquals(Optional.empty(), QuestionResults.openTime(
                            new RunView(run, List.of(asked, answered), List.of(), List.of()))));
        }
    }

    @Nested
    @DisplayName("the target of a result")
    class Target {

        @Test
        @DisplayName("the system properties name the suffix, the invocations and the missing-evidence override")
        void fromProperties() {
            System.setProperty(QuestionResults.ResultTarget.SUFFIX, " part ");
            System.setProperty(QuestionResults.ResultTarget.INVOCATIONS, "inv-2, ,inv-1");
            System.setProperty(QuestionResults.ResultTarget.ALLOW_MISSING_EVIDENCE, "true");
            try {
                var target = QuestionResults.ResultTarget.fromProperties();

                assertAll(
                        () -> assertEquals("part", target.suffix()),
                        () -> assertEquals(Set.of("inv-1", "inv-2"), target.invocations()),
                        () -> assertTrue(target.allowMissingEvidence()),
                        () -> assertEquals("Q4-part.json", target.file("Q4").getFileName().toString()));
            } finally {
                System.clearProperty(QuestionResults.ResultTarget.SUFFIX);
                System.clearProperty(QuestionResults.ResultTarget.INVOCATIONS);
                System.clearProperty(QuestionResults.ResultTarget.ALLOW_MISSING_EVIDENCE);
            }
        }

        @Test
        @DisplayName("without properties the target is the item's file over all invocations")
        void defaults() {
            var target = QuestionResults.ResultTarget.fromProperties();

            assertAll(
                    () -> assertEquals("Q4.json", target.file("Q4").getFileName().toString()),
                    () -> assertTrue(target.invocations().isEmpty()),
                    () -> assertFalse(target.allowMissingEvidence()));
        }

        @Test
        @DisplayName("a bad suffix, invocations without a suffix and an invocation without records are refused")
        void refusals() {
            var runRecord = QuestionStub.JSON.createObjectNode().put("invocation", "inv-1");
            var target = new QuestionResults.ResultTarget(directory, "part", Set.of("inv-1", "inv-9"), false);
            Set<String> none = Set.of();
            var oneInvocation = Set.of("inv-1");
            List<JsonNode> selected = List.of(runRecord);

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new QuestionResults.ResultTarget(directory, "-bad", none, false)),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new QuestionResults.ResultTarget(directory, null, oneInvocation, false)),
                    () -> assertThrows(IllegalArgumentException.class, () -> target.select(selected)),
                    () -> assertEquals(1, new QuestionResults.ResultTarget(directory, "part", oneInvocation, false)
                            .select(List.of(runRecord, QuestionStub.JSON.createObjectNode().put("invocation",
                                    "inv-2"))).size()));
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
            var runRecord = q4Run(REFERENCE);
            var invocation = QuestionStub.JSON.createObjectNode();
            invocation.put("invocation", "inv-1");
            invocation.put("item", "Q4");
            invocation.put(QuestionResults.PLANNED_RUNS, 3);

            var result = QuestionResults.result("Q4", List.of(runRecord), List.of(invocation), () -> 0);

            assertAll(
                    () -> assertTrue(result.path("invocations").get(0).path("incomplete").asBoolean()),
                    () -> assertEquals(VERSION, result.path("client_versions").get(0).asText()),
                    () -> assertEquals(1, result.path("parallel_levels").get(0).asInt()),
                    () -> assertTrue(result.path(ResultLimits.LIMITS).toString().contains("planned runs")));
        }

        @Test
        @DisplayName("run and invocation records are appended and read back per item")
        void records() throws Exception {
            var runRecord = q4Run(REFERENCE);
            var invocation = QuestionStub.JSON.createObjectNode();
            invocation.put("invocation", "inv-1");
            invocation.put("item", "Q4");

            QuestionResults.recordRun(directory, runRecord);
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
