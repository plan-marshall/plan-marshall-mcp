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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;


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
