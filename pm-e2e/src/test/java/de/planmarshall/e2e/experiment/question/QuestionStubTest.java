/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.e2e.experiment.question;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.experiment.question.QuestionDriver.EarlyExit;
import de.planmarshall.e2e.experiment.question.QuestionDriver.Fault;
import de.planmarshall.e2e.experiment.question.QuestionDriver.Host;
import de.planmarshall.e2e.experiment.question.QuestionDriver.Launch;
import de.planmarshall.e2e.experiment.question.QuestionDriver.LaunchFailure;
import de.planmarshall.e2e.experiment.question.QuestionDriver.OpenCodeDataHome;
import de.planmarshall.e2e.experiment.question.QuestionDriver.QuotaError;
import de.planmarshall.e2e.experiment.question.QuestionDriver.Worker;
import de.planmarshall.e2e.experiment.question.QuestionStub.AnswerResult;
import de.planmarshall.e2e.experiment.question.QuestionStub.Form;
import de.planmarshall.e2e.experiment.question.QuestionStub.PartAnswer;
import de.planmarshall.e2e.experiment.question.QuestionStub.Settings;
import de.planmarshall.e2e.experiment.question.QuestionStub.TaskSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The stub of the question loop without a model (experiment only): a client speaks to it over its socket as a relay
 * does, with the worker's identity in the first line.
 * <p>
 * Hazards it guards, each a premise of the measurement: an ask whose envelope the server would refuse being recorded
 * anyway, so Q1 counted asks no server takes; a lease of a fenced generation staying with it, so a replaced worker's
 * task is never offered again (PM-WORK-2); an open question record lost with a stub restart, or its answer delivered
 * twice, so Q7 measured the stub instead of the loop (PM-WF-12); a continuing task without {@code facts.answers}, so
 * Q4 measured a worker that never saw its answer; and an ask link offered beyond the question bound, so Q5 never
 * reached the bound. Beside the stub: a scenario recipe read into a fixture with an invented check, or another recipe
 * than the two large ones used, so R4 measured work nobody chose; a checkout that changes the local clone; and
 * Antigravity's tokens read as 0 or its missing-session resume, which starts a new conversation, counted as hung or as
 * failed. Beside them the launch: Claude Code or OpenCode handed to {@code pm-exec} by a bare name, which it refuses;
 * a worker that fails to launch noticed only at the run's deadline, or one killed by an injected fault, or one whose
 * relay connected, counted as a launch failure; a launch failure that records {@code pm-exec}'s {@code launched} line
 * instead of the harness's own cause; OpenCode launched with a flag it does not have ({@code --dir}), without
 * {@code --standalone}, or with a relay that lacks its identity; a missing OpenCode session named in a form OpenCode
 * refuses at once; a result that mixes the records of earlier passes unseen, or reports a smaller pass as one at the
 * specified scale; and the default telemetry checkout and scenario clones resolved below
 * a worktree instead of beside the main checkout. Beside them the ask's own text: a contract that never states the
 * evidence rule the stub enforces, so first asks are refused; a forced fixture whose facts claim the operator has
 * already answered, so the worker submits without asking; a Q2 control that is forced too; OpenCode on a data home
 * without the operator's login, or its runs in parallel on the one global database; OpenCode's missing-session resume,
 * which starts a session under the named id, counted as an exit without failure; and a result that does not record the
 * fixture wording or the OpenCode departure. Beside them Q3's figures: a silence-limit wait reported as the time to the
 * end of the turn instead of the time to the next {@code pm_wait}, and a harness's quota error waited out and counted
 * as a timeout or an early exit. The recipes, corpus items, repositories and executables of these
 * tests ({@code pm-exec} and the harness are shell scripts) are written by the tests; no network, model, harness or
 * real repository is used.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("QuestionStub: the question loop of a worker without a model")
class QuestionStubTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ROLE = "implement";
    private static final String TASK = "T-1";
    private static final String WORKER = "w1";
    private static final String LINKS_EMPTY = "links: []";

    @TempDir
    Path directory;

    private final List<Client> clients = new ArrayList<>();
    private Path socket;
    private QuestionStub stub;

    @BeforeEach
    void socket() {
        var bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        socket = Path.of("/tmp", "pmq-test-" + HexFormat.of().formatHex(bytes) + ".sock");
    }

    @AfterEach
    void stop() throws IOException {
        for (var client : clients) {
            client.close();
        }
        if (stub != null) {
            stub.close();
        }
    }

    @Nested
    @DisplayName("ask")
    class Ask {

        @Test
        @DisplayName("records a valid envelope, ends the task asked with no link, and delivers the decision")
        void shouldRecordValidEnvelope() throws Exception {
            stub = start(1);
            var client = activeWorker(Form.FALLBACK);

            var asked = client.ask("ask", validEnvelope());

            assertAll("the asked representation",
                    () -> assertTrue(asked.contains("state: task.asked"), asked),
                    () -> assertTrue(asked.contains(LINKS_EMPTY), asked),
                    () -> assertEquals("Q-001", stub.operatorDecisions().peek()),
                    () -> assertTrue(Files.readString(stub.records()).contains("\"line\":\"asked\"")));
        }

        @Test
        @DisplayName("refuses an envelope with one option and no recommendation with invalid_link_args")
        void shouldRefuseInvalidEnvelope() throws Exception {
            stub = start(1);
            var client = activeWorker(Form.FALLBACK);
            var envelope = validEnvelope();
            var part = (ObjectNode) envelope.path("parts").get(0);
            ((ArrayNode) part.get("options")).remove(1);
            part.putNull("recommendation");

            var refused = client.ask("ask", envelope);

            assertAll("the refusal",
                    () -> assertTrue(refused.contains("reason: invalid_link_args"), refused),
                    () -> assertTrue(refused.contains("two to five are required"), refused),
                    () -> assertTrue(refused.contains("exactly one recommended option is required"), refused),
                    () -> assertTrue(refused.contains("state: entity.active"), refused),
                    () -> assertTrue(stub.operatorDecisions().isEmpty()),
                    () -> assertFalse(Files.exists(stub.records())));
        }

        @Test
        @DisplayName("refuses an envelope that carries a server-owned field")
        void shouldRefuseServerOwnedField() throws Exception {
            stub = start(1);
            var client = activeWorker(Form.FALLBACK);
            var envelope = validEnvelope();
            ((ObjectNode) envelope.path("parts").get(0)).put("question_id", "Q-999");

            var refused = client.ask("ask", envelope);

            assertTrue(refused.contains("question_id: a server-owned field"), refused);
        }

        @Test
        @DisplayName("refuses evidence that is no pm:// URI, which the contract's evidence rule forbids")
        void shouldRefuseEvidenceWithoutPmUri() throws Exception {
            stub = start(1);
            var client = activeWorker(Form.FALLBACK);
            var envelope = validEnvelope();
            envelope.putArray("evidence").add("solution.py");

            var refused = client.ask("ask", envelope);

            assertTrue(refused.contains("evidence: every item must be a pm:// URI"), refused);
        }
    }

    @Nested
    @DisplayName("contract")
    class Contract {

        @Test
        @DisplayName("states the evidence rule, and its example carries one pm:// evidence item the stub accepts")
        void shouldStateEvidenceRule() throws Exception {
            var contract = QuestionDriver.contract(ROLE, "");
            var errors = new ArrayList<String>();

            QuestionStub.validateDecision(validEnvelope(), errors);

            var evidence = validEnvelope().path("evidence");
            assertAll("the contract",
                    () -> assertTrue(contract.contains(QuestionDriver.EVIDENCE_RULE), contract),
                    () -> assertTrue(contract.contains("pm:// URIs and nothing else"), contract),
                    () -> assertTrue(contract.contains("\"evidence\":[\"pm://qlv/T-1\"]"), contract),
                    () -> assertEquals(1, evidence.size()),
                    () -> assertTrue(evidence.get(0).asText().startsWith("pm://"), evidence.toString()),
                    () -> assertEquals(List.of(), errors));
        }
    }

    @Nested
    @DisplayName("fixture wording")
    class FixtureWording {

        private static final String QUESTION = "What does candidate([1.0, 2.0], 0.5) return?";

        @Test
        @DisplayName("a forced apply-answer fixture forces the ask and never claims that the operator answered")
        void shouldForceAskWithoutClaimedAnswer() throws Exception {
            var cache = cache();

            var fixture = QuestionFixtures.applyAnswer(cache).getFirst();
            var facts = QuestionFixtures.entityTask(fixture, TASK, ROLE, Form.FALLBACK).facts();

            assertAll("the forced fixture",
                    () -> assertTrue(fixture.task().contains(QuestionFixtures.FORCE_ASK), fixture.task()),
                    () -> assertTrue(fixture.task().contains("operator's choice, which you do not know"),
                            fixture.task()),
                    () -> assertFalse(fixture.task().contains("Operator's answer"), fixture.task()),
                    () -> assertEquals(QuestionFixtures.CHECK_AFTER_ANSWER + QUESTION,
                            facts.path("check_question").asText()),
                    () -> assertFalse(facts.toString().contains("the operator answered"), facts.toString()),
                    () -> assertEquals("Closer.", fixture.reply()),
                    () -> assertEquals("False", fixture.check().reference()));
        }

        @Test
        @DisplayName("the envelope's TASK-001 forces the ask and no longer says the operator settled the contract")
        void shouldForceAskInEnvelope() throws Exception {
            var fixture = QuestionFixtures.applyAnswer(cache()).getFirst();

            var spec = QuestionFixtures.envelopeTask(fixture, TASK, ROLE, Form.FALLBACK);

            var implement = spec.planTasks().getFirst().text();
            assertAll("the envelope",
                    () -> assertEquals(QuestionFixtures.FORCE_ASK_PLAN_TASK, implement),
                    () -> assertFalse(implement.contains("settled by the operator"), implement),
                    () -> assertTrue(spec.facts().path("contract").asText().contains(QuestionFixtures.FORCE_ASK)),
                    () -> assertFalse(spec.facts().toString().contains("the operator answered"),
                            spec.facts().toString()));
        }

        @Test
        @DisplayName("a Q2 control keeps its original, unforced task text")
        void shouldKeepControlUnforced() throws Exception {
            var cache = cache();

            var control = QuestionFixtures.controls(cache).getFirst();

            assertAll("the control",
                    () -> assertTrue(control.control()),
                    () -> assertEquals("Implement the Python function below as its docstring describes, in the "
                            + "file solution.py of your working directory.\n\ndef candidate(): pass", control.task()),
                    () -> assertFalse(control.task().contains("ask"), control.task()));
        }

        @Test
        @DisplayName("a two-flaw fixture of Q5 forces an ask per flaw")
        void shouldForceAskPerFlaw() throws Exception {
            var fixture = QuestionFixtures.twoFlaws(cache()).getFirst();

            assertAll("the two-flaw fixture",
                    () -> assertEquals("2ac", fixture.category()),
                    () -> assertTrue(fixture.task().contains(QuestionFixtures.FORCE_ASK_EACH), fixture.task()));
        }

        @Test
        @DisplayName("stops when the closed question lacks the answered framing it replaces")
        void shouldStopWithoutAnsweredFraming() {
            var stop = assertThrows(IllegalStateException.class,
                    () -> QuestionFixtures.checkAfterAnswer(QUESTION, "he-x"));

            assertTrue(stop.getMessage().contains("he-x"), stop.getMessage());
        }

        private Path cache() throws IOException {
            var cache = directory.resolve("cache");
            var apply = QuestionStub.JSON.createObjectNode();
            apply.put("id", "question/apply-answer/he-x");
            apply.put("title", "HumanEval/0 (has_close_elements), variant 1a: apply the operator's answer");
            apply.put("group", "humaneval-x");
            var input = apply.putObject("input");
            input.put("question", QuestionFixtures.ANSWERED_FRAMING + QUESTION);
            input.put("facts", "def candidate(numbers, threshold): pass" + QuestionFixtures.REPLY_MARKER + "Closer.");
            input.putArray("choices").add("False").add("True");
            apply.putObject("target").put("answer", "False");
            writeItem(cache, "question/apply-answer", "he-x", apply);
            var original = QuestionStub.JSON.createObjectNode();
            original.put("id", "question/ask-or-proceed/he-x-original");
            original.put("group", "humaneval-x");
            original.putObject("input").put("facts", "def candidate(): pass");
            writeItem(cache, "question/ask-or-proceed", "he-x-original", original);
            var twoFlaws = QuestionStub.JSON.createObjectNode();
            twoFlaws.put("id", "question/ask-or-proceed/he-x-2ac");
            twoFlaws.put("group", "humaneval-x");
            twoFlaws.putArray("tags").add("category:2ac");
            twoFlaws.putObject("input").put("facts", "def candidate(): pass");
            writeItem(cache, "question/ask-or-proceed", "he-x-2ac", twoFlaws);
            return cache;
        }

        private static void writeItem(Path cache, String topic, String name, JsonNode item) throws IOException {
            var file = cache.resolve("items").resolve(topic).resolve(name + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, QuestionStub.JSON.writeValueAsString(item));
        }
    }

    @Nested
    @DisplayName("continuation")
    class Continuation {

        @Test
        @DisplayName("holds: the answer arrives through pm_wait with ack as its only link, then the task is active")
        void shouldDeliverHeldAnswerThroughWait() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            var client = activeWorker(Form.HOLD);
            var asked = client.ask("ask", validEnvelope());

            var answered = stub.answer("Q-001", 1, List.of(answer("Q-001")));
            var delivered = client.call("pm_wait", QuestionStub.JSON.createObjectNode());
            var acknowledged = client.link("ack", QuestionStub.JSON.createObjectNode());

            assertAll("the held continuation",
                    () -> assertTrue(asked.contains("state: entity.awaiting_answer"), asked),
                    () -> assertEquals(AnswerResult.ANSWERED, answered),
                    () -> assertTrue(delivered.contains("state: task.answer"), delivered),
                    () -> assertTrue(delivered.contains("answers[1]{question_id,plan_task_id,question_text,"
                            + "branch_key,label,text,kind}:"), delivered),
                    () -> assertTrue(delivered.contains("links[1]{rel,link,form,free}:"), delivered),
                    () -> assertTrue(acknowledged.contains("state: entity.active"), acknowledged),
                    () -> assertEquals(AnswerResult.ALREADY_ANSWERED,
                            stub.answer("Q-001", 1, List.of(answer("Q-001")))));
        }

        @Test
        @DisplayName("falls back: the answer reaches a new task of the role in facts.answers")
        void shouldCarryAnswerIntoNewTask() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            var client = activeWorker(Form.FALLBACK);
            client.ask("ask", validEnvelope());

            stub.answer("Q-001", 1, List.of(answer("Q-001")));
            var offered = client.call("pm_wait", QuestionStub.JSON.createObjectNode());
            var active = client.link("ack", QuestionStub.JSON.createObjectNode());

            assertAll("the continuing task",
                    () -> assertTrue(offered.contains("state: task.offered"), offered),
                    () -> assertTrue(active.contains("Q-001,null,"), active),
                    () -> assertTrue(active.contains(",description,Follow the description,"), active),
                    () -> assertTrue(stub.taskIds().contains("T-1-Q-001"), stub.taskIds().toString()));
        }

        @Test
        @DisplayName("withdraws the ask link once the question bound is reached")
        void shouldWithdrawAskLinkAtBound() throws Exception {
            stub = start(1);
            var client = activeWorker(Form.FALLBACK);
            var beforeBound = client.state();
            client.ask("ask", validEnvelope());
            stub.answer("Q-001", 1, List.of(answer("Q-001")));
            client.call("pm_wait", QuestionStub.JSON.createObjectNode());

            var atBound = client.link("ack", QuestionStub.JSON.createObjectNode());

            assertAll("the bound",
                    () -> assertTrue(beforeBound.contains("submit,ask,submit,"), beforeBound),
                    () -> assertFalse(atBound.contains("submit,ask,submit,"), atBound),
                    () -> assertTrue(atBound.contains("submit,submit,submit,"), atBound));
        }
    }

    @Nested
    @DisplayName("leases")
    class Leases {

        @Test
        @DisplayName("a newer generation fences the older: its calls are refused and its task is offered anew")
        void shouldFenceOlderGeneration() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            stub.queue(task(Form.FALLBACK));
            var older = Client.connect(socket, WORKER, 1);
            clients.add(older);
            var offered = older.call("pm_wait", QuestionStub.JSON.createObjectNode());
            try (var newer = Client.connect(socket, WORKER, 2)) {
                newer.initialize();

                var fenced = older.link("ack", QuestionStub.JSON.createObjectNode());
                var reoffered = newer.call("pm_wait", QuestionStub.JSON.createObjectNode());

                assertAll("the fence",
                        () -> assertTrue(offered.contains("state: task.offered"), offered),
                        () -> assertTrue(fenced.contains("error: generation_fenced"), fenced),
                        () -> assertTrue(reoffered.contains("state: task.offered"), reoffered),
                        () -> assertTrue(reoffered.contains("entity: T-1"), reoffered));
            }
        }
    }

    @Nested
    @DisplayName("restart")
    class Restart {

        @Test
        @DisplayName("an open record survives a stub restart, and its answer continues exactly once")
        void shouldKeepOpenRecordAcrossRestart() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            activeWorker(Form.FALLBACK).ask("ask", validEnvelope());
            stub.close();

            stub = start(QuestionStub.QUESTION_BOUND);
            var reannounced = stub.operatorDecisions().peek();
            var answered = stub.answer("Q-001", 1, List.of(answer("Q-001")));
            var continued = stub.taskIds();
            stub.close();
            stub = start(QuestionStub.QUESTION_BOUND);
            var replayed = stub.taskIds();
            var lines = Files.readAllLines(stub.records()).stream()
                    .filter(line -> line.contains("\"line\":\"continued\"")).count();

            assertAll("the restarts",
                    () -> assertEquals("Q-001", reannounced),
                    () -> assertEquals(AnswerResult.ANSWERED, answered),
                    () -> assertEquals(List.of("T-1-Q-001"), continued),
                    () -> assertEquals(List.of(), replayed),
                    () -> assertEquals(1, lines),
                    () -> assertEquals(AnswerResult.ALREADY_ANSWERED,
                            stub.answer("Q-001", 1, List.of(answer("Q-001")))));
        }
    }

    @Nested
    @DisplayName("Antigravity launch")
    class AntigravityLaunch {

        @Test
        @DisplayName("a new session without a model drops --model and --conversation with their placeholders")
        void shouldDropFlagsWithoutValue() {
            var values = new LinkedHashMap<String, String>();
            values.put("{prompt}", "Do it.");
            values.put("{model}", null);
            values.put("{session_id}", null);

            var command = QuestionDriver.expand(QuestionDriver.AGY_ARGV, values);

            assertEquals(List.of("agy", "--print", "Do it.", "--output-format", "json",
                    "--dangerously-skip-permissions"), command);
        }

        @Test
        @DisplayName("a resume with a model names both")
        void shouldKeepFlagsWithValue() {
            var values = new LinkedHashMap<String, String>();
            values.put("{prompt}", "Continue.");
            values.put("{model}", "gemini-3.8-flash-low");
            values.put("{session_id}", "e8e99133-b297-4498-8d6f-e47d68f4b624");

            var command = QuestionDriver.expand(QuestionDriver.AGY_ARGV, values);

            assertEquals(List.of("agy", "--print", "Continue.", "--output-format", "json",
                    "--dangerously-skip-permissions", "--model", "gemini-3.8-flash-low", "--conversation",
                    "e8e99133-b297-4498-8d6f-e47d68f4b624"), command);
        }
    }

    @Nested
    @DisplayName("OpenCode launch")
    class OpenCodeLaunch {

        private static final String OPENCODE = "/opt/homebrew/bin/opencode";

        @Test
        @DisplayName("a resume with a model runs standalone as pull-worker with --model and --session")
        void shouldNameModelAndSession() throws Exception {
            var command = QuestionDriver.opencodeCommand("Continue.", "opencode/deepseek-v4.1-flash-free",
                    "ses_ea0479469ffez5MmZvmjJU2bd5", OPENCODE);

            assertEquals(List.of(OPENCODE, "run", "--standalone", "--format", "json", "--agent", "pull-worker",
                    "--model", "opencode/deepseek-v4.1-flash-free", "--session", "ses_ea0479469ffez5MmZvmjJU2bd5",
                    "Continue."), command);
        }

        @Test
        @DisplayName("a new session on the harness default drops --model and --session, and has no --dir")
        void shouldDropFlagsWithoutValue() throws Exception {
            var command = QuestionDriver.opencodeCommand("Do it.", null, null, OPENCODE);

            assertEquals(List.of(OPENCODE, "run", "--standalone", "--format", "json", "--agent", "pull-worker",
                    "Do it."), command);
        }

        @Test
        @DisplayName("opencode.json gives the relay its identity and the agent execute and the stub's tools only")
        void shouldWriteRelayIdentityAndAgent() throws Exception {
            var environment = new LinkedHashMap<String, String>();
            environment.put("PATH", "/usr/bin");
            environment.putAll(QuestionStub.relayEnvironment(socket, WORKER, 2, ROLE, "opencode",
                    directory.resolve("relay.jsonl")));
            var launch = new Launch(WORKER, 2, ROLE, "Do it.", true, null, List.of("read"), null);

            var config = QuestionStub.JSON.readTree(QuestionDriver.opencodeConfig(List.of("java", "Relay"), launch,
                    environment));

            var server = config.path("mcp").path(QuestionStub.SERVER);
            var tools = config.path("agent").path(QuestionDriver.OPENCODE_AGENT).path("tools");
            assertAll("the configuration",
                    () -> assertEquals("2", server.path("environment").path("PM_Q_GENERATION").asText()),
                    () -> assertTrue(server.path("environment").path("PATH").isMissingNode(), server.toString()),
                    () -> assertFalse(tools.path("*").asBoolean(true)),
                    () -> assertTrue(tools.path(QuestionDriver.OPENCODE_CODE_TOOL).asBoolean()),
                    () -> assertTrue(tools.path(QuestionStub.SERVER + "*").asBoolean()),
                    () -> assertTrue(tools.path("read").asBoolean()),
                    () -> assertTrue(tools.path(Host.OPENCODE.questionTool()).isMissingNode(), tools.toString()));
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(nullValues = "none", value = {"none, GLOBAL", "global, GLOBAL", "Worker, WORKER"})
        @DisplayName("pm.experiment.opencode.data-home selects the data home, global by default")
        void shouldSelectDataHome(String value, OpenCodeDataHome expected) {
            assertEquals(expected, OpenCodeDataHome.of(value));
        }

        @Test
        @DisplayName("fails an unknown data home with the values it takes")
        void shouldRefuseUnknownDataHome() {
            var refused = assertThrows(IllegalArgumentException.class, () -> OpenCodeDataHome.of("shared"));

            assertTrue(refused.getMessage().contains("-Dpm.experiment.opencode.data-home=shared: global or worker"),
                    refused.getMessage());
        }

        @Test
        @DisplayName("the global data home keeps the inherited XDG_DATA_HOME; the worker's sets its own")
        void shouldApplyDataHome() throws Exception {
            var global = new LinkedHashMap<String, String>();
            global.put("XDG_DATA_HOME", "/home/operator/.local/share");
            var worker = new LinkedHashMap<String, String>();
            var workerDirectory = directory.resolve("w1");

            QuestionDriver.applyOpenCodeDataHome(global, OpenCodeDataHome.GLOBAL, workerDirectory);
            QuestionDriver.applyOpenCodeDataHome(worker, OpenCodeDataHome.WORKER, workerDirectory);

            assertAll("the data homes",
                    () -> assertEquals(Map.of("XDG_DATA_HOME", "/home/operator/.local/share"), global),
                    () -> assertEquals(workerDirectory.resolve("data").toString(), worker.get("XDG_DATA_HOME")),
                    () -> assertEquals(Path.of("/home/operator/.local/share/opencode"),
                            QuestionDriver.globalOpencodeData(global)));
        }

        @Test
        @DisplayName("defaults to opencode/gpt-5.4-nano on the global data home, the harness default on the worker's")
        void shouldDefaultModelPerDataHome() {
            assertAll("the default models",
                    () -> assertEquals("opencode/gpt-5.4-nano",
                            QuestionLoopExperiment.defaultModel(Host.OPENCODE, OpenCodeDataHome.GLOBAL)),
                    () -> assertNull(QuestionLoopExperiment.defaultModel(Host.OPENCODE, OpenCodeDataHome.WORKER)),
                    () -> assertEquals("claude-haiku-4-5",
                            QuestionLoopExperiment.defaultModel(Host.CLAUDE, OpenCodeDataHome.GLOBAL)));
        }

        @Test
        @DisplayName("runs that start OpenCode on the global data home run one at a time, and name their models")
        void shouldSerializeOpenCodeRuns() {
            var fixture = QuestionFixtures.probe();
            var asker = QuestionLoopExperiment.RunPlan.of("Q1", 0, Host.OPENCODE, "opencode/gpt-5.4-nano", false,
                    Form.FALLBACK, fixture, "entity");
            var continuation = QuestionLoopExperiment.RunPlan.of("Q4", 1, Host.CLAUDE, "claude-haiku-4-5", false,
                    Form.FALLBACK, fixture, "entity").continuingOn(Host.OPENCODE);
            var claudeOnly = QuestionLoopExperiment.RunPlan.of("Q1", 0, Host.CLAUDE, "claude-haiku-4-5", false,
                    Form.FALLBACK, fixture, "entity");
            var global = config(OpenCodeDataHome.GLOBAL);

            assertAll("the OpenCode runs",
                    () -> assertTrue(QuestionLoopExperiment.usesOpenCode(asker)),
                    () -> assertTrue(QuestionLoopExperiment.usesOpenCode(continuation)),
                    () -> assertFalse(QuestionLoopExperiment.usesOpenCode(claudeOnly)),
                    () -> assertTrue(QuestionLoopExperiment.serialOpenCode(global)),
                    () -> assertFalse(QuestionLoopExperiment.serialOpenCode(config(OpenCodeDataHome.WORKER))),
                    () -> assertEquals(Set.of("opencode/gpt-5.4-nano"),
                            QuestionLoopExperiment.openCodeModels(List.of(asker, continuation, claudeOnly), global)),
                    () -> assertEquals(Set.of(), QuestionLoopExperiment.openCodeModels(List.of(claudeOnly), global)));
        }

        private QuestionLoopExperiment.Config config(OpenCodeDataHome dataHome) {
            return new QuestionLoopExperiment.Config(List.of(Host.CLAUDE, Host.OPENCODE),
                    Map.of(Host.CLAUDE, "claude-haiku-4-5", Host.OPENCODE, "opencode/gpt-5.4-nano"), 1,
                    Integer.MAX_VALUE, 4, directory.resolve("cache"), directory.resolve("telemetry"), "ref",
                    directory.resolve("clones"), 0, directory.resolve("workspace"), directory.resolve("results"),
                    List.of("pm-exec"), Duration.ofMinutes(20), QuestionLoopExperiment.Warmth.FRESH, dataHome);
        }

        @Test
        @DisplayName("names a missing session in OpenCode's own form, and by a UUID elsewhere")
        void shouldNameMissingSessionPerHost() {
            var opencode = QuestionDriver.missingSessionId(Host.OPENCODE);
            var claude = QuestionDriver.missingSessionId(Host.CLAUDE);

            assertAll("the missing ids",
                    () -> assertTrue(opencode.matches("ses_[0-9a-f]{26}"), opencode),
                    () -> assertEquals(claude, UUID.fromString(claude).toString()));
        }
    }

    @Nested
    @DisplayName("harness executable")
    class HarnessExecutable {

        @Test
        @DisplayName("takes the path the property names, made absolute")
        void shouldTakeOverride() throws Exception {
            var claude = script(directory.resolve("override").resolve("claude"), "exit 0");

            var resolved = QuestionDriver.executable(Host.CLAUDE, claude.toString(), "", List.of());

            assertEquals(claude.toAbsolutePath().toString(), resolved);
        }

        @Test
        @DisplayName("finds the executable on the PATH and returns its absolute path")
        void shouldFindOnPath() throws Exception {
            var bin = directory.resolve("path-bin");
            var opencode = script(bin.resolve("opencode"), "exit 0");

            var resolved = QuestionDriver.executable(Host.OPENCODE, null,
                    directory.resolve("empty") + File.pathSeparator + bin, List.of());

            assertEquals(opencode.toAbsolutePath().toString(), resolved);
        }

        @Test
        @DisplayName("falls back to the host's known location when the PATH has none")
        void shouldFallBackToKnownLocation() throws Exception {
            var known = directory.resolve("known");
            var claude = script(known.resolve("claude"), "exit 0");

            var resolved = QuestionDriver.executable(Host.CLAUDE, null, directory.toString(), List.of(known));

            assertEquals(claude.toAbsolutePath().toString(), resolved);
        }

        @Test
        @DisplayName("stops with the property to set when no executable is found")
        void shouldStopWhenNotFound() {
            var path = directory.toString();
            var known = List.of(directory.resolve("none"));

            var stop = assertThrows(IllegalStateException.class,
                    () -> QuestionDriver.executable(Host.CLAUDE, null, path, known));

            assertTrue(stop.getMessage().contains("-Dpm.experiment.exe.claude"), stop.getMessage());
        }

        @Test
        @DisplayName("stops when the property names a file that is not executable")
        void shouldStopOnNonExecutableOverride() throws Exception {
            var file = Files.writeString(directory.resolve("opencode.txt"), "");
            var override = file.toString();

            var stop = assertThrows(IllegalStateException.class,
                    () -> QuestionDriver.executable(Host.OPENCODE, override, "", List.of()));

            assertTrue(stop.getMessage().contains("-Dpm.experiment.exe.opencode"), stop.getMessage());
        }
    }

    @Nested
    @DisplayName("worker launch")
    class WorkerLaunch {

        private static final String PROPERTY = QuestionDriver.EXECUTABLE_PROPERTY + "claude";
        private static final String PM_EXEC_ERROR = "{\"pm_exec\":\"error\",\"stage\":\"usage\",\"errno\":0,"
                + "\"message\":\"program needs an absolute path: claude\"}";
        private static final String PM_EXEC_LAUNCHED = "{\"pm_exec\":\"launched\",\"pid\":96187,"
                + "\"confinement\":\"unavailable\",\"landlock_abi\":null,\"no_new_privs\":false}";
        private static final String QUOTA_LINE = "error: Individual quota reached. Please upgrade your subscription "
                + "to increase your limits. Resets in 42h26m24s.";

        private String previous;
        private Path claude;

        @BeforeEach
        void claudeExecutable() throws IOException {
            claude = script(directory.resolve("bin").resolve("claude"), "exit 0");
            previous = System.getProperty(PROPERTY);
            System.setProperty(PROPERTY, claude.toString());
        }

        @AfterEach
        void restoreProperty() {
            if (previous == null) {
                System.clearProperty(PROPERTY);
            } else {
                System.setProperty(PROPERTY, previous);
            }
        }

        @Test
        @DisplayName("hands pm-exec the absolute path of Claude Code that the property names")
        void shouldLaunchByAbsolutePath() throws Exception {
            var arguments = directory.resolve("arguments.txt");
            var exec = script(directory.resolve("pm-exec"), "printf '%s\\n' \"$@\" > '" + arguments + "'");
            try (var driver = driver(exec)) {
                var worker = launch(driver);

                var exited = driver.awaitExit(worker, Duration.ofSeconds(30));

                var lines = Files.readAllLines(arguments);
                assertAll("the launch",
                        () -> assertTrue(exited.isPresent(), "the fake pm-exec did not exit"),
                        () -> assertEquals(claude.toAbsolutePath().toString(), lines.get(lines.indexOf("--") + 1),
                                lines.toString()));
            }
        }

        @Test
        @DisplayName("fails a worker that exits before its first event at once, with its exit and stderr line")
        void shouldFailExitedWorkerAtOnce() throws Exception {
            var exec = script(directory.resolve("pm-exec"), "echo '" + PM_EXEC_ERROR + "' >&2\nexit 2");
            try (var driver = driver(exec)) {
                launch(driver);
                var deadline = System.currentTimeMillis() + Duration.ofMinutes(20).toMillis();

                var asked = assertTimeoutPreemptively(Duration.ofSeconds(30),
                        () -> driver.awaitEventWhileLaunched(event -> "asked".equals(event.path("event").asText()),
                                deadline));

                var failure = driver.launchFailure();
                assertAll("the launch failure",
                        () -> assertTrue(asked.isEmpty()),
                        () -> assertEquals(Optional.of(new LaunchFailure(WORKER, 1, 2, PM_EXEC_ERROR, List.of())),
                                failure),
                        () -> assertTrue(Files.readString(driver.stub().events())
                                .contains("\"event\":\"launch_failed\""), "no launch_failed event"));
            }
        }

        @Test
        @DisplayName("records the harness's own stderr line and its last lines, not pm-exec's launched line")
        void shouldRecordHarnessStderr() throws Exception {
            var lines = new StringBuilder("echo '" + PM_EXEC_LAUNCHED + "' >&2\n");
            for (var index = 1; index <= 7; index++) {
                lines.append("echo 'harness line ").append(index).append("' >&2\n");
            }
            var exec = script(directory.resolve("pm-exec"), lines + "exit 1");
            try (var driver = driver(exec)) {
                launch(driver);
                var deadline = System.currentTimeMillis() + Duration.ofMinutes(20).toMillis();

                assertTimeoutPreemptively(Duration.ofSeconds(30),
                        () -> driver.awaitEventWhileLaunched(event -> false, deadline));

                var failure = driver.launchFailure().orElseThrow();
                assertAll("the launch failure",
                        () -> assertEquals("harness line 1", failure.stderr()),
                        () -> assertEquals(List.of("harness line 3", "harness line 4", "harness line 5",
                                "harness line 6", "harness line 7"), failure.tail()),
                        () -> assertTrue(Files.readString(driver.stub().events())
                                .contains("\"stderr\":\"harness line 1\""), "the event does not name the harness line"));
            }
        }

        @Test
        @DisplayName("ends a worker that still runs after pm-exec reported an error, and fails it")
        void shouldEndRunningWorkerWithError() throws Exception {
            var exec = script(directory.resolve("pm-exec"), "echo '" + PM_EXEC_ERROR + "' >&2\nexec sleep 30");
            try (var driver = driver(exec)) {
                var worker = launch(driver);
                var deadline = System.currentTimeMillis() + Duration.ofMinutes(20).toMillis();

                assertTimeoutPreemptively(Duration.ofSeconds(30),
                        () -> driver.awaitEventWhileLaunched(event -> false, deadline));

                var failure = driver.launchFailure();
                assertAll("the ended worker",
                        () -> assertFalse(worker.process().isAlive()),
                        () -> assertEquals(Optional.of(PM_EXEC_ERROR), failure.map(LaunchFailure::stderr)));
            }
        }

        @Test
        @DisplayName("a worker that still runs without an error has not failed to launch")
        void shouldNotFailRunningWorker() throws Exception {
            var exec = script(directory.resolve("pm-exec"), "exec sleep 30");
            try (var driver = driver(exec)) {
                var worker = launch(driver);

                var failure = driver.launchFailure();

                assertAll("the running worker",
                        () -> assertTrue(worker.process().isAlive()),
                        () -> assertEquals(Optional.empty(), failure));
            }
        }

        @Test
        @DisplayName("a worker whose relay connected before it exited has not failed to launch")
        void shouldNotFailConnectedWorker() throws Exception {
            var exec = script(directory.resolve("pm-exec"), "exit 1");
            try (var driver = driver(exec)) {
                var worker = launch(driver);
                driver.stub().event(QuestionDriver.CONNECTED, Map.of("worker", WORKER, "generation", 1));
                assertTrue(driver.awaitExit(worker, Duration.ofSeconds(30)).isPresent());

                var failure = driver.launchFailure();

                assertEquals(Optional.empty(), failure);
            }
        }

        @Test
        @DisplayName("a worker killed by an injected fault has not failed to launch")
        void shouldNotFailFaultedWorker() throws Exception {
            var exec = script(directory.resolve("pm-exec"), "exec sleep 30");
            try (var driver = driver(exec)) {
                var worker = launch(driver);
                driver.inject(worker, Fault.KILL);
                assertTrue(driver.awaitExit(worker, Duration.ofSeconds(30)).isPresent());

                var failure = driver.launchFailure();

                assertEquals(Optional.empty(), failure);
            }
        }

        @Test
        @DisplayName("ends the wait at once for a worker that connected and exited without a call, as an early exit")
        void shouldEndEarlyExitAtOnce() throws Exception {
            var exec = script(directory.resolve("pm-exec"), "echo '" + PM_EXEC_LAUNCHED + "' >&2\n"
                    + "echo 'the plan-marshall tools are unavailable' >&2\nexit 3");
            try (var driver = driver(exec)) {
                var worker = launch(driver);
                driver.stub().event(QuestionDriver.CONNECTED, Map.of("worker", WORKER, "generation", 1));
                assertTrue(driver.awaitExit(worker, Duration.ofSeconds(30)).isPresent());
                var deadline = System.currentTimeMillis() + Duration.ofMinutes(20).toMillis();

                var asked = assertTimeoutPreemptively(Duration.ofSeconds(30),
                        () -> driver.awaitEventWhileLaunched(event -> "asked".equals(event.path("event").asText()),
                                deadline));

                var early = driver.earlyExit();
                assertAll("the early exit",
                        () -> assertTrue(asked.isEmpty()),
                        () -> assertEquals(Optional.of(new EarlyExit(WORKER, 1, 3,
                                "the plan-marshall tools are unavailable", false)), early),
                        () -> assertEquals(Optional.empty(), driver.launchFailure()),
                        () -> assertTrue(Files.readString(driver.stub().events())
                                .contains("\"event\":\"early_exit\""), "no early_exit event"));
            }
        }

        @Test
        @DisplayName("a worker that called the stub before it exited has not ended early")
        void shouldNotEndEarlyAfterCall() throws Exception {
            var exec = script(directory.resolve("pm-exec"), "exit 0");
            try (var driver = driver(exec)) {
                var worker = launch(driver);
                driver.stub().event(QuestionDriver.CONNECTED, Map.of("worker", WORKER, "generation", 1));
                driver.stub().event(QuestionDriver.CALL, Map.of("worker", WORKER, "generation", 1, "tool", "pm_state"));
                assertTrue(driver.awaitExit(worker, Duration.ofSeconds(30)).isPresent());

                var early = driver.earlyExit();

                assertAll("the exited asker",
                        () -> assertEquals(Optional.empty(), early),
                        () -> assertEquals(Optional.empty(), driver.launchFailure()));
            }
        }

        @Test
        @DisplayName("ends the wait at once for a continuation generation that exits without a call after its worker "
                + "asked, as a continuation early exit")
        void shouldEndContinuationEarlyExitAtOnce() throws Exception {
            var exec = script(directory.resolve("pm-exec"), "echo '" + PM_EXEC_LAUNCHED + "' >&2\n"
                    + "echo 'the pm_state, pm_wait and pm_do tools are not available' >&2\nexit 0");
            try (var driver = driver(exec)) {
                driver.stub().event(QuestionDriver.CONNECTED, Map.of("worker", WORKER, "generation", 1));
                driver.stub().event(QuestionDriver.CALL, Map.of("worker", WORKER, "generation", 1, "tool", "pm_do"));
                driver.stub().event(QuestionDriver.ASKED, Map.of("worker", WORKER, "generation", 1, "decision", "d1"));
                var continuation = launch(driver, 2);
                driver.stub().event(QuestionDriver.CONNECTED, Map.of("worker", WORKER, "generation", 2));
                var deadline = System.currentTimeMillis() + Duration.ofMinutes(20).toMillis();

                var asked = assertTimeoutPreemptively(Duration.ofSeconds(30),
                        () -> driver.awaitEventWhileLaunched(event -> "asked".equals(event.path("event").asText())
                                && "d2".equals(event.path("decision").asText()), deadline));

                var early = driver.earlyExit();
                var events = Files.readString(driver.stub().events());
                assertAll("the continuation early exit",
                        () -> assertTrue(asked.isEmpty()),
                        () -> assertFalse(continuation.process().isAlive()),
                        () -> assertEquals(Optional.of(new EarlyExit(WORKER, 2, 0,
                                "the pm_state, pm_wait and pm_do tools are not available", true)), early),
                        () -> assertEquals(Optional.empty(), driver.launchFailure()),
                        () -> assertTrue(events.contains("\"event\":\"continuation_early_exit\""),
                                "no continuation_early_exit event"),
                        () -> assertFalse(events.contains("\"event\":\"early_exit\""), "an early_exit event"));
            }
        }

        @Test
        @DisplayName("a continuation generation that called the stub before it exited has not ended early")
        void shouldNotEndContinuationEarlyAfterCall() throws Exception {
            var exec = script(directory.resolve("pm-exec"), "exit 0");
            try (var driver = driver(exec)) {
                driver.stub().event(QuestionDriver.ASKED, Map.of("worker", WORKER, "generation", 1, "decision", "d1"));
                var continuation = launch(driver, 2);
                driver.stub().event(QuestionDriver.CONNECTED, Map.of("worker", WORKER, "generation", 2));
                driver.stub().event(QuestionDriver.CALL, Map.of("worker", WORKER, "generation", 2, "tool", "pm_state"));
                assertTrue(driver.awaitExit(continuation, Duration.ofSeconds(30)).isPresent());

                var early = driver.earlyExit();

                assertEquals(Optional.empty(), early);
            }
        }

        @Test
        @DisplayName("ends the wait at once for a worker whose harness answers with a quota error, also after a call")
        void shouldEndQuotaErrorAtOnce() throws Exception {
            var exec = script(directory.resolve("pm-exec"), "echo '" + PM_EXEC_LAUNCHED + "' >&2\n"
                    + "echo '" + QUOTA_LINE + "' >&2\nexit 1");
            try (var driver = driver(exec)) {
                var worker = launch(driver);
                driver.stub().event(QuestionDriver.CONNECTED, Map.of("worker", WORKER, "generation", 1));
                driver.stub().event(QuestionDriver.CALL, Map.of("worker", WORKER, "generation", 1, "tool", "pm_state"));
                assertTrue(driver.awaitExit(worker, Duration.ofSeconds(30)).isPresent());
                var deadline = System.currentTimeMillis() + Duration.ofMinutes(20).toMillis();

                var asked = assertTimeoutPreemptively(Duration.ofSeconds(30),
                        () -> driver.awaitEventWhileLaunched(event -> "asked".equals(event.path("event").asText()),
                                deadline));

                assertAll("the quota error",
                        () -> assertTrue(asked.isEmpty()),
                        () -> assertEquals(Optional.of(new QuotaError(WORKER, 1, QUOTA_LINE)), driver.quotaError()),
                        () -> assertEquals(Optional.empty(), driver.earlyExit()),
                        () -> assertTrue(Files.readString(driver.stub().events())
                                .contains("\"event\":\"quota_error\""), "no quota_error event"));
            }
        }

        @Test
        @DisplayName("a quota error is a harness line of stderr, else an output line, never pm-exec's own")
        void shouldFindQuotaDetail() {
            var output = List.<JsonNode>of(stamped("{\"type\":\"step_start\"}"),
                    stamped("{\"status\":\"ERROR\",\"error\":\"Individual Quota reached.\"}"));

            assertAll("the details",
                    () -> assertEquals(Optional.of(QUOTA_LINE), QuestionResults.quotaDetail(PM_EXEC_LAUNCHED + "\n"
                            + QUOTA_LINE + "\n", List.of())),
                    () -> assertEquals(Optional.of("{\"status\":\"ERROR\",\"error\":\"Individual Quota reached.\"}"),
                            QuestionResults.quotaDetail("harness line\n", output)),
                    () -> assertEquals(Optional.empty(), QuestionResults.quotaDetail(
                            "{\"pm_exec\":\"error\",\"message\":\"quota\"}\n", List.of())),
                    () -> assertEquals(Optional.empty(), QuestionResults.quotaDetail("", List.of())));
        }

        @Test
        @DisplayName("the detail of an early exit is the harness's stderr line, else the last output line")
        void shouldNameEarlyExitDetail() throws Exception {
            var output = List.<JsonNode>of(stamped("{\"type\":\"step_start\"}"),
                    stamped("{\"type\":\"text\",\"part\":{\"text\":\"tools unavailable\"}}"));

            assertAll("the details",
                    () -> assertEquals("harness line", QuestionDriver.earlyExitDetail(PM_EXEC_LAUNCHED
                            + "\nharness line\n", output)),
                    () -> assertEquals("{\"type\":\"text\",\"part\":{\"text\":\"tools unavailable\"}}",
                            QuestionDriver.earlyExitDetail(PM_EXEC_LAUNCHED + "\n", output)),
                    () -> assertEquals("", QuestionDriver.earlyExitDetail("", List.of())));
        }

        private static JsonNode stamped(String line) {
            var node = QuestionStub.JSON.createObjectNode();
            node.put("at", 1L);
            node.put("line", line);
            return node;
        }

        private QuestionDriver driver(Path exec) throws IOException {
            var stubSettings = Settings.of(directory.resolve("stub"), socket).withWindow(Duration.ofMillis(300));
            return QuestionDriver.start(stubSettings, new QuestionDriver.Settings(Host.CLAUDE, null,
                    directory.resolve("workers"), directory.resolve("run"), List.of(exec.toString())));
        }

        private static Worker launch(QuestionDriver driver) throws IOException {
            return launch(driver, 1);
        }

        private static Worker launch(QuestionDriver driver, int generation) throws IOException {
            return driver.startFresh(task(Form.FALLBACK), new Launch(WORKER, generation, ROLE, "Do it.", false, null,
                    List.of(), null));
        }
    }

    @Nested
    @DisplayName("default paths")
    class DefaultPaths {

        @Test
        @DisplayName("resolve the sibling repositories to the main checkout's parent, also from a worktree")
        void shouldResolveSiblingsFromWorktree() throws Exception {
            var siblings = Files.createDirectories(directory.resolve("git"));
            var main = Files.createDirectories(siblings.resolve("plan-marshall-mcp"));
            QuestionFixtures.git(null, "init", "--quiet", main.toString());
            commit(main, "one");
            var worktree = main.resolve(".plan/local/worktrees/feature");
            Files.createDirectories(worktree.getParent());
            QuestionFixtures.git(main, "worktree", "add", "--quiet", "--detach", worktree.toString());

            var fromMain = QuestionFixtures.siblingRepositories(main);
            var fromWorktree = QuestionFixtures.siblingRepositories(worktree);

            assertAll("the sibling repositories",
                    () -> assertEquals(siblings.toRealPath(), fromMain.toRealPath()),
                    () -> assertEquals(siblings.toRealPath(), fromWorktree.toRealPath()));
        }
    }

    @Nested
    @DisplayName("scenario recipes")
    class ScenarioRecipes {

        private static final String REPOSITORY = "widget-lib";
        private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
        private static final String TITLE = "Count the widgets";
        private static final String DESCRIPTION = "Count every widget of the catalogue and report the total.";

        @Test
        @DisplayName("builds the task, reply and check of a scenario whose answer is an offered choice")
        void shouldBuildScenarioWithOfferedAnswer() throws Exception {
            var source = branch();
            writeScenario(source, "q-demo-offered", "beta", "beta", null);

            var fixture = QuestionFixtures.scenario(source, "q-demo-offered", directory.resolve("clones"));

            assertAll("the scenario",
                    () -> assertEquals("large", fixture.size()),
                    () -> assertTrue(fixture.task().contains(TITLE), fixture.task()),
                    () -> assertTrue(fixture.task().contains(DESCRIPTION), fixture.task()),
                    () -> assertTrue(fixture.task().contains(QuestionFixtures.CANARY_FILE), fixture.task()),
                    () -> assertEquals("Do beta.", fixture.reply()),
                    () -> assertEquals(List.of("alpha", "beta", "gamma"), fixture.check().choices()),
                    () -> assertEquals("beta", fixture.check().reference()),
                    () -> assertEquals(directory.resolve("clones").resolve(REPOSITORY), fixture.checkout().localClone()),
                    () -> assertEquals(COMMIT, fixture.checkout().commit()));
        }

        @Test
        @DisplayName("adds the operator's new option as a further choice when the own-words item confirms it")
        void shouldAddOperatorsNewOption() throws Exception {
            var source = branch();
            writeScenario(source, "q-demo-new", "delta", "Do something none of the options says.",
                    "new_option: delta");

            var fixture = QuestionFixtures.scenario(source, "q-demo-new", directory.resolve("clones"));

            assertAll("the check",
                    () -> assertEquals(List.of("alpha", "beta", "gamma", "delta"), fixture.check().choices()),
                    () -> assertEquals("delta", fixture.check().reference()));
        }

        @Test
        @DisplayName("stops when the own-words item does not map the answer to a new option")
        void shouldStopWithoutNewOptionMapping() throws Exception {
            var source = branch();
            writeScenario(source, "q-demo-unmapped", "delta", "Do something none of the options says.",
                    "option: alpha");
            var clones = directory.resolve("clones");

            var stop = assertThrows(IllegalStateException.class,
                    () -> QuestionFixtures.scenario(source, "q-demo-unmapped", clones));

            assertTrue(stop.getMessage().contains("new_option"), stop.getMessage());
        }

        @Test
        @DisplayName("stops instead of inventing a field the recipe lacks")
        void shouldStopOnMissingField() throws Exception {
            var source = branch();
            var recipe = writeScenario(source, "q-demo-missing", "beta", "beta", null);
            recipe.remove("asking_task");
            write(source, QuestionFixtures.SCENARIOS + "/q-demo-missing.json", recipe);
            var clones = directory.resolve("clones");

            var stop = assertThrows(IllegalStateException.class,
                    () -> QuestionFixtures.scenario(source, "q-demo-missing", clones));

            assertTrue(stop.getMessage().contains("asking_task"), stop.getMessage());
        }

        @Test
        @DisplayName("uses only the two large scenarios and names every other recipe as unused")
        void shouldUseOnlyLargeScenarios() throws Exception {
            var source = branch();
            for (var id : QuestionFixtures.LARGE_SCENARIOS) {
                writeScenario(source, id, "beta", "beta", null);
            }
            writeScenario(source, "q-demo-other", "beta", "beta", null);

            var large = QuestionFixtures.largeScenarios(source, directory.resolve("clones"));
            var unused = QuestionFixtures.unusedScenarios(source);

            assertAll("the selection",
                    () -> assertEquals(QuestionFixtures.LARGE_SCENARIOS, large.stream()
                            .map(QuestionFixtures.Fixture::id).toList()),
                    () -> assertEquals(List.of("q-demo-other"), unused));
        }

        @Test
        @DisplayName("checks a local clone out at the start commit without changing the clone")
        void shouldCheckOutWithoutChangingClone() throws Exception {
            var clone = Files.createDirectories(directory.resolve("clones").resolve(REPOSITORY));
            QuestionFixtures.git(null, "init", "--quiet", clone.toString());
            var first = commit(clone, "one");
            var second = commit(clone, "two");
            var worker = directory.resolve("worker");

            QuestionFixtures.prepare(new QuestionFixtures.Fixture("q-demo", "large", "", "", null, false,
                    new QuestionFixtures.Checkout(clone, first)), worker);

            assertAll("the checkout",
                    () -> assertEquals("one", Files.readString(worker.resolve("file.txt"))),
                    () -> assertTrue(Files.isRegularFile(worker.resolve(QuestionFixtures.CANARY_FILE))),
                    () -> assertEquals(second, QuestionFixtures.git(clone, "rev-parse", "HEAD").strip()),
                    () -> assertEquals("", QuestionFixtures.git(clone, "status", "--porcelain")),
                    () -> assertEquals("two", Files.readString(clone.resolve("file.txt"))));
        }

        private DirectorySource branch() throws IOException {
            return new DirectorySource(Files.createDirectories(directory.resolve("branch")));
        }

        private ObjectNode writeScenario(DirectorySource source, String id, String branchKey, String targetAnswer,
                String ownWords) throws IOException {
            var recipe = QuestionStub.JSON.createObjectNode();
            recipe.put("id", id);
            recipe.put("item", "decision/question/" + id);
            recipe.put("repository", "https://example.org/acme/" + REPOSITORY);
            recipe.put("start_commit", COMMIT);
            recipe.put("plan", "2026-01-01-demo");
            recipe.put("plan_month", "26-01");
            recipe.put("asking_task", "TASK-002");
            var answer = recipe.putObject("answer_to_deliver");
            answer.put("branch_key", branchKey);
            answer.put("text", "Do " + branchKey + ".");
            write(source, QuestionFixtures.SCENARIOS + "/" + id + ".json", recipe);

            var task = QuestionStub.JSON.createObjectNode();
            task.put("title", TITLE);
            task.put("description", DESCRIPTION);
            write(source, "projects/" + REPOSITORY + "/archived-plans/26-01/2026-01-01-demo/tasks/TASK-002.json",
                    task);

            var item = QuestionStub.JSON.createObjectNode();
            var input = item.putObject("input");
            input.put("question", "Which way?");
            input.putArray("choices").add("alpha").add("beta").add("gamma");
            item.putObject("target").put("answer", targetAnswer);
            write(source, QuestionFixtures.CORPUS + "/decision/question/" + id + ".json", item);

            if (ownWords != null) {
                var mapping = QuestionStub.JSON.createObjectNode();
                mapping.putObject("target").put("answer", ownWords);
                write(source, QuestionFixtures.CORPUS + "/decision/own-words/" + id + ".json", mapping);
            }
            return recipe;
        }

        private static void write(DirectorySource source, String path, JsonNode content) throws IOException {
            var file = source.root().resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, QuestionStub.JSON.writeValueAsString(content));
        }
    }

    @Nested
    @DisplayName("run plan")
    class RunPlans {

        private static final QuestionLoopExperiment.LargeWork NOT_READ = () -> {
            throw new IllegalStateException("the large work was read");
        };

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"both, 'false,true'", "fresh, false", "WARM, true"})
        @DisplayName("pm.experiment.warmth restricts the warm/fresh axis of the run matrix")
        void shouldRestrictWarmth(String value, String expected) throws Exception {
            var config = config(1, 0, QuestionLoopExperiment.Warmth.of(value));

            var plans = QuestionLoopExperiment.r4(config, NOT_READ);

            var warm = plans.stream().map(QuestionLoopExperiment.RunPlan::warm).distinct().toList();
            assertAll("the planned workers",
                    () -> assertEquals(expected, String.join(",", warm.stream().map(String::valueOf).toList())),
                    () -> assertEquals(Form.values().length * warm.size(), plans.size()));
        }

        @Test
        @DisplayName("plans fresh and warm workers by default")
        void shouldDefaultToBoth() {
            assertEquals(QuestionLoopExperiment.Warmth.BOTH, QuestionLoopExperiment.Warmth.of(null));
        }

        @Test
        @DisplayName("fails an unknown value with the values it takes")
        void shouldRefuseUnknownWarmth() {
            var refused = assertThrows(IllegalArgumentException.class,
                    () -> QuestionLoopExperiment.Warmth.of("lukewarm"));

            assertTrue(refused.getMessage().contains("-Dpm.experiment.warmth=lukewarm: both, fresh or warm"),
                    refused.getMessage());
        }

        @Test
        @DisplayName("R4 small work only: large-runs 0 plans no large run and does not read the large work")
        void shouldPlanSmallWorkOnly() throws Exception {
            var config = config(2, 0, QuestionLoopExperiment.Warmth.BOTH);

            var plans = QuestionLoopExperiment.r4(config, NOT_READ);

            assertAll("the small work",
                    () -> assertEquals(2 * 2 * Form.values().length, plans.size()),
                    () -> assertTrue(plans.stream().allMatch(plan -> "small".equals(plan.fixture().size())),
                            plans.toString()));
        }

        @Test
        @DisplayName("R4 large work only: runs 0, fresh, one run plans each scenario once in hold and resume")
        void shouldPlanLargeWorkOnly() throws Exception {
            var config = config(0, 1, QuestionLoopExperiment.Warmth.FRESH);
            var large = QuestionFixtures.LARGE_SCENARIOS.stream()
                    .map(id -> new QuestionFixtures.Fixture(id, "large", "", "", null, false, null)).toList();

            var plans = QuestionLoopExperiment.r4(config, () -> large);

            var planned = plans.stream().map(plan -> plan.fixture().id() + " " + plan.form() + " " + plan.warm())
                    .toList();
            var expected = new ArrayList<String>();
            for (var id : QuestionFixtures.LARGE_SCENARIOS) {
                expected.add(id + " HOLD false");
                expected.add(id + " RESUME false");
            }
            assertEquals(expected, planned);
        }

        @Test
        @DisplayName("R4 with no runs in either part plans nothing")
        void shouldPlanNothingWithoutRuns() throws Exception {
            var config = config(0, 0, QuestionLoopExperiment.Warmth.BOTH);

            assertEquals(List.of(), QuestionLoopExperiment.r4(config, NOT_READ));
        }

        @Test
        @DisplayName("records an invocation's settings: runs, fixtures and their limit, hosts, warmth, R4 large runs, "
                + "run limit and holds")
        void shouldRecordInvocationSettings() throws Exception {
            var config = config(2, 0, QuestionLoopExperiment.Warmth.FRESH);
            var plans = QuestionLoopExperiment.r4(config, NOT_READ);

            var invocation = QuestionLoopExperiment.invocation("R4", config, plans);

            assertAll("the invocation",
                    () -> assertTrue(invocation.path(QuestionResults.INVOCATION).asText().startsWith("R4-")),
                    () -> assertEquals(2, invocation.path("runs").asInt()),
                    () -> assertEquals(0, invocation.path("large_runs").asInt()),
                    () -> assertTrue(invocation.path("fixtures_limit").isNull()),
                    () -> assertEquals(1, invocation.path("fixtures").asInt()),
                    () -> assertEquals("[\"claude\"]", invocation.path("hosts").toString()),
                    () -> assertEquals("fresh", invocation.path("warmth").asText()),
                    () -> assertEquals(1, invocation.path("parallel").asInt()),
                    () -> assertEquals("[300,3600,86400]", invocation.path("r2_ages_s").toString()),
                    () -> assertEquals("PT20M", invocation.path(QuestionResults.RUN_TIMEOUT).asText()),
                    () -> assertEquals("[60]", invocation.path("holds_s").toString()),
                    () -> assertEquals("[]", invocation.path("opencode_models").toString()),
                    () -> assertEquals(plans.size(), invocation.path("planned_runs").asInt()));
        }

        @Test
        @DisplayName("refuses a negative count of runs")
        void shouldRefuseNegativeRuns() {
            var refused = assertThrows(IllegalArgumentException.class,
                    () -> config(-1, 0, QuestionLoopExperiment.Warmth.BOTH));

            assertTrue(refused.getMessage().contains("must be 0 or more"), refused.getMessage());
        }

        private QuestionLoopExperiment.Config config(int runs, int largeRuns, QuestionLoopExperiment.Warmth warmth) {
            return new QuestionLoopExperiment.Config(List.of(Host.CLAUDE), Map.of(Host.CLAUDE, "claude-haiku-4-5"),
                    runs, Integer.MAX_VALUE, 1, directory.resolve("cache"), directory.resolve("telemetry"), "ref",
                    directory.resolve("clones"), largeRuns, directory.resolve("workspace"),
                    directory.resolve("results"), List.of("pm-exec"), Duration.ofMinutes(20), warmth,
                    OpenCodeDataHome.GLOBAL);
        }
    }

    @Nested
    @DisplayName("result bookkeeping")
    class ResultBookkeeping {

        @Test
        @DisplayName("a pass at the specified scale lists its invocation and keeps the verdict unmarked")
        void shouldMarkSpecifiedScale() throws Exception {
            var result = verdict("pass");
            var runs = List.of(run("i1", "f1"), run("i1", "f2"));

            QuestionResults.scale("Q1", result, runs, List.of(invocation("i1", "Q1", 20, 3, null)));

            assertAll("the result",
                    () -> assertTrue(result.path("atSpecifiedScale").asBoolean()),
                    () -> assertTrue(result.path("verdict_scale").isMissingNode(), result.toString()),
                    () -> assertEquals("pass", result.path("verdict").asText()),
                    () -> assertEquals(2, result.path("run_records").asInt()),
                    () -> assertEquals(2, result.path("fixtures").asInt()),
                    () -> assertEquals("i1", result.path("invocations").get(0).path("invocation").asText()),
                    () -> assertEquals(2, result.path("invocations").get(0).path("run_records").asInt()));
        }

        @ParameterizedTest(name = "{0}: runs {1}, large runs {2}, fixture limit {3} -> {4}")
        @CsvSource(nullValues = "none", value = {
                "Q1, 20, 3, none, true",
                "Q1, 3, 3, none, false",
                "Q1, 20, 3, 1, false",
                "R4, 20, 0, none, true",
                "R4, 0, 3, none, true",
                "R4, 0, 1, none, false",
                "R4, 0, 0, none, false"})
        @DisplayName("one invocation runs at the specified scale only with the specified runs and no fixture limit")
        void shouldJudgeInvocationScale(String item, int runs, int largeRuns, Integer limit, boolean expected) {
            assertEquals(expected, QuestionResults.atSpecifiedScale(item, invocation("i1", item, runs, largeRuns,
                    limit)));
        }

        @Test
        @DisplayName("a smaller pass keeps its verdict, marked not at the specified scale")
        void shouldMarkSmallerPass() throws Exception {
            var result = verdict("fail");

            QuestionResults.scale("Q1", result, List.of(run("i1", "f1")), List.of(invocation("i1", "Q1", 3, 3,
                    null)));

            assertAll("the result",
                    () -> assertFalse(result.path("atSpecifiedScale").asBoolean(true)),
                    () -> assertEquals(QuestionResults.NOT_AT_SCALE, result.path("verdict_scale").asText()),
                    () -> assertEquals("fail", result.path("verdict").asText()));
        }

        @Test
        @DisplayName("a record of an earlier pass without an invocation id shows up as unknown and not recorded")
        void shouldShowStaleRecord() throws Exception {
            var result = verdict("pass");
            var stale = (ObjectNode) run("i1", "f1");
            stale.remove(QuestionResults.INVOCATION);

            QuestionResults.scale("Q1", result, List.of(run("i1", "f1"), stale),
                    List.of(invocation("i1", "Q1", 20, 3, null)));

            var invocations = result.path("invocations");
            assertAll("the result",
                    () -> assertEquals(2, invocations.size()),
                    () -> assertEquals("unknown", invocations.get(1).path("invocation").asText()),
                    () -> assertEquals("not recorded", invocations.get(1).path("settings").asText()),
                    () -> assertFalse(result.path("atSpecifiedScale").asBoolean(true)));
        }

        @Test
        @DisplayName("R4 is at the specified scale only when its small and its large work both ran so")
        void shouldRequireBothPartsOfR4() throws Exception {
            var both = verdict("none");
            var smallOnly = verdict("none");
            List<JsonNode> invocations = List.of(invocation("small", "R4", 20, 0, null),
                    invocation("large", "R4", 0, 3, null));

            QuestionResults.scale("R4", both, List.of(run("small", "probe"), run("large", "s1")), invocations);
            QuestionResults.scale("R4", smallOnly, List.of(run("small", "probe")), invocations);

            assertAll("the results",
                    () -> assertTrue(both.path("atSpecifiedScale").asBoolean()),
                    () -> assertFalse(smallOnly.path("atSpecifiedScale").asBoolean(true)));
        }

        @Test
        @DisplayName("records the fixture wording of a forced item, and for Q2 that its controls stay unforced")
        void shouldRecordFixtureWording() {
            var q1 = verdict("pass");
            var q2 = verdict("pass");
            var r1 = verdict("pass");

            QuestionResults.limitsOfFixtures(q1, "Q1");
            QuestionResults.limitsOfFixtures(q2, "Q2");
            QuestionResults.limitsOfFixtures(r1, "R1");

            var wording = q1.path("limits").get(0).asText();
            assertAll("the limits",
                    () -> assertTrue(wording.contains(QuestionFixtures.FORCE_ASK), wording),
                    () -> assertTrue(wording.contains(QuestionFixtures.FORCE_ASK_PLAN_TASK), wording),
                    () -> assertTrue(wording.contains(QuestionFixtures.FORCE_ASK_EACH), wording),
                    () -> assertTrue(wording.contains("added before any measured run"), wording),
                    () -> assertTrue(q1.path("limits").get(1).asText().contains(QuestionDriver.EVIDENCE_RULE)),
                    () -> assertEquals(2, q1.path("limits").size()),
                    () -> assertTrue(q2.path("limits").get(2).asText().contains("unforced")),
                    () -> assertTrue(r1.path("limits").isMissingNode(), r1.toString()),
                    () -> assertEquals("pass", q1.path("verdict").asText()));
        }

        @Test
        @DisplayName("records OpenCode on the global data home with its model, beside the limits of R4")
        void shouldRecordOpenCodeDataHome() {
            var result = verdict("none");

            QuestionResults.limitOfOpenCodeDataHome(result, Set.of("opencode/gpt-5.4-nano"));
            QuestionResults.limitsOfR4(result, 3, 3, 20);

            var limits = result.path("limits");
            assertAll("the limits",
                    () -> assertEquals("OpenCode ran on the global data home with opencode/gpt-5.4-nano, a departure "
                            + "from the per-worker data home of the package, accepted by the operator.",
                            limits.get(0).asText().substring(0, limits.get(0).asText().indexOf(". ") + 1)),
                    () -> assertEquals(5, limits.size()));
        }

        @Test
        @DisplayName("invocations are appended to invocations.jsonl and read per item")
        void shouldReadInvocationsPerItem() throws Exception {
            var results = directory.resolve("results");
            QuestionResults.recordInvocation(results, invocation("q1-a", "Q1", 20, 3, null));
            QuestionResults.recordInvocation(results, invocation("r4-a", "R4", 20, 0, null));

            var read = QuestionResults.invocations(results, "Q1");

            assertEquals(List.of("q1-a"), read.stream().map(node -> node.path(QuestionResults.INVOCATION).asText())
                    .toList());
        }

        @Test
        @DisplayName("a result from two invocations keeps the limits of both, names their run limits, flags the "
                + "incomplete one, and counts the early exit without changing the verdicts")
        void shouldBuildResultFromTwoInvocations() throws Exception {
            var openCode = invocation("oc", "Q1", 3, 3, 2);
            openCode.put("opencode_data_home", "global");
            openCode.put(QuestionResults.RUN_TIMEOUT, "PT6M");
            openCode.put("planned_runs", 1);
            var claude = invocation("cl", "Q1", 3, 3, 2);
            claude.put("opencode_data_home", "global");
            claude.put(QuestionResults.RUN_TIMEOUT, "PT20M");
            claude.put("planned_runs", 3);
            claude.putArray("opencode_models");
            var runs = List.of(
                    fixtureRun("oc", "opencode", "opencode/gpt-5.4-nano", true, EARLY_EXIT_EVENTS, false),
                    fixtureRun("cl", "claude", "claude-haiku-4-5", false, ASKING_EVENTS, true),
                    fixtureRun("cl", "claude", "claude-haiku-4-5", false, ASKING_EVENTS, true));

            var result = QuestionResults.result("Q1", runs, List.of(openCode, claude), () -> {
                throw new IllegalStateException("Q1 read the scenario recipes");
            });

            var groups = result.path("groups");
            var limits = new ArrayList<String>();
            result.path("limits").forEach(limit -> limits.add(limit.asText()));
            var invocations = result.path("invocations");
            assertAll("the result",
                    () -> assertEquals("fail", result.path("verdict").asText()),
                    () -> assertEquals("pass", groups.get(0).path("verdict").asText()),
                    () -> assertEquals("fail", groups.get(1).path("verdict").asText()),
                    () -> assertEquals(List.of(2, 0, 0, 0, 2, 2, 2), counts(groups.get(0))),
                    () -> assertEquals(List.of(1, 1, 1, 1, 0, 0, 0), counts(groups.get(1))),
                    () -> assertTrue(limits.stream().anyMatch(limit -> limit.startsWith(
                            "OpenCode ran on the global data home with opencode/gpt-5.4-nano")), limits.toString()),
                    () -> assertTrue(limits.stream().anyMatch(limit -> limit.contains(QuestionFixtures.FORCE_ASK)),
                            limits.toString()),
                    () -> assertTrue(limits.stream().anyMatch(limit -> limit.contains("claude PT20M, opencode PT6M")),
                            limits.toString()),
                    () -> assertTrue(limits.stream().anyMatch(limit -> limit.contains(
                            "cl recorded 2 of its 3 planned runs")), limits.toString()),
                    () -> assertTrue(limits.stream().anyMatch(limit -> limit.endsWith("timed_out too: opencode 1.")),
                            limits.toString()),
                    () -> assertTrue(invocations.get(0).path("incomplete").isMissingNode(), invocations.toString()),
                    () -> assertTrue(invocations.get(1).path("incomplete").asBoolean()),
                    () -> assertEquals(3, invocations.get(1).path("planned_runs").asInt()),
                    () -> assertEquals(2, invocations.get(1).path("run_records").asInt()));
        }

        @Test
        @DisplayName("a continuation that exits without a call after its worker asked is a continuation early exit, "
                + "not an early exit and not timed out; one of a worker that never asked is an early exit")
        void shouldKeepAskedWorkerOutOfEarlyExits() throws Exception {
            var askedThenSilent = new ArrayList<>(ASKING_EVENTS);
            askedThenSilent.addAll(List.of("worker_started@2", QuestionDriver.CONNECTED + "@2", "worker_exited@2"));
            var faulted = List.of("worker_started", QuestionDriver.CONNECTED, QuestionDriver.FAULT_INJECTED,
                    "worker_exited");
            var superseded = new ArrayList<>(EARLY_EXIT_EVENTS);
            superseded.addAll(List.of("worker_started@2", QuestionDriver.CONNECTED + "@2", QuestionDriver.CALL + "@2",
                    "worker_exited@2"));

            var result = QuestionResults.evaluate("Q1", List.of(
                    fixtureRun("i1", "opencode", "m", true, askedThenSilent, true),
                    fixtureRun("i1", "opencode", "m", true, faulted, false),
                    fixtureRun("i1", "opencode", "m", true, superseded, false),
                    fixtureRun("i1", "opencode", "m", true, EARLY_EXIT_EVENTS, false)));

            var group = result.path("groups").get(0);
            assertAll("the group",
                    () -> assertEquals(List.of(4, 3, 3, 1, 1, 1, 1), counts(group)),
                    () -> assertEquals(1, group.path(QuestionResults.CONTINUATION_EARLY_EXIT).asInt()));
        }

        @Test
        @DisplayName("an old record whose continuation exited without a call is reclassified and named in the limits")
        void shouldReclassifyWaitedOutContinuationExit() throws Exception {
            var exited = q3Run("exited", "fresh", true, "", event(1_000, "asked"),
                    event(1_100, "worktree_digest").put("digest", "d1"), event(2_000, "worker_exited"),
                    event(2_100, "worktree_digest").put("digest", "d1"),
                    event(3_000, "worker_started").put("generation", 2),
                    event(3_100, QuestionDriver.CONNECTED).put("generation", 2),
                    event(9_000, "worker_exited").put("generation", 2).put("exit", 0));
            var recorded = q3Run("recorded", "fresh", false, "", event(1_000, "asked"),
                    event(1_100, "worktree_digest").put("digest", "d1"), event(2_000, "worker_exited"),
                    event(2_100, "worktree_digest").put("digest", "d1"));
            ((ObjectNode) recorded).put(QuestionResults.CONTINUATION_EARLY_EXIT, true);

            var result = QuestionResults.result("Q3", List.of(exited, recorded), List.of(invocation("i1", "Q3", 3, 3,
                    2)), () -> 0);

            var group = result.path("groups").get(0);
            var limits = new ArrayList<String>();
            result.path("limits").forEach(limit -> limits.add(limit.asText()));
            assertAll("the result",
                    () -> assertEquals(List.of(2, 0, 0, 0, 2, 0, 0), counts(group)),
                    () -> assertEquals(2, group.path(QuestionResults.CONTINUATION_EARLY_EXIT).asInt()),
                    () -> assertEquals("pass", group.path("verdict").asText()),
                    () -> assertTrue(group.path(QuestionResults.VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT)
                            .isMissingNode(), group.toString()),
                    () -> assertTrue(limits.stream().anyMatch(limit -> limit.contains("(recorded so: claude 1)")
                            && limit.contains("reclassified as continuation_early_exit: claude 1 (exited)")
                            && limit.endsWith("no group's verdict reads differently without them.")),
                            limits.toString()));
        }

        @Test
        @DisplayName("a continuation without an exit of its own stays timed out and is marked as waited out")
        void shouldMarkContinuationWithoutOwnExit() throws Exception {
            var terminated = q3Run("terminated", "fresh", true, "", event(1_000, "asked"),
                    event(1_100, "worktree_digest").put("digest", "d1"), event(2_000, "worker_exited"),
                    event(2_100, "worktree_digest").put("digest", "d1"),
                    event(3_000, "worker_started").put("generation", 2),
                    event(3_100, QuestionDriver.CONNECTED).put("generation", 2),
                    event(1_200_000, "worker_exited").put("generation", 2).put("exit", 143));

            var result = QuestionResults.result("Q3", List.of(terminated), List.of(invocation("i1", "Q3", 3, 3, 2)),
                    () -> 0);

            var group = result.path("groups").get(0);
            var limits = new ArrayList<String>();
            result.path("limits").forEach(limit -> limits.add(limit.asText()));
            assertAll("the result",
                    () -> assertEquals(1, group.path("timed_out").asInt()),
                    () -> assertEquals(0, group.path(QuestionResults.CONTINUATION_EARLY_EXIT).asInt()),
                    () -> assertTrue(limits.stream().anyMatch(limit -> limit.contains(
                            "marked here as waited-out continuation exits: claude 1 (terminated)")), limits.toString()));
        }

        @Test
        @DisplayName("reports the verdict without the continuation early exits only where it reads differently")
        void shouldReportVerdictWithoutContinuationExitsWhereDifferent() throws Exception {
            var passing = q3Run("passing", "fresh", false, "", event(1_000, "asked"),
                    event(1_100, "worktree_digest").put("digest", "d1"), event(2_000, "worker_exited"),
                    event(2_100, "worktree_digest").put("digest", "d1"));
            var unstopped = q3Run("unstopped", "fresh", false, "", event(1_000, "asked"),
                    event(1_100, "worktree_digest").put("digest", "d1"),
                    event(2_100, "worktree_digest").put("digest", "d1"),
                    event(3_000, "worker_started").put("generation", 2),
                    event(3_100, QuestionDriver.CONNECTED).put("generation", 2),
                    event(9_000, "worker_exited").put("generation", 2).put("exit", 0));

            var result = QuestionResults.result("Q3", List.of(passing, unstopped),
                    List.of(invocation("i1", "Q3", 3, 3, 2)), () -> 0);

            var group = result.path("groups").get(0);
            var limits = new ArrayList<String>();
            result.path("limits").forEach(limit -> limits.add(limit.asText()));
            assertAll("the result",
                    () -> assertEquals("fail", group.path("verdict").asText()),
                    () -> assertEquals("pass", group.path(QuestionResults.VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT)
                            .asText()),
                    () -> assertEquals("fail", result.path("verdict").asText()),
                    () -> assertEquals("pass", result.path(QuestionResults.VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT)
                            .asText()),
                    () -> assertTrue(limits.stream().anyMatch(limit -> limit.contains(
                            "is the verdict without them, an additional figure, where it reads differently: "
                                    + group.path("group").asText())), limits.toString()));
        }

        @Test
        @DisplayName("names the harness default as such, also for records that wrote null")
        void shouldNameHarnessDefault() throws Exception {
            var runs = List.of(fixtureRun("i1", "agy", "null", false, ASKING_EVENTS, true),
                    fixtureRun("i1", "agy", QuestionResults.HARNESS_DEFAULT, false, ASKING_EVENTS, true));

            var result = QuestionResults.evaluate("Q1", runs);

            var group = result.path("groups").get(0);
            assertAll("the group",
                    () -> assertEquals(1, result.path("groups").size()),
                    () -> assertEquals(QuestionResults.HARNESS_DEFAULT, group.path("model").asText()),
                    () -> assertTrue(group.path("group").asText().contains("model=harness default"),
                            group.toString()));
        }

        @Test
        @DisplayName("names no run limit when every invocation recorded the same")
        void shouldNameNoEqualRunLimit() throws Exception {
            var first = invocation("a", "Q1", 3, 3, null);
            first.put(QuestionResults.RUN_TIMEOUT, "PT20M");
            var second = invocation("b", "Q1", 3, 3, null);
            second.put(QuestionResults.RUN_TIMEOUT, "PT20M");
            var runs = List.of(fixtureRun("a", "claude", "claude-haiku-4-5", false, ASKING_EVENTS, true),
                    fixtureRun("b", "agy", "null", false, ASKING_EVENTS, true));

            var result = QuestionResults.result("Q1", runs, List.of(first, second), () -> 0);

            result.path("limits").forEach(limit -> assertFalse(limit.asText().contains("run limit"), limit.asText()));
        }

        @Test
        @DisplayName("supplies the run limit of an invocation that did not record it, marked, and keeps a recorded one")
        void shouldSupplyRunTimeouts() {
            var old = invocation("old", "Q1", 3, 3, null);
            var recorded = invocation("new", "Q1", 3, 3, null);
            recorded.put(QuestionResults.RUN_TIMEOUT, "PT20M");
            List<JsonNode> invocations = List.of(old, recorded);

            var supplied = QuestionResults.supplyRunTimeouts(invocations, "old=PT6M, new=PT1M");

            assertAll("the invocations",
                    () -> assertEquals("PT6M", supplied.get(0).path(QuestionResults.RUN_TIMEOUT).asText()),
                    () -> assertTrue(supplied.get(0).path(QuestionResults.RUN_TIMEOUT_SUPPLIED).asBoolean()),
                    () -> assertFalse(old.has(QuestionResults.RUN_TIMEOUT), "the record itself was changed"),
                    () -> assertEquals("PT20M", supplied.get(1).path(QuestionResults.RUN_TIMEOUT).asText()),
                    () -> assertFalse(supplied.get(1).has(QuestionResults.RUN_TIMEOUT_SUPPLIED)),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> QuestionResults.supplyRunTimeouts(invocations, "typo=PT6M")));
        }

        @Test
        @DisplayName("Q3 reports the time to the next pm_wait and to the exit beside the stop event the verdict reads")
        void shouldReportTimeToNextPmWait() throws Exception {
            var waited = q3Run("waited", "fresh", false, "", event(1_000, "asked"),
                    event(1_100, "worktree_digest").put("digest", "d1"),
                    event(3_000, QuestionDriver.CALL).put("tool", "pm_wait"), event(3_001, "wait_started"),
                    event(271_000, "answer_sent"), event(271_100, "worktree_digest").put("digest", "d1"),
                    event(300_000, "worker_exited"));
            var ended = q3Run("ended", "fresh", false, "", event(1_000, "asked"),
                    event(1_100, "worktree_digest").put("digest", "d1"),
                    event(4_000, "worktree_digest").put("digest", "d1"), event(5_000, "worker_exited"));
            var late = q3Run("late", "fresh", false, "", event(1_000, "asked"),
                    event(1_100, "worktree_digest").put("digest", "d1"), event(271_000, "answer_sent"),
                    event(271_100, "worktree_digest").put("digest", "d1"), event(290_000, "worker_exited"));

            var group = QuestionResults.evaluate("Q3", List.of(waited, ended, late)).path("groups").get(0);

            var perRun = group.path("per_run");
            assertAll("the group",
                    () -> assertEquals("pass", group.path("verdict").asText()),
                    () -> assertEquals(3, group.path("stopped").asInt()),
                    () -> assertEquals(299_000, group.path("ask_to_stop_event_ms_max").asLong()),
                    () -> assertTrue(group.path("ask_to_end_ms_median").isMissingNode(), group.toString()),
                    () -> assertEquals(1, group.path("asked_then_pm_wait").asInt()),
                    () -> assertEquals(2_000, group.path("ask_to_next_pm_wait_ms_median").asLong()),
                    () -> assertEquals(2_000, group.path("ask_to_next_pm_wait_ms_max").asLong()),
                    () -> assertEquals(3, group.path("exits_observed").asInt()),
                    () -> assertEquals(1, group.path("exits_before_answer").asInt()),
                    () -> assertEquals(2, group.path("stopped_by_next_pm_wait_or_end").asInt()),
                    () -> assertEquals("fail", group.path(QuestionResults.VERDICT_BY_NEXT_PM_WAIT).asText()),
                    () -> assertEquals("waited", perRun.get(0).path("run").asText()),
                    () -> assertEquals(2_000, perRun.get(0).path(QuestionResults.ASK_TO_NEXT_PM_WAIT_MS).asLong()),
                    () -> assertEquals(299_000, perRun.get(0).path(QuestionResults.ASK_TO_EXIT_MS).asLong()),
                    () -> assertFalse(perRun.get(0).path("exit_before_answer").asBoolean(true)),
                    () -> assertTrue(perRun.get(1).path(QuestionResults.ASK_TO_NEXT_PM_WAIT_MS).isNull()),
                    () -> assertEquals(4_000, perRun.get(1).path(QuestionResults.ASK_TO_EXIT_MS).asLong()));
        }

        @Test
        @DisplayName("a pm_wait of another generation or before the ask is not the next pm_wait")
        void shouldReadOnlyAskersNextPmWait() throws Exception {
            var run = q3Run("other", "warm", false, "", event(500, "wait_started"), event(1_000, "asked"),
                    event(2_000, QuestionDriver.CALL).put("tool", "pm_state"),
                    event(3_000, "wait_started").put("generation", 2));
            var view = new QuestionResults.RunView(run, QuestionResults.readJsonLines(Path.of(run.path("stub_dir")
                    .asText()).resolve("events.jsonl")), List.of(), List.of());

            var next = QuestionResults.nextPmWait(view, view.named("asked").getFirst());

            assertEquals(Optional.empty(), next);
        }

        @Test
        @DisplayName("a quota error counts in its own column, and the verdict without it is reported beside the verdict")
        void shouldCountQuotaErrorApart() throws Exception {
            var passing = q3Run("passing", "warm", false, "", event(1_000, "asked"),
                    event(1_100, "worktree_digest").put("digest", "d1"), event(2_000, "wait_started"),
                    event(3_000, "worktree_digest").put("digest", "d1"));
            var quota = q3Run("quota", "warm", true, "error: Individual quota reached.",
                    event(900, "worker_started"), event(1_000, QuestionDriver.CONNECTED),
                    event(1_500, QuestionDriver.CALL).put("tool", "pm_wait"), event(2_000, "worker_exited"));
            var invocation = invocation("i1", "Q3", 3, 3, 2);

            var result = QuestionResults.result("Q3", List.of(passing, quota), List.of(invocation), () -> 0);

            var group = result.path("groups").get(0);
            var limits = new ArrayList<String>();
            result.path("limits").forEach(limit -> limits.add(limit.asText()));
            assertAll("the result",
                    () -> assertEquals(List.of(2, 0, 0, 0, 1, 0, 0), counts(group)),
                    () -> assertEquals(1, group.path(QuestionResults.QUOTA_ERROR).asInt()),
                    () -> assertEquals("fail", group.path("verdict").asText()),
                    () -> assertEquals("pass", group.path(QuestionResults.VERDICT_WITHOUT_QUOTA_ERROR).asText()),
                    () -> assertEquals(1, group.path("runs_without_quota_error").asInt()),
                    () -> assertEquals("fail", result.path("verdict").asText()),
                    () -> assertEquals("pass", result.path(QuestionResults.VERDICT_WITHOUT_QUOTA_ERROR).asText()),
                    () -> assertTrue(limits.stream().anyMatch(limit -> limit.startsWith("Runs whose harness answered "
                            + "with a quota") && limit.endsWith(": quota.")), limits.toString()),
                    () -> assertTrue(limits.stream().anyMatch(limit -> limit.startsWith("ask_to_stop_event_ms "
                            + "(formerly ask_to_end_ms)")), limits.toString()));
        }

        @Test
        @DisplayName("a group without a quota error reports no verdict without it")
        void shouldReportNoVerdictWithoutQuotaErrorWhenNone() throws Exception {
            var result = QuestionResults.evaluate("Q1", List.of(fixtureRun("i1", "claude", "m", false,
                    ASKING_EVENTS, true)));

            assertAll("the result",
                    () -> assertTrue(result.path(QuestionResults.VERDICT_WITHOUT_QUOTA_ERROR).isMissingNode()),
                    () -> assertTrue(result.path("groups").get(0).path(QuestionResults.VERDICT_WITHOUT_QUOTA_ERROR)
                            .isMissingNode()),
                    () -> assertEquals(0, result.path("groups").get(0).path(QuestionResults.QUOTA_ERROR).asInt(-1)));
        }

        private static ObjectNode event(long at, String name) {
            var node = QuestionStub.JSON.createObjectNode();
            node.put("at", at);
            node.put("event", name);
            node.put("worker", WORKER);
            node.put("generation", 1);
            return node;
        }

        /**
         * A run record of Q3 with the stub's events written by the test, its run named by its stub's directory, and
         * the {@code stderr} of {@code w1} beside its output, where a record of an earlier pass does not name it.
         */
        private JsonNode q3Run(String id, String form, boolean timedOut, String stderr, ObjectNode... events)
                throws IOException {
            var base = Files.createDirectories(directory.resolve(id));
            var stub = Files.createDirectories(base.resolve("stub"));
            var lines = new StringBuilder();
            for (var event : events) {
                lines.append(QuestionStub.JSON.writeValueAsString(event)).append('\n');
            }
            Files.writeString(stub.resolve("events.jsonl"), lines);
            var runDir = Files.createDirectories(base.resolve("run"));
            Files.writeString(QuestionDriver.stderrFile(runDir, WORKER, 1), stderr);
            var run = QuestionStub.JSON.createObjectNode();
            run.put(QuestionResults.INVOCATION, "i1");
            run.put("item", "Q3");
            run.put("host", "claude");
            run.put("model", "m");
            run.put("form", form);
            run.put("continuation", "fallback");
            run.put("fixture", "f1");
            run.put("kind", "entity");
            run.put(QuestionResults.TIMED_OUT, timedOut);
            run.put("stub_dir", stub.toString());
            var worker = run.putArray("workers").addObject();
            worker.put("worker", WORKER);
            worker.put("generation", 1);
            worker.put("output", runDir.resolve("output-w1-g1.jsonl").toString());
            return run;
        }

        private static final List<String> ASKING_EVENTS = List.of("worker_started", QuestionDriver.CONNECTED,
                QuestionDriver.CALL, "asked", "continued", "submitted", "worker_exited");
        private static final List<String> EARLY_EXIT_EVENTS = List.of("worker_started", QuestionDriver.CONNECTED,
                "disconnected", "worker_exited");

        private static List<Integer> counts(JsonNode group) {
            return List.of("runs", "timed_out", "no_ask", "early_exit", "asked", "continued", "submitted").stream()
                    .map(field -> group.path(field).asInt(-1)).toList();
        }

        /**
         * A run record of Q1 with the stub's events of {@code w1}, written by the test: each event of generation 1,
         * or of the generation after its {@code @} ({@code worker_exited@2}).
         */
        private JsonNode fixtureRun(String invocation, String host, String model, boolean timedOut,
                List<String> events, boolean recordedAsk) throws IOException {
            var stub = Files.createDirectories(directory.resolve("stub-" + UUID.randomUUID()));
            var lines = new StringBuilder();
            var at = 1_000L;
            for (var named : events) {
                var parts = named.split("@", 2);
                var event = parts[0];
                var node = QuestionStub.JSON.createObjectNode();
                node.put("at", at++);
                node.put("event", event);
                node.put("worker", WORKER);
                node.put("generation", parts.length > 1 ? Integer.parseInt(parts[1]) : 1);
                if ("asked".equals(event)) {
                    node.put("decision", "d1");
                }
                lines.append(QuestionStub.JSON.writeValueAsString(node)).append('\n');
            }
            Files.writeString(stub.resolve("events.jsonl"), lines);
            Files.writeString(stub.resolve("questions.jsonl"), recordedAsk ? "{\"line\":\"asked\"}\n" : "");
            var run = QuestionStub.JSON.createObjectNode();
            run.put(QuestionResults.INVOCATION, invocation);
            run.put("item", "Q1");
            run.put("host", host);
            run.put("model", model);
            run.put("form", "fresh");
            run.put("continuation", "fallback");
            run.put("fixture", "f1");
            run.put("kind", "entity");
            run.put(QuestionResults.TIMED_OUT, timedOut);
            run.put("stub_dir", stub.toString());
            run.putArray("workers");
            return run;
        }

        private static ObjectNode verdict(String verdict) {
            var result = QuestionStub.JSON.createObjectNode();
            result.put("verdict", verdict);
            return result;
        }

        private static JsonNode run(String invocation, String fixture) {
            var run = QuestionStub.JSON.createObjectNode();
            run.put(QuestionResults.INVOCATION, invocation);
            run.put("fixture", fixture);
            return run;
        }

        private static ObjectNode invocation(String id, String item, int runs, int largeRuns, Integer limit) {
            var invocation = QuestionStub.JSON.createObjectNode();
            invocation.put(QuestionResults.INVOCATION, id);
            invocation.put("item", item);
            invocation.put("runs", runs);
            invocation.put("large_runs", largeRuns);
            if (limit == null) {
                invocation.putNull("fixtures_limit");
            } else {
                invocation.put("fixtures_limit", limit);
            }
            return invocation;
        }
    }

    @Nested
    @DisplayName("Antigravity results")
    class AntigravityResults {

        @ParameterizedTest(name = "{0} -> {1} tokens")
        @CsvSource(delimiter = '|', value = {
                "{\"input_tokens\":100,\"output_tokens\":20} | 120",
                "{\"total_tokens\":150,\"input_tokens\":100,\"output_tokens\":20} | 150",
                "{\"promptTokenCount\":70,\"candidatesTokenCount\":5,\"totalTokenCount\":77} | 77"})
        @DisplayName("reads the tokens of agy's top-level usage")
        void shouldReadTopLevelUsage(String usage, long expected) throws Exception {
            var outputs = agyOutput(usage);

            assertEquals(expected, QuestionResults.tokens(outputs));
        }

        @Test
        @DisplayName("reads agy's top-level response as the final text")
        void shouldReadTopLevelResponse() throws Exception {
            var outputs = agyOutput("{\"input_tokens\":1}");

            assertEquals("Done.", QuestionResults.finalText(outputs));
        }

        @ParameterizedTest(name = "new session {0}, started {1}, exit after {2} ms with {3} -> {4}")
        @CsvSource({
                "true, true, 600000, 0, negative_new_session",
                "false, true, 2000, 1, failed_at_once",
                "false, true, -1, -1, hung",
                "false, true, 90000, 1, hung",
                "false, true, 2000, 0, exited_without_failure",
                "true, false, -1, -1, hung"})
        @DisplayName("classifies the resume of a missing session")
        void shouldClassifyNegativeResume(boolean newSession, boolean started, long exitAfter, int exitCode,
                String expected) {
            assertEquals(expected, QuestionResults.negativeOutcome(newSession, started, exitAfter, exitCode));
        }

        @ParameterizedTest(name = "{0}: named {1}, reported {2}, warned {3} -> {4}")
        @CsvSource(nullValues = "none", value = {
                "OPENCODE, ses_a, ses_a, false, true",
                "OPENCODE, ses_a, none, false, false",
                "CLAUDE, id-a, id-a, false, false",
                "CLAUDE, id-a, id-b, false, true",
                "ANTIGRAVITY, id-a, id-b, false, true",
                "ANTIGRAVITY, id-a, none, true, true"})
        @DisplayName("a missing-session resume started a new session: OpenCode's under the named id, others' by warning "
                + "or another id")
        void shouldRecogniseNewSession(Host host, String named, String reported, boolean warned, boolean expected) {
            assertEquals(expected, QuestionResults.startedNewSession(host, named, reported, warned));
        }

        @Test
        @DisplayName("OpenCode's missing-session resume, which exits 0 at once under the named id, is a new session")
        void shouldClassifyOpenCodeMissingSession() {
            var newSession = QuestionResults.startedNewSession(Host.OPENCODE, "ses_a", "ses_a", false);

            assertEquals(QuestionResults.NEGATIVE_NEW_SESSION,
                    QuestionResults.negativeOutcome(newSession, true, 5000, 0));
        }

        @Test
        @DisplayName("recognises agy's warning that the named conversation was not found")
        void shouldRecogniseMissingSessionWarning() {
            var stderr = "warning: conversation \"abc-1\" not found\n";

            assertAll("the warning",
                    () -> assertTrue(QuestionResults.warnsMissingSession(stderr, "abc-1")),
                    () -> assertFalse(QuestionResults.warnsMissingSession(stderr, "abc-2")));
        }

        private static List<JsonNode> agyOutput(String usage) throws IOException {
            var result = (ObjectNode) QuestionStub.JSON.readTree("{\"conversation_id\":\"c-1\",\"status\":\"success\","
                    + "\"response\":\"Done.\",\"num_turns\":2,\"usage\":" + usage + "}");
            var stamped = QuestionStub.JSON.createObjectNode();
            stamped.put("at", 1L);
            stamped.put("line", QuestionStub.JSON.writeValueAsString(result));
            return List.of(stamped);
        }
    }

    // --- fixtures ---

    /** The files of a telemetry branch, written by the test into a directory. */
    private record DirectorySource(Path root) implements QuestionFixtures.Source {

        @Override
        public String read(String path) throws IOException {
            var file = root.resolve(path);
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("not on the branch: " + path);
            }
            return Files.readString(file);
        }

        @Override
        public List<String> names(String path) throws IOException {
            try (var entries = Files.list(root.resolve(path))) {
                return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
            }
        }
    }

    private static String commit(Path repository, String content) throws IOException {
        Files.writeString(repository.resolve("file.txt"), content);
        QuestionFixtures.git(repository, "add", "file.txt");
        QuestionFixtures.git(repository, "-c", "user.name=test", "-c", "user.email=test@example.org", "-c",
                "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "--quiet", "-m", content);
        return QuestionFixtures.git(repository, "rev-parse", "HEAD").strip();
    }

    /** Writes a shell script and makes it executable: a fake {@code pm-exec} or harness, never a real one. */
    private static Path script(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    private QuestionStub start(int questionBound) throws IOException {
        return QuestionStub.start(Settings.of(directory.resolve("stub"), socket).withWindow(Duration.ofMillis(300))
                .withQuestionBound(questionBound));
    }

    private static TaskSpec task(Form form) {
        var facts = QuestionStub.JSON.createObjectNode();
        facts.put("task", "Implement the function as its contract says.");
        return TaskSpec.entity(TASK, ROLE, "Work the task in facts.task", facts,
                "answer:text[1..200],judgment:text[0..2000]", form, List.of());
    }

    /** A worker leased the task at spawn, which it acknowledged. */
    private Client activeWorker(Form form) throws IOException {
        stub.queue(task(form));
        stub.leaseAtSpawn(TASK, WORKER, 1);
        var client = Client.connect(socket, WORKER, 1);
        clients.add(client);
        var offered = client.state();
        assertTrue(offered.contains("state: task.offered"), offered);
        var active = client.link("ack", QuestionStub.JSON.createObjectNode());
        assertTrue(active.contains("state: entity.active"), active);
        return client;
    }

    private static ObjectNode validEnvelope() throws IOException {
        return (ObjectNode) QuestionStub.JSON.readTree(QuestionDriver.ENVELOPE_EXAMPLE);
    }

    private static PartAnswer answer(String questionId) {
        return new PartAnswer(questionId, "description", "The description is right.", "chosen");
    }

    /** A worker's connection as its relay opens it: the identity first, then newline-delimited JSON-RPC. */
    private static final class Client implements AutoCloseable {

        private final SocketChannel channel;
        private final BufferedReader reader;
        private final OutputStream out;
        private int nextId;

        private Client(SocketChannel channel) {
            this.channel = channel;
            this.reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel),
                    StandardCharsets.UTF_8));
            this.out = Channels.newOutputStream(channel);
        }

        static Client connect(Path socket, String worker, int generation) throws IOException {
            var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socket));
            var client = new Client(channel);
            client.send("{\"worker\":\"" + worker + "\",\"generation\":" + generation + ",\"role\":\"" + ROLE
                    + "\",\"harness\":\"test\"}");
            return client;
        }

        void initialize() throws IOException {
            var id = ++nextId;
            send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"initialize\",\"params\":{}}");
            response(id);
        }

        String state() throws IOException {
            var arguments = QuestionStub.JSON.createObjectNode();
            arguments.put("entity", TASK);
            return call("pm_state", arguments);
        }

        String link(String link, ObjectNode args) throws IOException {
            var arguments = QuestionStub.JSON.createObjectNode();
            arguments.put("plan_id", QuestionStub.PLAN_ID);
            arguments.put("link", link);
            arguments.set("args", args);
            return call("pm_do", arguments);
        }

        String ask(String link, ObjectNode envelope) throws IOException {
            var args = QuestionStub.JSON.createObjectNode();
            args.set("decision", envelope);
            return link(link, args);
        }

        String call(String tool, ObjectNode arguments) throws IOException {
            var id = ++nextId;
            var request = QuestionStub.JSON.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", "tools/call");
            var params = request.putObject("params");
            params.put("name", tool);
            params.set("arguments", arguments);
            send(QuestionStub.JSON.writeValueAsString(request));
            return response(id).path("result").path("content").get(0).path("text").asText();
        }

        private JsonNode response(int id) throws IOException {
            for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                var message = QuestionStub.JSON.readTree(line);
                if (message.path("id").asInt(-1) == id) {
                    return message;
                }
            }
            throw new IOException("the stub closed the connection before response " + id);
        }

        private void send(String line) throws IOException {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
