/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.e2e.question.driver;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.question.driver.QuestionDriver.Launch;
import de.planmarshall.e2e.question.driver.QuestionDriver.RecoveryStep;
import de.planmarshall.e2e.question.driver.QuestionDriver.Settings;
import de.planmarshall.e2e.question.driver.QuestionDriver.WarmStep;
import de.planmarshall.e2e.question.driver.QuestionDriver.WarmStop;
import de.planmarshall.e2e.question.driver.QuestionDriver.Worker;
import de.planmarshall.e2e.question.stub.QuestionStub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The driver's classification of how a worker generation ended, its quota rule, the worker's environment, the
 * resolution of a harness's executable and the recovery decision after a loss, each on recorded log and output lines:
 * no harness is run.
 */
@DisplayName("QuestionDriver: classification, quota rule, environment, executable and recovery")
class QuestionDriverTest {

    private static final String WORKER = "w-1";
    private static final String PM_EXEC_LAUNCHED = "{\"pm_exec\":\"launched\",\"pid\":4711}";
    private static final String PM_EXEC_ERROR = "{\"pm_exec\":\"error\",\"message\":\"no such file\"}";

    @TempDir
    Path directory;

    @Nested
    @DisplayName("launch failure")
    class LaunchFailure {

        @Test
        @DisplayName("a generation that exited before its relay connected failed to launch")
        void exitedBeforeConnect() {
            var events = List.of(event("worker_started", WORKER, 1, 100));

            assertTrue(QuestionDriver.launchFailed(events, WORKER, 1, false, false));
        }

        @Test
        @DisplayName("a generation whose relay connected did not fail to launch, whatever came after")
        void connected() {
            var events = List.of(event(QuestionDriver.CONNECTED, WORKER, 1, 100));

            assertFalse(QuestionDriver.launchFailed(events, WORKER, 1, false, true));
        }

        @Test
        @DisplayName("a generation a fault was injected into did not fail to launch")
        void faulted() {
            var events = List.of(event(QuestionDriver.FAULT_INJECTED, WORKER, 1, 100));

            assertFalse(QuestionDriver.launchFailed(events, WORKER, 1, false, false));
        }

        @Test
        @DisplayName("a running generation fails to launch only once pm-exec reported an error")
        void runningNeedsReport() {
            List<JsonNode> events = List.of();

            assertAll(
                    () -> assertFalse(QuestionDriver.launchFailed(events, WORKER, 1, true, false)),
                    () -> assertTrue(QuestionDriver.launchFailed(events, WORKER, 1, true, true)));
        }

        @Test
        @DisplayName("the connection of another generation of the worker does not count")
        void otherGeneration() {
            var events = List.of(event(QuestionDriver.CONNECTED, WORKER, 2, 100));

            assertTrue(QuestionDriver.launchFailed(events, WORKER, 1, false, false));
        }

        @Test
        @DisplayName("pm-exec reports an error on stderr or in the output; its other lines are no report")
        void pmExecReport() {
            assertAll(
                    () -> assertTrue(QuestionDriver.pmExecReported(PM_EXEC_ERROR + "\n", List.of())),
                    () -> assertTrue(QuestionDriver.pmExecReported("", List.of(json(PM_EXEC_ERROR)))),
                    () -> assertFalse(QuestionDriver.pmExecReported(PM_EXEC_LAUNCHED + "\n",
                            List.of(json(PM_EXEC_LAUNCHED)))),
                    () -> assertFalse(QuestionDriver.pmExecReported("not json\n", List.of())));
        }

        @Test
        @DisplayName("the record names the harness's first line and its last five lines, without pm-exec's")
        void harnessCause() {
            var stderr = String.join("\n", PM_EXEC_LAUNCHED, "line 1", "line 2", "line 3", "line 4", "line 5",
                    "line 6", "");

            var failure = QuestionDriver.launchFailure(WORKER, 1, 2, stderr);

            assertAll(
                    () -> assertEquals("line 1", failure.stderr()),
                    () -> assertEquals(List.of("line 2", "line 3", "line 4", "line 5", "line 6"), failure.tail()),
                    () -> assertEquals(2, failure.exit()));
        }

        @Test
        @DisplayName("without a harness line the record names pm-exec's error, else its first line, else nothing")
        void pmExecCause() {
            assertAll(
                    () -> assertEquals(PM_EXEC_ERROR, QuestionDriver.launchFailure(WORKER, 1, 1,
                            PM_EXEC_LAUNCHED + "\n" + PM_EXEC_ERROR).stderr()),
                    () -> assertEquals(PM_EXEC_LAUNCHED, QuestionDriver.launchFailure(WORKER, 1, 1,
                            PM_EXEC_LAUNCHED).stderr()),
                    () -> assertEquals("", QuestionDriver.launchFailure(WORKER, 1, 1, "").stderr()),
                    () -> assertEquals(List.of(), QuestionDriver.launchFailure(WORKER, 1, 1, PM_EXEC_LAUNCHED)
                            .tail()));
        }
    }

    @Nested
    @DisplayName("early exit")
    class EarlyExit {

        @Test
        @DisplayName("a generation that connected and exited without a call ended early")
        void connectedWithoutCall() {
            var events = List.of(event(QuestionDriver.CONNECTED, WORKER, 1, 100));

            var early = QuestionDriver.earlyExit(events, WORKER, 1, 0, "tools unavailable").orElseThrow();

            assertAll(
                    () -> assertEquals(QuestionDriver.EARLY_EXIT, early.outcome()),
                    () -> assertEquals("tools unavailable", early.detail()),
                    () -> assertFalse(early.continuation()),
                    () -> assertFalse(early.voter()));
        }

        @Test
        @DisplayName("a single call of its own generation means it did not end early")
        void oneCall() {
            var events = List.of(event(QuestionDriver.CONNECTED, WORKER, 1, 100),
                    event(QuestionDriver.CALL, WORKER, 1, 110));

            assertEquals(Optional.empty(), QuestionDriver.earlyExit(events, WORKER, 1, 0, ""));
        }

        @Test
        @DisplayName("a call of an earlier generation does not count for this one")
        void callOfEarlierGeneration() {
            var events = List.of(event(QuestionDriver.CALL, WORKER, 1, 100),
                    event(QuestionDriver.CONNECTED, WORKER, 2, 200));

            assertTrue(QuestionDriver.earlyExit(events, WORKER, 2, 0, "").isPresent());
        }

        @Test
        @DisplayName("a generation that never connected or had a fault injected did not end early")
        void notConnectedOrFaulted() {
            var faulted = List.of(event(QuestionDriver.CONNECTED, WORKER, 1, 100),
                    event(QuestionDriver.FAULT_INJECTED, WORKER, 1, 110));

            assertAll(
                    () -> assertEquals(Optional.empty(), QuestionDriver.earlyExit(List.of(), WORKER, 1, 0, "")),
                    () -> assertEquals(Optional.empty(), QuestionDriver.earlyExit(faulted, WORKER, 1, 0, "")));
        }

        @Test
        @DisplayName("a generation after one of its worker that asked is a continuation early exit")
        void continuation() {
            var events = List.of(event(QuestionDriver.ASKED, WORKER, 1, 100),
                    event(QuestionDriver.CONNECTED, WORKER, 2, 200));

            var early = QuestionDriver.earlyExit(events, WORKER, 2, 0, "").orElseThrow();

            assertAll(
                    () -> assertTrue(early.continuation()),
                    () -> assertEquals(QuestionDriver.CONTINUATION_EARLY_EXIT, early.outcome()));
        }

        @Test
        @DisplayName("an ask of the same or a later generation, or of another worker, makes no continuation")
        void askOfSameGeneration() {
            var events = List.of(event(QuestionDriver.ASKED, WORKER, 2, 90), event(QuestionDriver.ASKED, "w-2", 1, 95),
                    event(QuestionDriver.CONNECTED, WORKER, 2, 100), event(QuestionDriver.ASKED, WORKER, 3, 300));

            var early = QuestionDriver.earlyExit(events, WORKER, 2, 0, "").orElseThrow();

            assertAll(
                    () -> assertFalse(early.continuation()),
                    () -> assertEquals(QuestionDriver.EARLY_EXIT, early.outcome()));
        }

        @Test
        @DisplayName("a voter is recorded as voter early exit, even after an ask of its worker")
        void voter() {
            var voter = QuestionStub.VOTE_PREFIX + "set-1-reviewer";
            var events = List.of(event(QuestionDriver.ASKED, voter, 1, 100),
                    event(QuestionDriver.CONNECTED, voter, 2, 200));

            var early = QuestionDriver.earlyExit(events, voter, 2, 0, "").orElseThrow();

            assertAll(
                    () -> assertTrue(early.voter()),
                    () -> assertEquals(QuestionDriver.VOTER_EARLY_EXIT, early.outcome()));
        }

        @Test
        @DisplayName("the detail is the harness's first stderr line, else the last output line")
        void detail() {
            var output = List.of(stamped("{\"type\":\"init\"}"), stamped("last words"));

            assertAll(
                    () -> assertEquals("cause", QuestionDriver.earlyExitDetail(PM_EXEC_LAUNCHED + "\n\ncause\n",
                            output)),
                    () -> assertEquals("last words", QuestionDriver.earlyExitDetail(PM_EXEC_LAUNCHED, output)),
                    () -> assertEquals("", QuestionDriver.earlyExitDetail("", List.of())));
        }
    }

    @Nested
    @DisplayName("quota error")
    class Quota {

