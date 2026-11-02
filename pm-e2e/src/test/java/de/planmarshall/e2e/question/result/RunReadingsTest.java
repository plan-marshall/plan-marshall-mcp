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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import de.planmarshall.e2e.question.driver.Host;
import de.planmarshall.e2e.question.driver.QuestionDriver;
import de.planmarshall.e2e.question.fixture.QuestionFixtures;
import de.planmarshall.e2e.question.result.RunReadings.ContinuationExit;
import de.planmarshall.e2e.question.stub.QuestionStub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("RunReadings: what the evaluation reads from one run")
class RunReadingsTest {

    private static JsonNode json(String text) {
        try {
            return QuestionStub.JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    private static List<JsonNode> lines(String... texts) {
        var nodes = new ArrayList<JsonNode>();
        for (var text : texts) {
            nodes.add(json(text));
        }
        return nodes;
    }

    private static JsonNode stamped(long at, String line) {
        var node = QuestionStub.JSON.createObjectNode();
        node.put("at", at);
        node.put("line", line);
        return node;
    }

    private static RunView view(String run, String... events) {
        return new RunView(json(run), lines(events), List.of(), List.of());
    }

    @Nested
    @DisplayName("the reference")
    class Reference {

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {"3|3|true", "3|' 3 '|true", "3|4|false", "''|3|false"})
        @DisplayName("follows the reference when the answer is the reference")
        void followsReference(String reference, String answer, boolean expected) {
            assertEquals(expected, RunReadings.followsReference(reference, answer));
        }

        @Test
        @DisplayName("reads the last submission of a run against its reference")
        void lastSubmission() {
            var follows = view("{\"reference\":\"42\"}", "{\"event\":\"submitted\",\"args\":{\"answer\":\"41\"}}",
                    "{\"event\":\"submitted\",\"args\":{\"answer\":\"42\"}}");

            assertAll(() -> assertTrue(RunReadings.followsReference(follows)),
                    () -> assertFalse(RunReadings.referenceForm(follows)),
                    () -> assertEquals("42", RunReadings.lastSubmitArgs(follows).path("answer").asText()));
        }
    }

    @Nested
    @DisplayName("Q4 and Q6")
    class Repeats {

        @Test
        @DisplayName("counts an answered question asked again and a plan task submitted again")
        void repeats() {
            var run = view("{}",
                    "{\"event\":\"answer_recorded\",\"items\":[{\"question_text\":\"Which?\"}]}",
                    "{\"event\":\"asked\",\"question_texts\":[\"which?\",\"Other?\"]}",
                    "{\"event\":\"submitted\",\"task\":\"T-1\",\"plan_task_id\":\"p1\"}",
                    "{\"event\":\"submitted\",\"task\":\"T-1-c1\",\"plan_task_id\":\"p1\"}",
                    "{\"event\":\"submitted\",\"task\":\"T-1\"}");

            assertAll(() -> assertEquals(1, RunReadings.reasked(run)), () -> assertEquals(1, RunReadings.redone(run)));
        }

        @Test
        @DisplayName("counts consultation and question mixed up")
        void mixups() {
            var run = view("{}", "{\"event\":\"link_refused\",\"state\":\"consulting\"}",
                    "{\"event\":\"consulted\",\"question\":\"How?\"}",
                    "{\"event\":\"asked\",\"question_texts\":[\"how?\"]}");

            assertEquals(3, RunReadings.mixups(run));
        }
    }

    @Nested
    @DisplayName("the canary and the worker's own tools")
    class Canary {

        @Test
        @DisplayName("finds the canary in a submission")
        void namesCanary() {
            assertAll(() -> assertTrue(RunReadings.namesCanary(view("{\"canary\":\"c-7\"}",
                            "{\"event\":\"submitted\",\"args\":{\"answer\":\"saw c-7\"}}"))),
                    () -> assertFalse(RunReadings.namesCanary(view("{}"))));
        }

        @Test
        @DisplayName("counts a read of the canary file after the ask by the worker's own tool only")
        void rereadsCanary() {
            var file = QuestionFixtures.CANARY_FILE;
            var claudeRead = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\","
                    + "\"input\":{\"file_path\":\"" + file + "\"}}]}}";
            var stubCall = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\","
                    + "\"name\":\"mcp__plan-marshall__pm_do\",\"input\":{\"note\":\"" + file + "\"}}]}}";

            assertAll(() -> assertTrue(RunReadings.rereadsCanary(List.of(stamped(10, claudeRead)), 5)),
                    () -> assertFalse(RunReadings.rereadsCanary(List.of(stamped(1, claudeRead)), 5)),
                    () -> assertFalse(RunReadings.readsFile(stubCall, file)),
                    () -> assertFalse(RunReadings.readsFile("no json " + file, file)),
                    () -> assertFalse(RunReadings.readsFile("{}", file)));
        }

        @Test
        @DisplayName("counts an OpenCode read, but not code that calls a stub tool")
        void openCodeReads() {
            var file = QuestionFixtures.CANARY_FILE;
            var read = "{\"type\":\"tool_use\",\"part\":{\"type\":\"tool\",\"tool\":\"read\",\"state\":{\"input\":"
                    + "{\"path\":\"" + file + "\"}}}}";
            var code = "{\"type\":\"tool_use\",\"part\":{\"type\":\"tool\",\"tool\":\"execute\",\"state\":{\"input\":"
                    + "{\"code\":\"pm_do('" + file + "')\"}}}}";

            assertAll(() -> assertTrue(RunReadings.readsFile(read, file)),
                    () -> assertFalse(RunReadings.readsFile(code, file)));
        }

        @ParameterizedTest
        @CsvSource({"pm_wait,true", "mcp__plan-marshall__pm_state,true", "plan-marshall.pm_do,true", "Read,false"})
        @DisplayName("knows the stub's tools by every host's name")
        void stubTools(String name, boolean expected) {
            assertEquals(expected, RunReadings.stubTool(name));
        }
    }

    @Nested
    @DisplayName("the output")
    class Output {

        @Test
        @DisplayName("reads the final text of each host and a prose question")
        void finalText() {
            var claude = List.of(stamped(1, "{\"type\":\"result\",\"result\":\"Done?\"}"));
            var opencode = List.of(stamped(1, "{\"type\":\"text\",\"part\":{\"text\":\"done\"}}"), stamped(2, "x"));
            var agy = List.of(stamped(1, "{\"conversation_id\":\"c\",\"response\":\"ok\"}"));

            assertAll(() -> assertTrue(RunReadings.proseQuestion(claude)),
                    () -> assertEquals("done", RunReadings.finalText(opencode)),
                    () -> assertEquals("ok", RunReadings.finalText(agy)));
        }

        @Test
        @DisplayName("sums the tokens every host reports")
        void tokens() {
            var outputs = List.of(
                    stamped(1, "{\"type\":\"result\",\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}"),
                    stamped(2, "{\"part\":{\"tokens\":{\"input\":3,\"output\":4,\"cache\":{\"read\":5}}}}"),
                    stamped(3, "{\"conversation_id\":\"c\",\"usage\":{\"total_tokens\":10}}"), stamped(4, "nope"));

            assertAll(() -> assertEquals(25, RunReadings.tokens(outputs)),
                    () -> assertEquals(7, RunReadings.usageTokens(json("{\"input_tokens\":3,\"outputTokens\":4,"
                            + "\"other\":9}"))));
        }

        @Test
        @DisplayName("counts the host's own question tool and how it behaved")
        void hostTool() {
            var tool = Host.CLAUDE.questionTool();
            var run = new RunView(json("{\"host\":\"claude\"}"), List.of(), List.of(),
                    List.of(stamped(1, "{\"name\":\"" + tool + "\",\"is_error\":true}")));
            var hung = new RunView(json("{\"host\":\"claude\",\"timed_out\":true}"), List.of(), List.of(), List.of());

            assertAll(() -> assertEquals(1, RunReadings.hostToolCalls(run)),
                    () -> assertEquals("fails", RunReadings.hostToolBehaviour(run)),
                    () -> assertEquals("hangs", RunReadings.hostToolBehaviour(hung)));
        }
    }

    @Nested
    @DisplayName("times")
    class Times {

        @Test
        @DisplayName("sums the worker time and measures the ask to the next pm_wait and to the exit")
        void times() {
            var run = view("{}", "{\"event\":\"worker_started\",\"worker\":\"w1\",\"generation\":1,\"at\":100}",
                    "{\"event\":\"wait_started\",\"worker\":\"w1\",\"generation\":1,\"at\":150}",
                    "{\"event\":\"worker_exited\",\"worker\":\"w1\",\"generation\":1,\"at\":300}",
                    "{\"event\":\"worker_exited\",\"worker\":\"w2\",\"generation\":1,\"at\":400}");
            var asked = json("{\"worker\":\"w1\",\"generation\":1,\"at\":120}");

            assertAll(() -> assertEquals(200, RunReadings.workerTime(run)),
                    () -> assertEquals(Optional.of(30L), RunReadings.nextPmWait(run, asked)),
                    () -> assertEquals(Optional.of(180L), RunReadings.askToExit(run, asked)));
        }

        @Test
        @DisplayName("computes share, median and maximum, with none for no values")
        void numbers() {
            assertAll(() -> assertEquals(0.5, RunReadings.share(1, 2)), () -> assertEquals(0, RunReadings.share(1, 0)),
                    () -> assertEquals(2, RunReadings.median(List.of(3L, 1L, 2L))),
                    () -> assertEquals(250_000L, RunReadings.median(List.of(400_000L, 100_000L))),
                    () -> assertEquals(3L, RunReadings.median(List.of(4L, 1L, 2L, 7L))),
                    () -> assertEquals(5L, RunReadings.median(List.of(5L))),
                    () -> assertEquals(-1, RunReadings.median(List.of())),
                    () -> assertEquals(3, RunReadings.max(List.of(3L, 1L))),
                    () -> assertEquals(-1, RunReadings.max(List.of())));
        }
    }

    @Nested
    @DisplayName("R2 and R3")
    class Resumes {

        @ParameterizedTest
        @CsvSource({"true,true,10,0,negative_new_session", "false,false,-1,-1,hung", "false,true,10,1,failed_at_once",
                "false,true,10,0,exited_without_failure"})
        @DisplayName("classifies the resume of a missing session")
        void negativeOutcomes(boolean newSession, boolean started, long exitAfter, int exit, String expected) {
            assertEquals(expected, RunReadings.negativeOutcome(newSession, started, exitAfter, exit));
        }

        @Test
        @DisplayName("classifies a resume that outlasts the hung bound as hung and reads it from a run")
        void negativeFromRun() {
            var run = view("{\"negative_new_session\":false}",
                    "{\"event\":\"worker_started\",\"worker\":\"w1\",\"generation\":2,\"resumed\":true,\"at\":0}",
                    "{\"event\":\"worker_exited\",\"worker\":\"w1\",\"generation\":2,\"exit\":1,\"at\":5}");

            assertAll(() -> assertEquals(RunReadings.HUNG,
                            RunReadings.negativeOutcome(false, true, RunReadings.HUNG_RESUME_MS + 1, 1)),
                    () -> assertEquals(RunReadings.FAILED_AT_ONCE, RunReadings.negativeOutcome(run)));
        }

        @Test
        @DisplayName("knows a new session by the warning, another id, or any id on OpenCode")
        void newSessions() {
            assertAll(() -> assertTrue(RunReadings.warnsMissingSession("conversation \"s1\" not found", "s1")),
                    () -> assertTrue(RunReadings.startedNewSession(Host.ANTIGRAVITY, "s1", "", true)),
                    () -> assertFalse(RunReadings.startedNewSession(Host.CLAUDE, "s1", "", false)),
                    () -> assertTrue(RunReadings.startedNewSession(Host.CLAUDE, "s1", "s2", false)),
                    () -> assertFalse(RunReadings.startedNewSession(Host.CLAUDE, "s1", "s1", false)),
                    () -> assertTrue(RunReadings.startedNewSession(Host.OPENCODE, "s1", "s1", false)));
        }

        @Test
        @DisplayName("reads a continuation from the session and the exit after the delivery")
        void continuationAndExit() {
            var events = new String[]{
                    "{\"event\":\"answer_delivered\",\"worker\":\"w1\",\"generation\":2,\"resumed\":true,\"at\":10}",
                    "{\"event\":\"acknowledged\",\"kind\":\"answer\",\"worker\":\"w1\",\"generation\":2,\"at\":11}",
                    "{\"event\":\"submitted\",\"args\":{\"answer\":\"c-1\"}}",
                    "{\"event\":\"worker_exited\",\"worker\":\"w1\",\"generation\":2,\"at\":40}"};
            var run = view("{\"canary\":\"c-1\"}", events);
            var observation = RunReadings.exitObservation(lines(events), "w1", 2);
            var none = RunReadings.exitObservation(List.of(), "w1", 2);

            assertAll(() -> assertTrue(RunReadings.continuedFromSession(run)),
                    () -> assertFalse(RunReadings.continuedFromSession(view("{\"negative\":true}", events))),
                    () -> assertEquals(30L, observation.exitAfterDeliveryMs()),
                    () -> assertNull(none.exitAfterDeliveryMs()));
        }

        @Test
        @DisplayName("reads the host's cut and whether the worker survived it")
        void cut() {
            var run = view("{}",
                    "{\"event\":\"fault_injected\",\"fault\":\"CALL_CUT\",\"cut_by\":\"host\",\"worker\":\"w1\","
                            + "\"generation\":1,\"cut_at\":100}",
                    "{\"event\":\"call\",\"tool\":\"pm_wait\",\"worker\":\"w1\",\"generation\":1,\"at\":130}",
                    "{\"event\":\"answer_delivered\",\"worker\":\"w1\",\"generation\":1,\"at\":140}",
                    "{\"event\":\"acknowledged\",\"kind\":\"answer\",\"worker\":\"w1\",\"generation\":1,\"at\":150}");
            var cut = RunReadings.hostCut(run).orElseThrow();

            assertAll(() -> assertEquals(Optional.of(30L), RunReadings.reWaitAfterCut(run, cut)),
                    () -> assertTrue(RunReadings.cutSurvived(run, cut)),
                    () -> assertTrue(RunReadings.hostCut(view("{}")).isEmpty()));
        }
    }

    @Nested
    @DisplayName("generations, harness output and sessions")
    class Generations {

        private static final String ASKED = "{\"event\":\"asked\",\"worker\":\"w1\",\"generation\":1}";
        private static final String STARTED = "{\"event\":\"worker_started\",\"worker\":\"w1\",\"generation\":2}";
        private static final String CONNECTED = "{\"event\":\"connected\",\"worker\":\"w1\",\"generation\":2}";

        @TempDir
        Path directory;

        private static String exited(int exit) {
            return "{\"event\":\"worker_exited\",\"worker\":\"w1\",\"generation\":2,\"exit\":" + exit + "}";
        }

        @Test
        @DisplayName("a later generation of an asker that exits by itself without a call is a continuation exit")
        void continuationExit() {
            var call = "{\"event\":\"call\",\"worker\":\"w1\",\"generation\":2}";

            assertAll(
                    () -> assertEquals(ContinuationExit.EXITED, RunReadings.continuationExit(
                            view("{}", ASKED, STARTED, CONNECTED, exited(0)))),
                    () -> assertEquals(ContinuationExit.NONE, RunReadings.continuationExit(
                            view("{}", ASKED, STARTED, CONNECTED, exited(143)))),
                    () -> assertEquals(ContinuationExit.NONE, RunReadings.continuationExit(
                            view("{}", ASKED, STARTED, CONNECTED, call, exited(0)))),
                    () -> assertEquals(ContinuationExit.NONE, RunReadings.continuationExit(
                            view("{}", STARTED, CONNECTED, exited(0)))),
                    () -> assertEquals(ContinuationExit.NONE, RunReadings.continuationExit(
                            view("{\"negative\":true}", ASKED, STARTED, CONNECTED, exited(0)))),
                    () -> assertEquals(ContinuationExit.EXITED, RunReadings.continuationExit(
                            view("{\"continuation_early_exit\":true}"))));
        }

        @Test
        @DisplayName("a silent generation of a worker that did not ask ended early, a voter's as a voter early exit")
        void silentExits() {
            var voter = "vote-set-1-reviewer";
            var voterRun = view("{}", "{\"event\":\"connected\",\"worker\":\"" + voter + "\",\"generation\":1}",
                    "{\"event\":\"worker_exited\",\"worker\":\"" + voter + "\",\"generation\":1}");
            var superseded = view("{}", "{\"event\":\"worker_started\",\"worker\":\"w2\",\"generation\":2}",
                    "{\"event\":\"connected\",\"worker\":\"w2\",\"generation\":1}",
                    "{\"event\":\"worker_exited\",\"worker\":\"w2\",\"generation\":1}");

            assertAll(
                    () -> assertTrue(RunReadings.earlyExit(view("{}", STARTED, CONNECTED, exited(0)))),
                    () -> assertFalse(RunReadings.voterEarlyExit(view("{}", STARTED, CONNECTED, exited(0)))),
                    () -> assertTrue(RunReadings.voterEarlyExit(voterRun)),
                    () -> assertFalse(RunReadings.earlyExit(voterRun)),
                    () -> assertFalse(RunReadings.earlyExit(superseded)),
                    () -> assertTrue(RunReadings.earlyExit(view("{\"early_exit\":true}"))));
        }

        @Test
        @DisplayName("the final text is Claude Code's result, OpenCode's last text part or Antigravity's response")
        void finalText() {
            assertAll(
                    () -> assertEquals("Done?", RunReadings.finalText(List.of(stamped(1, "not json"),
                            stamped(2, "{\"type\":\"text\",\"part\":{\"text\":\"Done?\"}}")))),
                    () -> assertEquals("Fine.", RunReadings.finalText(List.of(
                            stamped(1, "{\"conversation_id\":\"c-1\",\"response\":\"Fine.\"}")))),
                    () -> assertEquals("", RunReadings.finalText(List.of(stamped(1, "{\"type\":\"result\"}")))),
                    () -> assertTrue(RunReadings.proseQuestion(List.of(
                            stamped(1, "{\"type\":\"result\",\"result\":\"Which one? \"}")))));
        }

        @Test
        @DisplayName("the tokens of Antigravity's usage are its total, else the sum of its token fields")
        void usageTokens() {
            assertAll(
                    () -> assertEquals(7, RunReadings.usageTokens(json("{\"totalTokenCount\":7,\"inputTokens\":3}"))),
                    () -> assertEquals(5, RunReadings.usageTokens(json(
                            "{\"inputTokens\":3,\"outputTokens\":2,\"model\":\"m\",\"latency\":9}"))),
                    () -> assertEquals(12, RunReadings.tokens(List.of(stamped(1,
                            "{\"conversation_id\":\"c-1\",\"usage\":{\"total_tokens\":12}}")))));
        }

        @Test
        @DisplayName("a quota error is read from the record's rule or from the stderr of its workers")
        void quotaError() throws Exception {
            var named = Files.writeString(directory.resolve("named.log"), "error: quota reached\n",
                    StandardCharsets.UTF_8);
            Files.writeString(QuestionDriver.stderrFile(directory, "w1", 2), "error: QUOTA exhausted\n",
                    StandardCharsets.UTF_8);
            var byStderr = view("{\"workers\":[{\"stderr\":\"" + named + "\"}]}");
            var byOutput = view("{\"workers\":[{\"worker\":\"w1\",\"generation\":2,\"output\":\""
                    + directory.resolve("out.jsonl") + "\"}]}");
            var none = view("{\"workers\":[{\"worker\":\"w1\"},{\"output\":\"out.jsonl\"}]}");
            var byRule = view("{\"quota_error\":true,\"quota_rule\":\"" + QuestionDriver.HARNESS_ERROR_OUTPUT + "\"}");
            var byRefusalRule = view("{\"quota_error\":true,\"quota_rule\":\"" + QuestionDriver.RATE_LIMIT_EVENT
                    + "\"}");
            var refusalLine = QuestionStub.JSON.createObjectNode();
            refusalLine.put("line", "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"rejected\"}}");
            var byRefusal = new RunView(json("{}"), List.of(), List.of(), List.of(refusalLine));

            assertAll(
                    () -> assertTrue(RunReadings.quotaError(byRefusalRule)),
                    () -> assertTrue(RunReadings.quotaError(byRefusal)),
                    () -> assertTrue(RunReadings.quotaError(byStderr)),
                    () -> assertTrue(RunReadings.quotaError(byOutput)),
                    () -> assertFalse(RunReadings.quotaError(none)),
                    () -> assertTrue(RunReadings.quotaError(byRule)),
                    () -> assertFalse(RunReadings.quotaError(view("{\"quota_error\":true}"))));
        }

        @Test
        @DisplayName("a resume of a missing session started a new one when warned, on another id, or on OpenCode")
        void startedNewSession() {
            assertAll(
                    () -> assertTrue(RunReadings.startedNewSession(Host.CLAUDE, "s-1", "", true)),
                    () -> assertFalse(RunReadings.startedNewSession(Host.CLAUDE, "s-1", null, false)),
                    () -> assertFalse(RunReadings.startedNewSession(Host.CLAUDE, "s-1", "", false)),
                    () -> assertTrue(RunReadings.startedNewSession(Host.CLAUDE, "s-1", "s-2", false)),
                    () -> assertFalse(RunReadings.startedNewSession(Host.CLAUDE, "s-1", "s-1", false)),
                    () -> assertTrue(RunReadings.startedNewSession(Host.OPENCODE, "s-1", "s-1", false)),
                    () -> assertTrue(RunReadings.warnsMissingSession("conversation \"s-1\" not found", "s-1")));
        }

        @Test
        @DisplayName("a negative resume is a new session, failed at once, hung or exited without failure")
        void negativeOutcome() {
            assertAll(
                    () -> assertEquals(RunReadings.NEGATIVE_NEW_SESSION, RunReadings.negativeOutcome(true, true, 5, 0)),
                    () -> assertEquals(RunReadings.HUNG, RunReadings.negativeOutcome(true, false, -1, -1)),
                    () -> assertEquals(RunReadings.HUNG, RunReadings.negativeOutcome(false, true, -1, -1)),
                    () -> assertEquals(RunReadings.HUNG,
                            RunReadings.negativeOutcome(false, true, RunReadings.HUNG_RESUME_MS + 1, 1)),
                    () -> assertEquals(RunReadings.EXITED_WITHOUT_FAILURE,
                            RunReadings.negativeOutcome(false, true, 5, 0)),
                    () -> assertEquals(RunReadings.FAILED_AT_ONCE, RunReadings.negativeOutcome(false, true, 5, 2)));
        }
    }
}