        @Test
        @DisplayName("a stderr line of the harness that names the quota, in any case, matches")
        void stderrLine() {
            var stderr = PM_EXEC_LAUNCHED + "\nerror: Individual QUOTA reached. Resets in 42h.\n";

            assertEquals(Optional.of("error: Individual QUOTA reached. Resets in 42h."),
                    QuestionDriver.quotaDetail(stderr, List.of()));
        }

        @Test
        @DisplayName("a line of pm-exec's own never matches, even when it names the quota")
        void pmExecLine() {
            var stderr = "{\"pm_exec\":\"launched\",\"note\":\"quota\"}\n";

            assertEquals(Optional.empty(), QuestionDriver.quotaDetail(stderr, List.of()));
        }

        @ParameterizedTest(name = "error line: {0}")
        @DisplayName("an error line of a harness's structured output that names the quota matches")
        @ValueSource(strings = {
                "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"quota exceeded\"}",
                "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"errors\":[\"Quota reached\"]}",
                "{\"type\":\"error\",\"error\":{\"type\":\"provider\",\"message\":\"quota exhausted\"}}",
                "{\"conversation_id\":\"c-1\",\"status\":\"ERROR\",\"error\":\"Individual quota reached.\"}"})
        void harnessErrorLine(String line) {
            assertEquals(Optional.of(line), QuestionDriver.quotaDetail("", List.of(stamped(line))));
        }

        @ParameterizedTest(name = "not an error line: {0}")
        @DisplayName("a tool's input or result, the model's prose and a successful result never match")
        @ValueSource(strings = {
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"the quota is fine\"}]}}",
                "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"content\":\"quota.txt\"}]}}",
                "{\"type\":\"tool_use\",\"input\":{\"file_path\":\"doc/quota.adoc\"}}",
                "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"quota explained\"}",
                "{\"conversation_id\":\"c-1\",\"response\":\"the quota of the plan\"}",
                "{\"type\":\"rate_limit_event\",\"status\":\"allowed_warning\",\"note\":\"quota\"}",
                "quota in a line that is no JSON"})
        void notAnErrorLine(String line) {
            assertEquals(Optional.empty(), QuestionDriver.quotaDetail("", List.of(stamped(line))));
        }

        @Test
        @DisplayName("an error line that does not name the quota does not match")
        void otherError() {
            var line = "{\"type\":\"error\",\"error\":{\"type\":\"provider.transport\",\"message\":\"reset\"}}";

            assertAll(
                    () -> assertEquals(Optional.of("{\"type\":\"provider.transport\",\"message\":\"reset\"}"),
                            QuestionDriver.harnessErrorText(line)),
                    () -> assertEquals(Optional.empty(), QuestionDriver.quotaDetail("", List.of(stamped(line)))));
        }
    }

    @Nested
    @DisplayName("usage-limit refusal")
    class UsageLimit {

        private static String event(String status, String extra) {
            return "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"" + status + "\"" + extra + "}}";
        }

        @ParameterizedTest(name = "status {0}")
        @DisplayName("a rate_limit_event that allows the account is no refusal")
        @ValueSource(strings = {"allowed", "allowed_warning"})
        void allowed(String status) {
            var line = event(status, ",\"rateLimitType\":\"seven_day\",\"resetsAt\":1793520000,\"utilization\":0.96");

            assertAll(() -> assertEquals(Optional.empty(), QuestionDriver.rateLimitRefusal(List.of(stamped(line)))),
                    () -> assertEquals(Optional.empty(), QuestionDriver.quotaError("w", 1, "",
                            List.of(stamped(line)))));
        }

        @Test
        @DisplayName("any other status refuses, with the type and the reset as an ISO instant")
        void refused() {
            var allowed = event("allowed_warning", "");
            var refusing = event("rejected", ",\"rateLimitType\":\"seven_day\",\"resetsAt\":1793520000");

            var refusal = QuestionDriver.rateLimitRefusal(List.of(stamped(allowed), stamped("no json"),
                    stamped("{\"type\":\"assistant\"}"), stamped(refusing)));
            var quota = QuestionDriver.quotaError("w1", 2, "", List.of(stamped(refusing))).orElseThrow();
            var fields = QuestionDriver.quotaFields(quota);

            assertAll(() -> assertEquals(new QuestionDriver.RateLimitRefusal("rejected", "seven_day",
                            "2026-11-01T08:00:00Z", refusing), refusal.orElseThrow()),
                    () -> assertTrue(quota.usageLimit()),
                    () -> assertEquals(QuestionDriver.RATE_LIMIT_EVENT, quota.rule()),
                    () -> assertEquals(refusing, quota.detail()),
                    () -> assertEquals("w1", quota.worker()),
                    () -> assertEquals(QuestionDriver.RATE_LIMIT_EVENT, fields.get(QuestionDriver.QUOTA_RULE)),
                    () -> assertEquals("seven_day", fields.get(QuestionDriver.RATE_LIMIT_TYPE)),
                    () -> assertEquals("rejected", fields.get(QuestionDriver.RATE_LIMIT_STATUS)),
                    () -> assertEquals("2026-11-01T08:00:00Z", fields.get(QuestionDriver.RESETS_AT)));
        }

        @Test
        @DisplayName("a refusal without type or reset records them empty, a textual reset as given")
        void refusedWithoutDetails() {
            var bare = QuestionDriver.rateLimitRefusal(List.of(stamped(event("blocked", "")))).orElseThrow();
            var textual = QuestionDriver.rateLimitRefusal(List.of(stamped(event("blocked",
                    ",\"resetsAt\":\"tomorrow\"")))).orElseThrow();
            var nullReset = QuestionDriver.rateLimitRefusal(List.of(stamped(event("blocked",
                    ",\"resetsAt\":null")))).orElseThrow();

            assertAll(() -> assertEquals("", bare.rateLimitType()), () -> assertEquals("", bare.resetsAt()),
                    () -> assertEquals("tomorrow", textual.resetsAt()), () -> assertEquals("", nullReset.resetsAt()));
        }

        @Test
        @DisplayName("an event without a textual status is no refusal")
        void noStatus() {
            var lines = List.of(stamped("{\"type\":\"rate_limit_event\",\"status\":\"allowed_warning\"}"),
                    stamped("{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":3}}"));

            assertEquals(Optional.empty(), QuestionDriver.rateLimitRefusal(lines));
        }

        @Test
        @DisplayName("the harness's own quota error wins over a refusing event and records no refusal")
        void harnessErrorFirst() {
            var quota = QuestionDriver.quotaError("w1", 1, "error: quota reached\n",
                    List.of(stamped(event("rejected", "")))).orElseThrow();

            assertAll(() -> assertFalse(quota.usageLimit()),
                    () -> assertEquals(QuestionDriver.HARNESS_ERROR_OUTPUT, quota.rule()),
                    () -> assertFalse(QuestionDriver.quotaFields(quota).containsKey(QuestionDriver.RESETS_AT)));
        }
    }

    @Nested
    @DisplayName("environment and executable")
    class EnvironmentAndExecutable {

        @Test
        @DisplayName("the markers of the driver's host session are stripped and PWD is the worker directory")
        void stripped() {
            var inherited = Map.of("PATH", "/usr/bin", "CLAUDECODE", "1", "CLAUDE_CODE_ENTRYPOINT", "cli",
                    "OPENCODE_SESSION_ID", "ses_1", "CODEX_SANDBOX", "seatbelt", "PWD", "/elsewhere", "HOME", "/home");

            var environment = QuestionDriver.strippedEnvironment(inherited, directory);

            assertEquals(Map.of("PATH", "/usr/bin", "HOME", "/home", "PWD", directory.toString()), environment);
        }

        @Test
        @DisplayName("the override names the executable by its absolute path, and must be an executable file")
        void override() throws Exception {
            var executable = executable(directory.resolve("bin"), "claude");
            var plain = Files.writeString(directory.resolve("plain"), "", StandardCharsets.UTF_8);

            assertAll(
                    () -> assertEquals(executable.toString(),
                            Host.CLAUDE.resolveExecutable(executable.toString(), "", List.of())),
                    () -> assertThrows(IllegalStateException.class,
                            () -> Host.CLAUDE.resolveExecutable(plain.toString(), "", List.of())));
        }

        @Test
        @DisplayName("without an override the PATH is searched first, then the known locations")
        void searchOrder() throws Exception {
            var first = executable(directory.resolve("first"), "opencode");
            var known = executable(directory.resolve("known"), "opencode");
            var searchPath = directory.resolve("empty") + File.pathSeparator + first.getParent();

            assertAll(
                    () -> assertEquals(first.toString(),
                            Host.OPENCODE.resolveExecutable(null, searchPath, List.of(known.getParent()))),
                    () -> assertEquals(known.toString(),
                            Host.OPENCODE.resolveExecutable(null, "", List.of(known.getParent()))));
        }

        @Test
        @DisplayName("an executable found nowhere names the property to set")
        void notFound() {
            var failure = assertThrows(IllegalStateException.class,
                    () -> Host.ANTIGRAVITY.resolveExecutable(null, directory.toString(), List.of()));

            assertTrue(failure.getMessage().contains("-D" + Host.EXECUTABLE_PROPERTY + "agy"), failure.getMessage());
        }
    }

    @Nested
    @DisplayName("recovery after a loss")
    class Recovery {

        private static final String DECISION = "D-1";
        private static final String LOST = WORKER + "#1";

        @Test
        @DisplayName("a lost task that is resumable is resumed")
        void resume() {
            var recovery = QuestionDriver.recovery(List.of(), DECISION, "T-1", LOST,
                    task -> Optional.of("task.resumable"));

            assertEquals(RecoveryStep.RESUME, recovery.step());
        }

        @Test
        @DisplayName("without a continuation or an offer to another generation the driver waits")
        void waitForStub() {
            var events = List.of(offered("T-1", WORKER, 1, 100));

            var recovery = QuestionDriver.recovery(events, DECISION, "T-1", LOST, task -> Optional.of("task.offered"));

            assertAll(
                    () -> assertEquals(RecoveryStep.WAIT, recovery.step()),
                    () -> assertEquals(LOST, recovery.holder()));
        }

        @Test
        @DisplayName("a lost task offered again to another generation is taken")
        void offeredToAnother() {
            var events = List.of(offered("T-1", WORKER, 1, 100), offered("T-1", "w-2", 1, 200));

            var recovery = QuestionDriver.recovery(events, DECISION, "T-1", LOST, task -> Optional.of("task.offered"));

            assertEquals(RecoveryStep.TAKEN, recovery.step());
        }

        @Test
        @DisplayName("a queued continuation gets a new generation")
        void queuedContinuation() {
            var events = List.of(continued("T-2"));

            var recovery = QuestionDriver.recovery(events, DECISION, "T-1", LOST, task -> Optional.of("task.queued"));

            assertAll(
                    () -> assertEquals(RecoveryStep.CONTINUE, recovery.step()),
                    () -> assertEquals("T-2", recovery.task()),
                    () -> assertEquals("E-2", recovery.entity()),
                    () -> assertFalse(recovery.holderEnded()));
        }

        @Test
        @DisplayName("a continuation held by a generation that still runs, or closed, is taken")
        void heldByRunningOrClosed() {
            var events = List.of(continued("T-2"), offered("T-2", "w-2", 1, 300));

            assertAll(
                    () -> assertEquals(RecoveryStep.TAKEN, QuestionDriver.recovery(events, DECISION, "T-1", LOST,
                            task -> Optional.of("task.offered")).step()),
                    () -> assertEquals(RecoveryStep.TAKEN, QuestionDriver.recovery(events, DECISION, "T-1", LOST,
                            "w-2#1"::equals, task -> Optional.of("task.submitted.completed")).step()));
        }

        @Test
        @DisplayName("a continuation held by an ended generation in a state a lease takes over gets a new generation")
        void heldByEndedHandover() {
            var events = List.of(continued("T-2"), offered("T-2", WORKER, 1, 300));

            var recovery = QuestionDriver.recovery(events, DECISION, "T-1", LOST, task -> Optional.of("task.offered"));

            assertAll(
                    () -> assertEquals(RecoveryStep.CONTINUE, recovery.step()),
                    () -> assertTrue(recovery.holderEnded()));
        }

        @Test
        @DisplayName("a continuation held by an ended generation in any other open state is a continuation gap")
        void heldByEndedGap() {
            var events = List.of(continued("T-2"), offered("T-2", WORKER, 1, 300));

            var recovery = QuestionDriver.recovery(events, DECISION, "T-1", LOST, task -> Optional.of("task.answer"));

            assertAll(
                    () -> assertEquals(RecoveryStep.GAP, recovery.step()),
                    () -> assertTrue(recovery.holderEnded()),
                    () -> assertEquals(LOST, recovery.holder()));
        }

        private static JsonNode continued(String task) {
            var node = QuestionStub.JSON.createObjectNode();
            node.put("at", 250);
            node.put("event", "continued");
            node.put("decision", DECISION);
            node.put("task", task);
            node.put("entity", "E-2");
            return node;
        }
    }

    @Nested
    @DisplayName("continuations")
    class Continuations {

        @Test
        @DisplayName("a continuation the asking worker took over pm_wait and closed is continued by hold")
        void continuedByHold() {
            var events = List.of(asked("D-1", WORKER, 1, 100), continued("D-1", "T-2", 150),
                    offered("T-2", WORKER, 1, 160), closed("T-2", WORKER, 1, 200));

            var held = QuestionDriver.heldContinuation(events, "D-1").orElseThrow();

            assertAll(
                    () -> assertEquals("T-2", held.task()),
                    () -> assertEquals(1, held.generation()),
                    () -> assertEquals(200, held.closedAt()),
                    () -> assertEquals(-1, held.submittedAt()));
        }

        @Test
        @DisplayName("a continuation leased at a spawn, or closed by another worker, is not continued by hold")
        void notByHold() {
            var atSpawn = offered("T-2", WORKER, 2, 160);
            ((ObjectNode) atSpawn).put("at_spawn", true);
            var spawned = List.of(asked("D-1", WORKER, 1, 100), continued("D-1", "T-2", 150), atSpawn,
                    closed("T-2", WORKER, 2, 200));
            var other = List.of(asked("D-1", WORKER, 1, 100), continued("D-1", "T-2", 150),
                    offered("T-2", "w-2", 1, 160), closed("T-2", "w-2", 1, 200));

            assertAll(
                    () -> assertEquals(Optional.empty(), QuestionDriver.heldContinuation(spawned, "D-1")),
                    () -> assertEquals(Optional.empty(), QuestionDriver.heldContinuation(other, "D-1")));
        }

        @Test
        @DisplayName("a warm continuation is pending, taken, or waits on an asker that exited")
        void warmContinuation() {
            var events = List.of(continued("D-1", "T-2", 150));

            assertAll(
                    () -> assertEquals(WarmStep.PENDING, QuestionDriver.warmContinuation(List.of(), "D-1",
                            task -> Optional.empty(), true).step()),
                    () -> assertEquals(WarmStep.PENDING, QuestionDriver.warmContinuation(events, "D-1",
                            task -> Optional.of("task.queued"), true).step()),
                    () -> assertEquals(WarmStep.ASKER_EXITED, QuestionDriver.warmContinuation(events, "D-1",
                            task -> Optional.of("task.queued"), false).step()),
                    () -> assertEquals(WarmStep.TAKEN, QuestionDriver.warmContinuation(events, "D-1",
                            task -> Optional.of("task.offered"), false).step()));
        }
    }

    @Nested
    @DisplayName("stub log")
    class StubLog {

        @Test
        @DisplayName("a held wait ends with the first event of its generation that ends a wait")
        void waits() {
            var atSpawn = offered(WORKER, WORKER, 1, 150);
            ((ObjectNode) atSpawn).put("at_spawn", true);
            var events = List.of(event("wait_started", WORKER, 1, 100), atSpawn,
                    event("wait_again", WORKER, 2, 160), event("wait_end", WORKER, 1, 200),
                    event("wait_started", WORKER, 2, 300));

            var waits = QuestionDriver.waits(events);

            assertAll(
                    () -> assertEquals(List.of(new QuestionDriver.Wait(WORKER, 1, 100, 200),
                            new QuestionDriver.Wait(WORKER, 2, 300, Long.MAX_VALUE)), waits),
                    () -> assertTrue(QuestionDriver.holdingWait(events, WORKER, 2)),
                    () -> assertFalse(QuestionDriver.holdingWait(events, WORKER, 1)));
        }

        @Test
        @DisplayName("the stub's stop ends every held wait")
        void stubStopped() {
            var stop = QuestionStub.JSON.createObjectNode().put("at", 500).put("event", "stub_stopped");
            var events = List.<JsonNode>of(event("wait_started", WORKER, 1, 100), stop);

            assertEquals(List.of(new QuestionDriver.Wait(WORKER, 1, 100, 500)), QuestionDriver.waits(events));
        }

        @Test
        @DisplayName("a log still being written skips its unfinished last line and fails on an earlier broken one")
        void linesSoFar() throws Exception {
            var cut = Files.writeString(directory.resolve("cut.jsonl"), "{\"a\":1}\n{\"b\":", StandardCharsets.UTF_8);
            var broken = Files.writeString(directory.resolve("broken.jsonl"), "{\"a\":\n{\"b\":2}\n",
                    StandardCharsets.UTF_8);

            var read = QuestionDriver.readJsonLinesSoFar(cut);

            assertAll(
                    () -> assertEquals(1, read.nodes().size()),
                    () -> assertEquals(1, read.skipped()),
                    () -> assertEquals(List.of(), QuestionDriver.jsonLinesSoFar(directory.resolve("missing"))),
                    () -> assertThrows(IOException.class, () -> QuestionDriver.readJsonLinesSoFar(broken)));
        }
    }

    @Nested
    @DisplayName("warm asker")
    class WarmAsker {

        @Test
        @DisplayName("a warm asker that exited without pm_wait after its ask is logged as warm_asker_exited")
        void exited() throws Exception {
            var bytes = new byte[4];
            new SecureRandom().nextBytes(bytes);
            var socket = Path.of("/tmp", "pmq-drv-" + HexFormat.of().formatHex(bytes) + ".sock");
            var settings = new Settings(Host.CLAUDE, null, directory.resolve("workspace"), directory.resolve("run"),
                    List.of("pm-exec"));
            try (var driver = QuestionDriver.start(QuestionStub.Settings.of(directory.resolve("stub"), socket),
                         settings)) {
                var process = new ProcessBuilder("true").start();
                process.waitFor();
                var launch = new Launch(WORKER, 1, "implementer", "prompt", false, null, List.of(), null);
                var worker = new Worker(launch, Host.CLAUDE, process, directory, directory.resolve("out.jsonl"),
                        directory.resolve("relay.jsonl"));

                var stop = driver.awaitWarmStop(worker, 0, Duration.ofSeconds(5));

                var names = new ArrayList<String>();
                QuestionDriver.jsonLinesSoFar(driver.stub().events())
                        .forEach(event -> names.add(event.path("event").asText()));
                assertAll(
                        () -> assertEquals(WarmStop.EXITED, stop),
                        () -> assertTrue(names.contains(QuestionDriver.WARM_ASKER_EXITED), names.toString()));
            } finally {
                Files.deleteIfExists(socket);
            }
        }
    }

    @Nested
    @DisplayName("call cut")
    class CallCutReading {

        private JsonNode relay(long at, String dir, String line) {
            return QuestionStub.JSON.createObjectNode().put("at", at).put("dir", dir).put("line", line);
        }

        private JsonNode withField(JsonNode event, String field, String value) {
            return ((ObjectNode) event).put(field, value);
        }

        @Test
        @DisplayName("the host's cancellation of a pm_wait it requested after the ask cuts the held call")
        void cancelled() {
            var events = List.of(event("wait_started", WORKER, 1, 100));
            var relay = List.of(
                    relay(40, "from_host", "{\"method\":\"tools/call\",\"id\":3,\"params\":{\"name\":\"pm_wait\"}}"),
                    relay(90, "from_host", "{\"method\":\"tools/call\",\"id\":7,\"params\":{\"name\":\"pm_wait\"}}"),
                    relay(95, "from_host", "{\"method\":\"tools/call\",\"id\":8,\"params\":{\"name\":\"pm_do\"}}"),
                    relay(120, "to_host", "{\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":7}}"),
                    relay(130, "from_host", "not json"),
                    relay(135, "from_host", "[1]"),
                    relay(140, "from_host", "{\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":3}}"),
                    relay(150, "from_host", "{\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":7}}"),
                    relay(160, "from_host", "{\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":7}}"));

            var cut = QuestionDriver.callCut(events, relay, WORKER, 1, 50).orElseThrow();

            assertAll(
                    () -> assertEquals(QuestionDriver.CUT_CANCELLED, cut.evidence()),
                    () -> assertEquals(150, cut.cutAt()),
                    () -> assertEquals(50, cut.afterMs()));
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("a superseding pm_wait, a failed reply to pm_wait or a disconnect cuts the held call")
        @ValueSource(strings = {"superseded", "reply_failed", "disconnected"})
        void stubSigns(String evidence) {
            var sign = switch (evidence) {
                case "superseded" -> withField(event("wait_again", WORKER, 1, 130), "reason", "superseded");
                case "reply_failed" -> withField(event("reply_failed", WORKER, 1, 130), "tool", "pm_wait");
                default -> event("disconnected", WORKER, 1, 130);
            };
            var events = List.of(event("wait_started", WORKER, 1, 100),
                    withField(event("wait_again", WORKER, 1, 110), "reason", "progress"),
                    withField(event("reply_failed", WORKER, 1, 115), "tool", "pm_do"),
                    event("disconnected", "w-2", 1, 120), event("call", WORKER, 1, 125), sign,
                    event("disconnected", WORKER, 1, 140));

            var cut = QuestionDriver.callCut(events, List.of(), WORKER, 1, 50).orElseThrow();

            assertAll(
                    () -> assertEquals(evidence, cut.evidence()),
                    () -> assertEquals(130, cut.cutAt()));
        }

        @Test
        @DisplayName("no held call after the ask, or no sign of a cut, is no cut")
        void none() {
            var held = List.of(event("wait_started", WORKER, 1, 100));
            var before = List.of(event("wait_started", WORKER, 1, 10), event("disconnected", WORKER, 1, 20));

            assertAll(
                    () -> assertEquals(Optional.empty(), QuestionDriver.callCut(held, List.of(), WORKER, 1, 50)),
                    () -> assertEquals(Optional.empty(), QuestionDriver.callCut(before, List.of(), WORKER, 1, 50)),
                    () -> assertEquals(Optional.empty(), QuestionDriver.callCut(held, List.of(), WORKER, 2, 50)));
        }

        @Test
        @DisplayName("the relay's log is read without its unfinished last line and its lines that are no JSON")
        void relayLines() throws Exception {
            var log = Files.writeString(directory.resolve("relay.jsonl"),
                    "{\"at\":1}\n\nnot json\n{\"at\":2}\n{\"at\":", StandardCharsets.UTF_8);

            var lines = QuestionDriver.relayLines(log);

            assertAll(
                    () -> assertEquals(2, lines.size()),
                    () -> assertEquals(List.of(), QuestionDriver.relayLines(directory.resolve("none"))));
        }
    }

    @Nested
    @DisplayName("readings of the logs and the configuration")
    class Readings {

        @Test
        @DisplayName("a file ends cut off only in a line that is not blank; a missing file has no lines")
        void writtenLines() throws Exception {
            var blankEnd = Files.writeString(directory.resolve("blank"), "a\r\nb\n \t", StandardCharsets.UTF_8);
            var cut = Files.writeString(directory.resolve("cut"), "a\nb", StandardCharsets.UTF_8);

            assertAll(
                    () -> assertEquals(new QuestionDriver.WrittenLines(List.of("a", "b"), false),
                            QuestionDriver.writtenLines(blankEnd)),
                    () -> assertEquals(new QuestionDriver.WrittenLines(List.of("a"), true),
                            QuestionDriver.writtenLines(cut)),
                    () -> assertEquals(new QuestionDriver.WrittenLines(List.of(), false),
                            QuestionDriver.writtenLines(directory.resolve("missing"))));
        }

        @Test
        @DisplayName("a log whose last complete line is no JSON skips it")
        void lastLineNoJson() throws Exception {
            var file = Files.writeString(directory.resolve("tail.jsonl"), "{\"a\":1}\n{\"b\":\n",
                    StandardCharsets.UTF_8);

            var read = QuestionDriver.readJsonLinesSoFar(file);

            assertAll(
                    () -> assertEquals(1, read.nodes().size()),
                    () -> assertEquals(1, read.skipped()));
        }

        @Test
        @DisplayName("the output lines are the stamped lines that are JSON")
        void outputLines() throws Exception {
            var file = Files.writeString(directory.resolve("out.jsonl"),
                    "{\"at\":1,\"line\":\"{\\\"type\\\":\\\"init\\\"}\"}\n{\"at\":2,\"line\":\"plain text\"}\n",
                    StandardCharsets.UTF_8);

            var lines = QuestionDriver.outputLines(file);

            assertAll(
                    () -> assertEquals(1, lines.size()),
                    () -> assertEquals("init", lines.getFirst().path("type").asText()));
        }

        @Test
        @DisplayName("OpenCode's data home is global by default, or per worker, in any case; anything else is refused")
        void openCodeDataHome() {
            assertAll(
                    () -> assertEquals(QuestionDriver.OpenCodeDataHome.GLOBAL, QuestionDriver.OpenCodeDataHome.of(null)),
                    () -> assertEquals(QuestionDriver.OpenCodeDataHome.WORKER,
                            QuestionDriver.OpenCodeDataHome.of(" Worker ")),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> QuestionDriver.OpenCodeDataHome.of("shared")));
        }

        @Test
        @DisplayName("the global data home is XDG_DATA_HOME/opencode, else below the home directory")
        void globalData() {
            var fallback = Path.of(System.getProperty("user.home"), ".local", "share", "opencode");

            assertAll(
                    () -> assertEquals(Path.of("/data", "opencode"),
                            QuestionDriver.globalOpencodeData(Map.of("XDG_DATA_HOME", "/data"))),
                    () -> assertEquals(fallback, QuestionDriver.globalOpencodeData(Map.of("XDG_DATA_HOME", " "))),
                    () -> assertEquals(fallback, QuestionDriver.globalOpencodeData(Map.of())));
        }

        @Test
        @DisplayName("a worker's own data home is set in its environment, the global one leaves it as it is")
        void applyDataHome() throws Exception {
            var worker = new HashMap<String, String>();
            var global = new HashMap<String, String>();

            QuestionDriver.applyOpenCodeDataHome(worker, QuestionDriver.OpenCodeDataHome.WORKER, directory);
            QuestionDriver.applyOpenCodeDataHome(global, QuestionDriver.OpenCodeDataHome.GLOBAL, directory);

            assertAll(
                    () -> assertEquals(directory.resolve("data").toString(), worker.get("XDG_DATA_HOME")),
                    () -> assertTrue(Files.isDirectory(directory.resolve("data").resolve("opencode"))),
                    () -> assertTrue(global.isEmpty()));
        }

        @Test
        @DisplayName("opencode.json carries the relay identity, the tool timeout and the work tools only")
        void opencodeConfig() throws Exception {
            var launch = new Launch(WORKER, 1, "implementer", "prompt", false, Duration.ofSeconds(90),
                    List.of("read"), null);

            var config = QuestionStub.JSON.readTree(QuestionDriver.opencodeConfig(List.of("relay", "--x"), launch,
                    Map.of("PM_Q_WORKER", WORKER, "HOME", "/home")));

            var server = config.path("mcp").path(QuestionStub.SERVER);
            var tools = config.path("agent").path(Host.OPENCODE_AGENT).path("tools");
            assertAll(
                    () -> assertEquals(90_000, server.path("timeout").asLong()),
                    () -> assertEquals(WORKER, server.path("environment").path("PM_Q_WORKER").asText()),
                    () -> assertFalse(server.path("environment").has("HOME")),
                    () -> assertTrue(tools.path("read").asBoolean()),
                    () -> assertFalse(tools.path("*").asBoolean()));
        }

        @Test
        @DisplayName("the pm-exec property names the command line")
        void resolveExec() {
            System.setProperty("pm.experiment.exec", "/opt/pm-exec");
            try {
                assertEquals(List.of("/opt/pm-exec"), QuestionDriver.resolveExec());
            } finally {
                System.clearProperty("pm.experiment.exec");
            }
        }

        @Test
        @DisplayName("a held wait is the generation's open pm_wait started at or after the given time")
        void heldWait() {
            var events = List.of(event("wait_started", WORKER, 1, 100), event("wait_started", "w-2", 1, 100));

            assertAll(
                    () -> assertEquals(Optional.of(new QuestionDriver.Wait(WORKER, 1, 100, Long.MAX_VALUE)),
                            QuestionDriver.heldWait(events, WORKER, 1, 50)),
                    () -> assertEquals(Optional.empty(), QuestionDriver.heldWait(events, WORKER, 1, 150)),
                    () -> assertEquals(Optional.empty(), QuestionDriver.heldWait(events, WORKER, 2, 50)));
        }

        @Test
        @DisplayName("the stub's own release of a generation's task after the delivery is its loss")
        void stubLoss() {
            var released = ((ObjectNode) event("released", WORKER, 1, 200)).put("reason", "fenced");
            var early = ((ObjectNode) event("lost", WORKER, 1, 50)).put("reason", "exit");
            var noReason = event("resumable", WORKER, 1, 210);

            assertAll(
                    () -> assertEquals(Optional.of(released),
                            QuestionDriver.stubLoss(List.of(early, noReason, released), WORKER, 1, 100)),
                    () -> assertEquals(Optional.empty(),
                            QuestionDriver.stubLoss(List.of(early, noReason), WORKER, 1, 100)));
        }

        @Test
        @DisplayName("a missing session id has OpenCode's form on OpenCode and is a UUID elsewhere")
        void missingSessionId() {
            assertAll(
                    () -> assertTrue(QuestionDriver.missingSessionId(Host.OPENCODE).matches("ses_[0-9a-f]{26}")),
                    () -> assertEquals(36, QuestionDriver.missingSessionId(Host.CLAUDE).length()));
        }
    }

    @Nested
    @DisplayName("with a running stub and a stand-in worker process")
    class WithStub {

        private Process process;

        private Worker worker(Host host, Process running) {
            process = running;
            var launch = new Launch(WORKER, 1, "implementer", "prompt", false, null, List.of(), null);
            return new Worker(launch, host, running, directory.resolve("wd"), directory.resolve("out.jsonl"),
                    directory.resolve("relay.jsonl"));
        }

        private Process exited() throws Exception {
            var exited = new ProcessBuilder("true").start();
            exited.waitFor();
            return exited;
        }

        private Process sleeping() throws IOException {
            return new ProcessBuilder("sleep", "60").start();
        }

        private void run(DriverAction action) throws Exception {
            var bytes = new byte[4];
            new SecureRandom().nextBytes(bytes);
            var socket = Path.of("/tmp", "pmq-drv-" + HexFormat.of().formatHex(bytes) + ".sock");
            var settings = new Settings(Host.CLAUDE, null, directory.resolve("workspace"), directory.resolve("run"),
                    List.of("pm-exec"));
            try (var driver = QuestionDriver.start(QuestionStub.Settings.of(directory.resolve("stub"), socket),
                         settings)) {
                action.run(driver);
            } finally {
                if (process != null) {
                    process.destroyForcibly();
                }
                Files.deleteIfExists(socket);
            }
        }

        private void log(QuestionDriver driver, String name, long at, Map<String, Object> fields) {
            var all = new LinkedHashMap<String, Object>(fields);
            all.put("worker", WORKER);
            all.put("generation", 1);
            all.put("at", at);
            driver.stub().event(name, all);
        }

        private List<JsonNode> logged(QuestionDriver driver, String name) throws IOException {
            return QuestionDriver.jsonLinesSoFar(driver.stub().events()).stream()
                    .filter(event -> name.equals(event.path("event").asText())).toList();
        }

        @Test
        @DisplayName("the worktree digest leaves out the harness configuration and data, and logs itself")
        void worktreeDigest() throws Exception {
            run(driver -> {
                var worker = worker(Host.CLAUDE, exited());
                Files.createDirectories(worker.directory().resolve("data"));
                Files.writeString(worker.directory().resolve("a.txt"), "a", StandardCharsets.UTF_8);
                Files.writeString(worker.directory().resolve("data").resolve("x"), "1", StandardCharsets.UTF_8);
                Files.writeString(worker.directory().resolve(".hidden"), "1", StandardCharsets.UTF_8);
                Files.writeString(worker.directory().resolve("opencode.json"), "{}", StandardCharsets.UTF_8);

                var first = driver.worktreeDigest(worker);
                Files.writeString(worker.directory().resolve("data").resolve("x"), "2", StandardCharsets.UTF_8);
                Files.writeString(worker.directory().resolve("mcp-config.json"), "{}", StandardCharsets.UTF_8);
                var unchanged = driver.worktreeDigest(worker);
                Files.writeString(worker.directory().resolve("a.txt"), "b", StandardCharsets.UTF_8);
                var changed = driver.worktreeDigest(worker);

                assertAll(
                        () -> assertEquals(first, unchanged),
                        () -> assertNotEquals(first, changed),
                        () -> assertEquals(3, logged(driver, "worktree_digest").size()));
            });
        }

        @Test
        @DisplayName("the worktree digest leaves out only the data directory, not a file whose name starts with data")
        void worktreeDigestDataPrefix() throws Exception {
            run(driver -> {
                var worker = worker(Host.CLAUDE, exited());
                Files.createDirectories(worker.directory());
                Files.writeString(worker.directory().resolve("data_utils.py"), "a", StandardCharsets.UTF_8);
                Files.writeString(worker.directory().resolve("data"), "a", StandardCharsets.UTF_8);

                var first = driver.worktreeDigest(worker);
                Files.writeString(worker.directory().resolve("data_utils.py"), "b", StandardCharsets.UTF_8);
                var afterPrefixed = driver.worktreeDigest(worker);
                Files.writeString(worker.directory().resolve("data"), "b", StandardCharsets.UTF_8);
                var afterDataFile = driver.worktreeDigest(worker);

                assertAll(
                        () -> assertNotEquals(first, afterPrefixed),
                        () -> assertNotEquals(afterPrefixed, afterDataFile));
            });
        }

        @Test
        @DisplayName("a held call without a cut by the deadline is logged as not cut, with its hold")
        void callNotCut() throws Exception {
            run(driver -> {
                var worker = worker(Host.ANTIGRAVITY, exited());
                log(driver, "wait_started", 100, Map.of());

                var cut = driver.awaitCallCut(worker, 50, 0, new Host.CutSetting(null, Duration.ofSeconds(60),
                        "fixed"));

                var notCut = logged(driver, QuestionDriver.CALL_NOT_CUT).getFirst();
                assertAll(
                        () -> assertEquals(Optional.empty(), cut),
                        () -> assertTrue(notCut.path("held").asBoolean()),
                        () -> assertEquals(100, notCut.path("held_since").asLong()),
                        () -> assertEquals(-1, notCut.path("tool_timeout_ms").asLong()));
            });
        }

        @Test
        @DisplayName("a held call the logs show cut is logged as the injected fault, cut by the host")
        void callCut() throws Exception {
            run(driver -> {
                var worker = worker(Host.CLAUDE, exited());
                log(driver, "wait_started", 100, Map.of());
                log(driver, "disconnected", 130, Map.of());

                var cut = driver.awaitCallCut(worker, 50, Long.MAX_VALUE, Host.CLAUDE.cutSetting()).orElseThrow();

                var fault = logged(driver, QuestionDriver.FAULT_INJECTED).getFirst();
                assertAll(
                        () -> assertEquals("disconnected", cut.evidence()),
                        () -> assertEquals("host", fault.path("cut_by").asText()),
                        () -> assertEquals(Fault.CALL_CUT.name(), fault.path("fault").asText()),
                        () -> assertEquals(30, fault.path("after_ms").asLong()));
            });
        }

        @Test
        @DisplayName("the recovery is logged; a continuation held by the lost generation is a logged gap")
        void recovery() throws Exception {
            run(driver -> {
                var worker = worker(Host.CLAUDE, exited());

                var waiting = driver.recovery(worker, "D-1", "T-1");
                driver.stub().event("continued", Map.of("decision", "D-1", "task", "T-2", "entity", "E-2"));
                log(driver, "offered", System.currentTimeMillis(), Map.of("task", "T-2"));
                var gap = driver.recovery(worker, "D-1", "T-1");

                assertAll(
                        () -> assertEquals(RecoveryStep.WAIT, waiting.step()),
                        () -> assertEquals(RecoveryStep.GAP, gap.step()),
                        () -> assertEquals(2, logged(driver, QuestionDriver.RECOVERY).size()),
                        () -> assertTrue(logged(driver, QuestionDriver.RECOVERY).getLast().path("holder_ended")
                                .asBoolean()),
                        () -> assertEquals(1, logged(driver, QuestionDriver.CONTINUATION_GAP).size()));
            });
        }

        @Test
        @DisplayName("a continuation held by a generation of no driver of the stub that still runs is taken")
        void recoveryTaken() throws Exception {
            run(driver -> {
                var worker = worker(Host.CLAUDE, exited());
                driver.stub().event("continued", Map.of("decision", "D-1", "task", "T-2"));
                driver.stub().event("offered", Map.of("task", "T-2", "worker", "w-2", "generation", 1));

                assertEquals(RecoveryStep.TAKEN, driver.recovery(worker, "D-1", "T-1").step());
            });
        }

        @Test
        @DisplayName("an answer acknowledged in time is supervised without a loss")
        void acknowledged() throws Exception {
            run(driver -> {
                var worker = worker(Host.CLAUDE, exited());
                var now = System.currentTimeMillis();
                log(driver, "answer_delivered", now, Map.of());
                log(driver, "acknowledged", now + 1, Map.of("kind", "answer"));

                assertAll(
                        () -> assertTrue(driver.superviseAnswer(worker, Duration.ZERO)),
                        () -> assertEquals(List.of(), logged(driver, QuestionDriver.LOST_DETECTED)));
            });
        }

        @Test
        @DisplayName("an answer never delivered is no loss on a stub that loses a worker at its exit")
        void undelivered() throws Exception {
            run(driver -> {
                var worker = worker(Host.CLAUDE, exited());

                assertAll(
                        () -> assertFalse(driver.superviseAnswer(worker, Duration.ZERO)),
                        () -> assertEquals(List.of(), logged(driver, QuestionDriver.ANSWER_UNDELIVERED)));
            });
        }

        @Test
        @DisplayName("a missing acknowledgement past the deadline is a loss the stub saw first where it released")
        void ackMissing() throws Exception {
            run(driver -> {
                var worker = worker(Host.CLAUDE, exited());
                var delivered = System.currentTimeMillis() - 60_000;
                log(driver, "answer_delivered", delivered, Map.of());
                log(driver, "released", delivered + 1_000, Map.of("reason", "exit"));

                var acknowledged = driver.superviseAnswer(worker, Duration.ZERO);

                var lost = logged(driver, QuestionDriver.LOST_DETECTED).getFirst();
                assertAll(
                        () -> assertFalse(acknowledged),
                        () -> assertEquals("answer_ack_missing", lost.path("by").asText()),
                        () -> assertEquals("stub", lost.path("first_by").asText()),
                        () -> assertEquals(1_000, lost.path("stub_after_ms").asLong()));
            });
        }

        @Test
        @DisplayName("on an informational host a worker silent past the limit is a loss detected by silence")
        void silence() throws Exception {
            run(driver -> {
                var worker = worker(Host.OPENCODE, sleeping());
                var delivered = System.currentTimeMillis() - 600_000;
                log(driver, "answer_delivered", delivered, Map.of());
                log(driver, QuestionDriver.CALL, delivered + 1, Map.of("tool", "pm_wait"));

                var acknowledged = driver.superviseAnswer(worker, Duration.ZERO);

                var lost = logged(driver, QuestionDriver.LOST_DETECTED).getFirst();
                assertAll(
                        () -> assertFalse(acknowledged),
                        () -> assertEquals("silence", lost.path("by").asText()),
                        () -> assertEquals("driver", lost.path("first_by").asText()),
                        () -> assertFalse(worker.process().isAlive()));
            });
        }

        @Test
        @DisplayName("an asker that exited by itself is not ended, and the open time starts")
        void askerExited() throws Exception {
            run(driver -> {
                var worker = worker(Host.CLAUDE, exited());
                log(driver, "worker_exited", System.currentTimeMillis(), Map.of());

                var end = driver.endAsker(worker, System.currentTimeMillis(), Duration.ofSeconds(5));

                assertAll(
                        () -> assertFalse(end.endedByDriver()),
                        () -> assertEquals(List.of(), logged(driver, QuestionDriver.ASKER_ENDED_BY_DRIVER)),
                        () -> assertEquals(1, logged(driver, QuestionDriver.OPEN_TIME_STARTED).size()));
            });
        }

        @Test
        @DisplayName("an asker that still runs at its silence limit is ended, with whether it held a pm_wait")
        void askerEnded() throws Exception {
            run(driver -> {
                var worker = worker(Host.CLAUDE, sleeping());
                // the exit first: logged after the wait, it would end the wait before the asker is ended
                log(driver, "worker_exited", System.currentTimeMillis(), Map.of());
                log(driver, "wait_started", System.currentTimeMillis(), Map.of());

                var end = driver.endAsker(worker, System.currentTimeMillis(), Duration.ZERO);

                assertAll(
                        () -> assertTrue(end.endedByDriver()),
                        () -> assertTrue(end.holdingWait()),
                        () -> assertTrue(logged(driver, QuestionDriver.ASKER_ENDED_BY_DRIVER).getFirst()
                                .path("holding_wait").asBoolean()),
                        () -> assertFalse(worker.process().isAlive()));
            });
        }

        @Test
        @DisplayName("a generation that does not hold is not stopped; one that holds is stopped with its tree")
        void stopWhileHolding() throws Exception {
            run(driver -> {
                var idle = worker(Host.CLAUDE, exited());
                var none = driver.stopWhileHolding(idle, 0, Duration.ZERO);
                var holding = worker(Host.CLAUDE, sleeping());
                log(driver, "wait_started", System.currentTimeMillis(), Map.of());

                var held = driver.stopWhileHolding(holding, 0, Duration.ofSeconds(5));
                var signalled = driver.signalledPids(holding);
                driver.terminate(holding);

                assertAll(
                        () -> assertEquals(Optional.empty(), none),
                        () -> assertEquals("no_hold", logged(driver, QuestionDriver.FAULT_NOT_INJECTED).getFirst()
                                .path("reason").asText()),
                        () -> assertTrue(held.isPresent()),
                        () -> assertTrue(signalled.orElseThrow() >= 1),
                        () -> assertFalse(holding.process().isAlive()));
            });
        }

        @Test
        @DisplayName("a continuation the asker took and closed over its hold is logged as continued by hold")
        void continuedByHold() throws Exception {
            run(driver -> {
                var now = System.currentTimeMillis();
                log(driver, QuestionDriver.ASKED, now, Map.of("decision", "D-1"));
                driver.stub().event("continued", Map.of("decision", "D-1", "task", "T-2", "entity", "T-2"));
                log(driver, "offered", now + 1, Map.of("task", "T-2"));
                log(driver, "closed", now + 2, Map.of("task", "T-2", "outcome", "submitted"));

                var held = driver.continuedByHold("D-1");

                assertAll(
                        () -> assertEquals("T-2", held.orElseThrow().task()),
                        () -> assertEquals(1, logged(driver, QuestionDriver.CONTINUED_BY_HOLD).size()),
                        () -> assertEquals(Optional.empty(), driver.continuedByHold("D-9")));
            });
        }

        @Test
        @DisplayName("a warm continuation not continued by the end of the wait is logged; a taken one is not")
        void warmContinuation() throws Exception {
            run(driver -> {
                var asker = worker(Host.CLAUDE, exited());

                var notContinued = driver.awaitWarmContinuation(asker, "D-1", Duration.ZERO);
                driver.stub().event("continued", Map.of("decision", "D-1", "task", "T-2", "entity", "T-2"));
                var taken = driver.awaitWarmContinuation(asker, "D-1", Duration.ZERO);

                assertAll(
                        () -> assertEquals(WarmStep.NOT_CONTINUED, notContinued.step()),
                        () -> assertEquals(WarmStep.TAKEN, taken.step()),
                        () -> assertEquals(1, logged(driver, QuestionDriver.WARM_CONTINUATION).size()));
            });
        }

        @Test
        @DisplayName("no answer by the deadline, and no worker that failed, ends the wait for the answer empty")
        void noAnswer() throws Exception {
            run(driver -> assertAll(
                    () -> assertEquals(Optional.empty(), driver.awaitAnswer("D-1", Duration.ZERO, Duration.ZERO,
                            _ -> List.of())),
                    () -> assertEquals(Optional.empty(), driver.quotaError()),
                    () -> assertEquals(Optional.empty(), driver.launchFailure()),
                    () -> assertEquals(Optional.empty(), driver.earlyExit()),
                    () -> assertEquals(Optional.empty(), driver.awaitEventWhileLaunched(_ -> false, 0))));
        }

        @Test
        @DisplayName("an injected kill is logged and ends the process")
        void kill() throws Exception {
            run(driver -> {
                var worker = worker(Host.CLAUDE, sleeping());

                driver.inject(worker, Fault.KILL);
                worker.process().waitFor();

                assertAll(
                        () -> assertEquals(Fault.KILL.name(), logged(driver, QuestionDriver.FAULT_INJECTED).getFirst()
                                .path("fault").asText()),
                        () -> assertFalse(worker.process().isAlive()),
                        () -> assertEquals(Optional.empty(), driver.signalledPids(worker)));
            });
        }
    }

    @Nested
    @DisplayName("the readings of recorded logs: cuts, held continuations, recoveries, files being written, errors")
    class LogReadings {

        private static final String LOST = WORKER + "#1";

        private JsonNode relay(long at, String dir, String line) {
            return QuestionStub.JSON.createObjectNode().put("at", at).put("dir", dir).put("line", line);
        }

        private JsonNode withField(JsonNode event, String name, String value) {
            return ((ObjectNode) event).put(name, value);
        }

        private JsonNode delivered(String task, String worker, int generation) {
            return withField(event("answer_delivered", worker, generation, 5), "task", task);
        }

        @Test
        @DisplayName("a cut is the earliest sign after the held call's start: the host's cancel, else a stub event")
        void callCuts() {
            var waits = List.of(event("wait_started", "w-2", 1, 200), event("wait_started", WORKER, 2, 200),
                    event("wait_started", WORKER, 1, 50), event("wait_started", WORKER, 1, 200));
            var events = new ArrayList<>(waits);
            events.add(withField(event("wait_again", WORKER, 1, 250), "reason", "again"));
            events.add(withField(event("reply_failed", WORKER, 1, 260), "tool", "pm_ask"));
            events.add(event("progress", WORKER, 1, 270));
            events.add(event("disconnected", "w-2", 1, 280));
            events.add(event("disconnected", WORKER, 1, 450));
            var call = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"pm_wait\"}}";
            var cancel = "{\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":%d}}";
            var relay = List.of(relay(300, "to_host", call), relay(50, "from_host", call),
                    relay(300, "from_host", "not json"), relay(300, "from_host", "[1]"), relay(210, "from_host", call),
                    relay(210, "from_host", "{\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"pm_ask\"}}"),
                    relay(300, "from_host", cancel.formatted(9)), relay(150, "from_host", cancel.formatted(7)),
                    relay(400, "from_host", cancel.formatted(7)), relay(500, "from_host", cancel.formatted(7)));
            var superseded = new ArrayList<>(waits);
            superseded.add(withField(event("reply_failed", WORKER, 1, 250), "tool", "pm_wait"));
            superseded.add(withField(event("wait_again", WORKER, 1, 300), "reason", QuestionDriver.CUT_SUPERSEDED));
            var again = new ArrayList<>(waits);
            again.add(withField(event("wait_again", WORKER, 1, 300), "reason", QuestionDriver.CUT_SUPERSEDED));

            var cancelled = QuestionDriver.callCut(events, relay, WORKER, 1, 100).orElseThrow();
            var failed = QuestionDriver.callCut(superseded, List.of(), WORKER, 1, 100).orElseThrow();
            var newer = QuestionDriver.callCut(again, List.of(), WORKER, 1, 100).orElseThrow();

            assertAll(() -> assertEquals(QuestionDriver.CUT_CANCELLED, cancelled.evidence()),
                    () -> assertEquals(200, cancelled.afterMs()),
                    () -> assertEquals("reply_failed", failed.evidence()),
                    () -> assertEquals(QuestionDriver.CUT_SUPERSEDED, newer.evidence()),
                    () -> assertEquals(Optional.empty(), QuestionDriver.callCut(waits, List.of(), WORKER, 1, 100)),
                    () -> assertEquals(Optional.empty(), QuestionDriver.callCut(List.of(), List.of(), WORKER, 1, 0)));
        }

        @Test
        @DisplayName("a held continuation needs the ask, the continuation, its close and its offer over the wait")
        void heldContinuations() {
            var events = List.of(asked("D-0", WORKER, 2, 1), asked("D-1", WORKER, 2, 10),
                    continued("D-0", "T-0", 11), continued("D-1", "T-2", 12),
                    closed("T-9", WORKER, 2, 20), closed("T-2", "w-2", 3, 20), closed("T-2", WORKER, 1, 20),
                    closed("T-2", WORKER, 3, 30),
                    offered("T-9", WORKER, 3, 15), offered("T-2", "w-2", 3, 15), offered("T-2", WORKER, 2, 15),
                    ((ObjectNode) offered("T-2", WORKER, 3, 15)).put("at_spawn", true), offered("T-2", WORKER, 3, 16),
                    withField(event("submitted", WORKER, 3, 25), "task", "T-9"),
                    withField(event("submitted", "w-2", 3, 25), "task", "T-2"),
                    withField(event("submitted", WORKER, 2, 25), "task", "T-2"),
                    withField(event("submitted", WORKER, 3, 27), "task", "T-2"),
                    withField(withField(event("answer_recorded", WORKER, 3, 13), "decision", "D-0"), "channel", "x"),
                    withField(withField(event("answer_recorded", WORKER, 3, 14), "decision", "D-1"), "channel",
                            "quorum"));
            var withoutSubmission = List.of(asked("D-1", WORKER, 2, 10), continued("D-1", "T-2", 12),
                    closed("T-2", WORKER, 2, 30), offered("T-2", WORKER, 2, 16));

            var held = QuestionDriver.heldContinuation(events, "D-1").orElseThrow();
            var bare = QuestionDriver.heldContinuation(withoutSubmission, "D-1").orElseThrow();

            assertAll(() -> assertEquals(new QuestionDriver.HeldContinuation("D-1", "T-2", WORKER, 3, "submitted", 30,
                            27, "quorum"), held),
                    () -> assertEquals(-1, bare.submittedAt()),
                    () -> assertEquals("", bare.channel()),
                    () -> assertEquals(Optional.empty(), QuestionDriver.heldContinuation(List.of(), "D-1")),
                    () -> assertEquals(Optional.empty(), QuestionDriver.heldContinuation(
                            List.of(asked("D-1", WORKER, 2, 10)), "D-1")),
                    () -> assertEquals(Optional.empty(), QuestionDriver.heldContinuation(
                            List.of(asked("D-1", WORKER, 2, 10), continued("D-1", "T-2", 12)), "D-1")),
                    () -> assertEquals(Optional.empty(), QuestionDriver.heldContinuation(
                            List.of(asked("D-1", WORKER, 2, 10), continued("D-1", "T-2", 12),
                                    closed("T-2", WORKER, 2, 30)), "D-1")));
        }

        @Test
        @DisplayName("the recovery follows the continuation's state and holder, else the lost task's")
        void recoveries() {
            var continuation = List.of(continued("D-0", "T-0", 1), continued("D-1", "T-2", 2));
            var heldByOther = new ArrayList<>(continuation);
            heldByOther.add(offered("T-2", "w-2", 1, 3));
            var lostTask = List.of(continued("D-0", "T-0", 1), offered("T-1", "w-2", 1, 2),
                    withField(event("offered", WORKER, 1, 3), "task", "T-9"),
                    QuestionStub.JSON.createObjectNode().put("event", "offered").put("task", "T-1"));
            var lostHolder = List.<JsonNode>of(delivered("T-1", WORKER, 1));

            assertAll(
                    () -> assertEquals(RecoveryStep.TAKEN, step(continuation, "task.offered", _ -> true)),
                    () -> assertEquals(RecoveryStep.TAKEN, step(heldByOther, "task.completed", _ -> true)),
                    () -> assertEquals(RecoveryStep.TAKEN, step(heldByOther, "task.lost", _ -> true)),
                    () -> assertEquals(RecoveryStep.TAKEN, step(heldByOther, "task.offered", _ -> false)),
                    () -> assertEquals(RecoveryStep.CONTINUE, step(heldByOther, "task.offered", _ -> true)),
                    () -> assertEquals(RecoveryStep.GAP, step(heldByOther, "task.suspended", _ -> true)),
                    () -> assertEquals(RecoveryStep.RESUME, step(lostTask, "task.resumable", _ -> true)),
                    () -> assertEquals(RecoveryStep.TAKEN, step(lostTask, "task.offered", _ -> true)),
                    () -> assertEquals(RecoveryStep.WAIT, step(lostTask, "", _ -> true)),
                    () -> assertEquals(RecoveryStep.WAIT, step(lostTask, "task.queued", _ -> true)),
                    () -> assertEquals(RecoveryStep.WAIT, step(List.of(), "task.offered", _ -> true)),
                    () -> assertEquals(RecoveryStep.WAIT, step(lostHolder, "task.offered", _ -> true)));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(QuestionStub.AnswerResult.class)
        @DisplayName("an operator answer counts as answered only when the stub recorded it, never when it refused it")
        void answeredOnlyWhenRecorded(QuestionStub.AnswerResult result) {
            var expected = result == QuestionStub.AnswerResult.ANSWERED ? Optional.of("D-1") : Optional.<String>empty();

            var answered = QuestionDriver.answeredDecision("D-1", result);

            assertEquals(expected, answered);
        }

        private RecoveryStep step(List<JsonNode> events, String state, Predicate<String> ended) {
            return QuestionDriver.recovery(events, "D-1", "T-1", LOST, ended, _ -> Optional.of(state)).step();
        }

        @Test
        @DisplayName("a file being written ends its complete lines at a line feed or a carriage return")
        void writtenLines() throws Exception {
            var carriage = Files.writeString(directory.resolve("cr.txt"), "a\r", StandardCharsets.UTF_8);
            var trailingBlank = Files.writeString(directory.resolve("blank.txt"), "a\n \t", StandardCharsets.UTF_8);
            var blankLines = Files.writeString(directory.resolve("lines.jsonl"), "{\"a\":1}\n\n{\"b\":2}\n",
                    StandardCharsets.UTF_8);
            var corrupt = Files.writeString(directory.resolve("corrupt.jsonl"), "{\"a\":1}\nnot json\n{\"b\"",
                    StandardCharsets.UTF_8);

            assertAll(
                    () -> assertEquals(new QuestionDriver.WrittenLines(List.of("a"), false),
                            QuestionDriver.writtenLines(carriage)),
                    () -> assertEquals(new QuestionDriver.WrittenLines(List.of("a"), false),
                            QuestionDriver.writtenLines(trailingBlank)),
                    () -> assertEquals(2, QuestionDriver.readJsonLinesSoFar(blankLines).nodes().size()),
                    () -> assertThrows(IOException.class, () -> QuestionDriver.readJsonLinesSoFar(corrupt)));
        }

        @Test
        @DisplayName("the error text of a harness's error line, and nothing for any other line")
        void harnessErrorTexts() {
            assertAll(
                    () -> assertEquals(Optional.empty(), QuestionDriver.harnessErrorText("not json")),
                    () -> assertEquals(Optional.empty(), QuestionDriver.harnessErrorText("[1]")),
                    () -> assertEquals(Optional.empty(), QuestionDriver.harnessErrorText("")),
                    () -> assertEquals(Optional.of("{\"code\":1}\nquota hit\nr"), QuestionDriver.harnessErrorText(
                            "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"errors\":[{\"code\":1},"
                                    + "\"quota hit\"],\"result\":\"r\",\"error\":5}")),
                    () -> assertEquals(Optional.of(""), QuestionDriver.harnessErrorText(
                            "{\"type\":\"result\",\"is_error\":true}")),
                    () -> assertEquals(Optional.empty(), QuestionDriver.harnessErrorText(
                            "{\"type\":\"result\",\"subtype\":\"success\"}")),
                    () -> assertEquals(Optional.empty(), QuestionDriver.harnessErrorText("{\"type\":\"error\"}")),
                    () -> assertEquals(Optional.of("{\"message\":\"m\"}"), QuestionDriver.harnessErrorText(
                            "{\"type\":\"error\",\"error\":{\"message\":\"m\"}}")),
                    () -> assertEquals(Optional.of("text"), QuestionDriver.harnessErrorText(
                            "{\"type\":\"error\",\"error\":\"text\"}")),
                    () -> assertEquals(Optional.of("e"), QuestionDriver.harnessErrorText(
                            "{\"conversation_id\":\"c\",\"error\":\"e\"}")),
                    () -> assertEquals(Optional.empty(), QuestionDriver.harnessErrorText(
                            "{\"conversation_id\":\"c\",\"error\":{}}")),
                    () -> assertEquals(Optional.empty(), QuestionDriver.harnessErrorText(
                            "{\"conversation_id\":1,\"error\":\"e\"}")));
        }

        @Test
        @DisplayName("the first rate limit event with a refusing status is the refusal")
        void rateLimitRefusals() {
            var outputs = List.of(stamped("not json"), stamped(""), stamped("[1]"),
                    stamped("{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed\"}}"),
                    stamped("{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":1}}"),
                    stamped("{\"type\":\"rate_limit_event\",\"rate_limit_info\":{}}"),
                    stamped("{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"rejected\","
                            + "\"rateLimitType\":\"five_hour\",\"resetsAt\":1700000000}}"));

            var refusal = QuestionDriver.rateLimitRefusal(outputs).orElseThrow();

            assertAll(() -> assertEquals("2023-11-14T22:13:20Z", refusal.resetsAt()),
                    () -> assertEquals(Optional.empty(), QuestionDriver.rateLimitRefusal(outputs.subList(0, 6))));
        }

        @Test
        @DisplayName("pm-exec's own lines are told apart from the harness's, and blank stderr lines are no cause")
        void pmExecLines() {
            assertAll(() -> assertFalse(QuestionDriver.pmExecLine("{\"pm_exec\":1}")),
                    () -> assertFalse(QuestionDriver.pmExecLine("")),
                    () -> assertFalse(QuestionDriver.pmExecLine("[1]")),
                    () -> assertEquals("cause", QuestionDriver.launchFailure(WORKER, 1, 1, " \ncause\n").stderr()));
        }

        @Test
        @DisplayName("an opencode.json carries the tool timeout of a launch that has one, and only PM_Q_ variables")
        void opencodeConfig() throws Exception {
            var timed = new Launch(WORKER, 1, "worker", "p", false, Duration.ofSeconds(90), List.of("bash"), null);
            var untimed = new Launch(WORKER, 1, "worker", "p", false, null, List.of(), null);
            var environment = Map.of("PM_Q_SOCKET", "/s", "HOME", "/h");

            var withTimeout = json(QuestionDriver.opencodeConfig(List.of("java", "-cp", "x"), timed, environment));
            var withoutTimeout = json(QuestionDriver.opencodeConfig(List.of("java"), untimed, environment));

            var server = withTimeout.path("mcp").path(QuestionStub.SERVER);
            assertAll(() -> assertEquals(90_000, server.path("timeout").asLong()),
                    () -> assertEquals(1, server.path("environment").size()),
                    () -> assertTrue(server.path("environment").has("PM_Q_SOCKET")),
                    () -> assertFalse(withoutTimeout.path("mcp").path(QuestionStub.SERVER).has("timeout")));
        }

        @Test
        @DisplayName("a worker's OpenCode data home gets the operator's login once, and none without a login")
        void opencodeDataHome() throws Exception {
            var home = Files.createDirectories(directory.resolve("home"));
            var login = Files.createDirectories(home.resolve(".local/share/opencode")).resolve("auth.json");
            Files.writeString(login, "{\"token\":\"t\"}", StandardCharsets.UTF_8);
            var previous = System.getProperty("user.home");
            try {
                System.setProperty("user.home", home.toString());
                var dataHome = QuestionDriver.opencodeDataHome(directory.resolve("w1"));
                Files.writeString(dataHome.resolve("opencode/auth.json"), "kept", StandardCharsets.UTF_8);
                QuestionDriver.opencodeDataHome(directory.resolve("w1"));
                System.setProperty("user.home", directory.resolve("empty").toString());
                var without = QuestionDriver.opencodeDataHome(directory.resolve("w2"));

                assertAll(() -> assertEquals("kept", Files.readString(dataHome.resolve("opencode/auth.json"),
                                StandardCharsets.UTF_8)),
                        () -> assertFalse(Files.exists(without.resolve("opencode/auth.json"))));
            } finally {
                System.setProperty("user.home", previous);
            }
        }
    }

    @Nested
    @DisplayName("launches through a stand-in pm-exec that runs no harness")
    class Launches {

        @ParameterizedTest
        @EnumSource(Host.class)
        @DisplayName("a newer generation ends the older one, and the recovery reads the driver's ended generations")
        void launchesAndEndsOlderGeneration(Host host) throws Exception {
            var exec = executable(directory.resolve("bin"), "pm-exec");
            Files.writeString(exec, "#!/bin/sh\necho '{\"type\":\"system\"}'\nexec sleep 30\n", StandardCharsets.UTF_8);
            var harness = executable(directory.resolve("bin"), host.executable());
            var property = Host.EXECUTABLE_PROPERTY + host.executable();
            var bytes = new byte[4];
            new SecureRandom().nextBytes(bytes);
            var socket = Path.of("/tmp", "pmq-lch-" + HexFormat.of().formatHex(bytes) + ".sock");
            var settings = new Settings(host, "m", directory.resolve("workspace"), directory.resolve("run"),
                    List.of(exec.toString()), QuestionDriver.OpenCodeDataHome.GLOBAL, 1);
            System.setProperty(property, harness.toString());
            try (var driver = QuestionDriver.start(QuestionStub.Settings.of(directory.resolve("stub"), socket),
                         settings)) {
                var first = driver.startWarm(new Launch(WORKER, 1, "implementer", "p", false,
                        Duration.ofSeconds(90), List.of("Read"), null));
                var second = driver.startWarm(new Launch(WORKER, 2, "implementer", "p", false, null, List.of(),
                        "session-1"));
                driver.stub().event("continued", Map.of("decision", "D-1", "task", "T-2", "entity", "T-2"));
                driver.stub().event("offered", Map.of("task", "T-2", "worker", WORKER, "generation", 2));
                var whileRunning = driver.recovery(first, "D-1", "T-1");
                driver.terminate(second);
                var afterEnd = driver.recovery(first, "D-1", "T-1");
                driver.sibling(Host.CLAUDE, null);

                var superseded = QuestionDriver.jsonLinesSoFar(driver.stub().events()).stream()
                        .filter(event -> QuestionDriver.SUPERSEDED_ENDED.equals(event.path("event").asText()))
                        .toList();
                assertAll(() -> assertFalse(first.process().isAlive()),
                        () -> assertFalse(second.process().isAlive()),
                        () -> assertEquals(2, driver.workers().size()),
                        () -> assertEquals(2, superseded.getFirst().path("by").asInt()),
                        () -> assertTrue(Files.isRegularFile(host.mcpConfig(first.directory()))),
                        () -> assertEquals(RecoveryStep.TAKEN, whileRunning.step()),
                        () -> assertEquals(RecoveryStep.GAP, afterEnd.step()),
                        () -> assertDoesNotThrow(() -> driver.earlyExit()),
                        () -> assertDoesNotThrow(() -> driver.voterEarlyExits()),
                        () -> assertDoesNotThrow(() -> driver.quotaError()),
                        () -> assertDoesNotThrow(() -> driver.launchFailure()));
            } finally {
                System.clearProperty(property);
                Files.deleteIfExists(socket);
            }
        }
    }

    @FunctionalInterface
    private interface DriverAction {

        void run(QuestionDriver driver) throws Exception;
    }

    private Path executable(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        var file = Files.writeString(dir.resolve(name), "#!/bin/sh\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    private static JsonNode event(String name, String worker, int generation, long at) {
        var node = QuestionStub.JSON.createObjectNode();
        node.put("at", at);
        node.put("event", name);
        node.put("worker", worker);
        node.put("generation", generation);
        return node;
    }

    private static JsonNode offered(String task, String worker, int generation, long at) {
        var node = (ObjectNode) event("offered", worker, generation, at);
        node.put("task", task);
        return node;
    }

    private static JsonNode closed(String task, String worker, int generation, long at) {
        var node = (ObjectNode) event("closed", worker, generation, at);
        node.put("task", task);
        node.put("outcome", "submitted");
        return node;
    }

    private static JsonNode asked(String decision, String worker, int generation, long at) {
        var node = (ObjectNode) event(QuestionDriver.ASKED, worker, generation,
                at);
        node.put("decision", decision);
        return node;
    }

    private static JsonNode continued(String decision, String task, long at) {
        var node = QuestionStub.JSON.createObjectNode();
        node.put("at", at);
        node.put("event", "continued");
        node.put("decision", decision);
        node.put("task", task);
        node.put("entity", task);
        return node;
    }

    private static JsonNode stamped(String line) {
        return QuestionStub.JSON.createObjectNode().put("at", 1).put("line", line);
    }

    private static JsonNode json(String text) {
        try {
            return QuestionStub.JSON.readTree(text);
        } catch (IOException e) {
            throw new IllegalArgumentException(text, e);
        }
    }
}
