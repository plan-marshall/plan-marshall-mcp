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
import java.nio.file.StandardOpenOption;
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
import java.util.concurrent.Executors;
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
import de.planmarshall.e2e.experiment.question.QuestionStub.Quorum;
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
import org.junit.jupiter.params.provider.EnumSource;

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
 * as a timeout or an early exit. Beside them Q6's vote runs: a continuation that the asker, still holding
 * {@code pm_wait} when the quorum decided, already took and submitted, given to a new generation that the stub refuses,
 * so the run waited out its limit. Beside them Q7's open time: a fresh asker that holds {@code pm_wait} through the
 * open time, unseen by {@code leases_while_open}, or not ended by the driver before the open time; and a held wait the
 * stopping stub refuses named as {@code generation_fenced}. Beside them R3's faults: a stop injected before the asker
 * holds {@code pm_wait}, so the answer is never delivered and nothing is detected; a call cut no host can make (a hold
 * shorter than the tool timeout, progress frames that reset it), or one counted without the cut in the logs; a second
 * resume of a task the stub already closed and continued at the resumed process's exit; a continuation logged before
 * the detection that caused it; and a stub of R3 that releases or continues a task at the worker's process exit, so the
 * detection is not made by the acknowledgement, or a stub of another item that no longer does. The recipes, corpus items, repositories and executables of these
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
    @DisplayName("vote and answer path")
    class VoteAndAnswerPath {

        private static final String VOTE_A = "vote-Q-001-quorum-a";
        private static final String VOTE_B = "vote-Q-001-quorum-b";
        private static final String CLAUDE_PROPERTY = QuestionDriver.EXECUTABLE_PROPERTY + "claude";
        private static final Map<String, Quorum> QUORUMS = Map.of("interpretation",
                new Quorum(List.of("quorum-a", "quorum-b"), 90));

        private String previous;

        @BeforeEach
        void claudeExecutable() throws IOException {
            var claude = script(directory.resolve("bin").resolve("claude"), "exit 0");
            previous = System.getProperty(CLAUDE_PROPERTY);
            System.setProperty(CLAUDE_PROPERTY, claude.toString());
        }

        @AfterEach
        void restoreProperty() {
            if (previous == null) {
                System.clearProperty(CLAUDE_PROPERTY);
            } else {
                System.setProperty(CLAUDE_PROPERTY, previous);
            }
        }

        @Test
        @DisplayName("takes a decision its quorum decided at once, without an operator, and the task continues")
        void shouldTakeQuorumDecisionWithoutOperator() throws Exception {
            try (var driver = driver(QUORUMS, "exit 0")) {
                ask(driver);
                vote(driver, VOTE_A, "quorum-a");
                vote(driver, VOTE_B, "quorum-b");

                var answered = assertTimeoutPreemptively(Duration.ofSeconds(10),
                        () -> driver.awaitAnswer("Q-001", Duration.ofMinutes(15), Duration.ZERO, decision -> {
                            throw new IllegalStateException("the operator was asked");
                        }));

                var events = Files.readString(driver.stub().events());
                assertAll("the quorum's answer",
                        () -> assertEquals(Optional.of("Q-001"), answered),
                        () -> assertTrue(events.contains("\"event\":\"" + QuestionDriver.QUORUM_ANSWER_TAKEN + "\""),
                                "no quorum_answer_taken event"),
                        () -> assertTrue(events.contains("\"channel\":\"quorum\""), "not answered by the quorum"),
                        () -> assertFalse(events.contains("\"event\":\"decision_seen\""), "the operator saw it"),
                        () -> assertTrue(driver.stub().operatorDecisions().isEmpty()),
                        () -> assertTrue(driver.stub().taskIds().contains("T-1-Q-001"),
                                driver.stub().taskIds().toString()));
            }
        }

        @Test
        @DisplayName("answers a question without a quorum as the operator, the path of the consult runs")
        void shouldAnswerWithoutQuorumAsOperator() throws Exception {
            try (var driver = driver(Map.of(), "exit 0")) {
                ask(driver);

                var answered = assertTimeoutPreemptively(Duration.ofSeconds(10),
                        () -> driver.awaitAnswer("Q-001", Duration.ofMinutes(15), Duration.ZERO,
                                decision -> List.of(answer("Q-001"))));

                var events = Files.readString(driver.stub().events());
                assertAll("the operator's answer",
                        () -> assertEquals(Optional.of("Q-001"), answered),
                        () -> assertTrue(events.contains("\"event\":\"decision_seen\""), "no decision_seen event"),
                        () -> assertTrue(events.contains("\"result\":\"ANSWERED\""), "not answered"),
                        () -> assertFalse(events.contains(QuestionDriver.QUORUM_ANSWER_TAKEN), events),
                        () -> assertTrue(driver.stub().taskIds().contains("T-1-Q-001"),
                                driver.stub().taskIds().toString()));
            }
        }

        @Test
        @DisplayName("a vote task whose voter exits closes lost at once, and the set escalates role_unavailable to "
                + "the operator")
        void shouldEscalateMissingVote() throws Exception {
            try (var driver = driver(QUORUMS, "exit 0")) {
                ask(driver);
                vote(driver, VOTE_A, "quorum-a");
                driver.stub().leaseAtSpawn(VOTE_B, VOTE_B, 1);

                driver.stub().workerExited(VOTE_B, 1, false);
                var answered = assertTimeoutPreemptively(Duration.ofSeconds(10),
                        () -> driver.awaitAnswer("Q-001", Duration.ofMinutes(15), Duration.ZERO,
                                decision -> List.of(answer("Q-001"))));

                var events = QuestionResults.readJsonLines(driver.stub().events());
                var adjudicated = events.stream().filter(event -> "adjudicated".equals(event.path("event").asText()))
                        .findFirst().orElseThrow();
                assertAll("the escalation",
                        () -> assertEquals(Optional.of("task.lost"), driver.stub().state(VOTE_B)),
                        () -> assertEquals("escalated", adjudicated.path("outcome").asText()),
                        () -> assertEquals("role_unavailable", adjudicated.path("reason").asText()),
                        () -> assertEquals(Optional.of("Q-001"), answered),
                        () -> assertTrue(events.stream().anyMatch(event -> "decision_seen".equals(
                                event.path("event").asText())), "the operator did not see it"),
                        () -> assertTrue(driver.stub().taskIds().contains("T-1-Q-001"),
                                driver.stub().taskIds().toString()));
            }
        }

        @Test
        @DisplayName("a voter that connects and exits without a call is a voter early exit: the run goes on, and its "
                + "vote is missing")
        void shouldRecordVoterEarlyExitWithoutEndingRun() throws Exception {
            try (var driver = driver(QUORUMS, "echo 'the plan-marshall tools are unavailable' >&2\nexit 3")) {
                ask(driver);
                var voter = driver.sibling(Host.CLAUDE, null).startLeased(VOTE_B, VOTE_B,
                        QuestionLoopExperiment.voterLaunch(VOTE_B, "quorum-b"));
                driver.stub().event(QuestionDriver.CONNECTED, Map.of("worker", VOTE_B, "generation", 1));
                assertTrue(driver.awaitExit(voter, Duration.ofSeconds(30)).isPresent());
                var lost = driver.awaitEvent(event -> "lost".equals(event.path("event").asText())
                        && VOTE_B.equals(event.path("task").asText()), System.currentTimeMillis() + 10_000);

                var voters = driver.voterEarlyExits();

                assertAll("the voter early exit",
                        () -> assertEquals(List.of(new EarlyExit(VOTE_B, 1, 3,
                                "the plan-marshall tools are unavailable", false, true)), voters),
                        () -> assertEquals(Optional.empty(), driver.earlyExit()),
                        () -> assertEquals(Optional.empty(), driver.launchFailure()),
                        () -> assertTrue(lost.isPresent(), "the vote task was not closed lost"),
                        () -> assertTrue(Files.readString(driver.stub().events())
                                .contains("\"event\":\"" + QuestionResults.VOTER_EARLY_EXIT + "\""),
                                "no voter_early_exit event"));
            }
        }

        @Test
        @DisplayName("the launch of a newer generation ends the older one that still runs in the shared directory")
        void shouldEndOlderGenerationAtLaunch() throws Exception {
            try (var driver = driver(Map.of(), "exec sleep 30")) {
                var older = driver.startFresh(task(Form.FALLBACK), new Launch(WORKER, 1, ROLE, "Do it.", false,
                        null, List.of(), null));

                var newer = driver.startLeased(TASK, TASK, new Launch(WORKER, 2, ROLE, "Do it.", false, null,
                        List.of(), null));

                var events = QuestionResults.readJsonLines(driver.stub().events());
                var ended = events.stream().filter(event -> QuestionDriver.SUPERSEDED_ENDED.equals(
                        event.path("event").asText())).toList();
                assertAll("the ended generation",
                        () -> assertFalse(older.process().isAlive()),
                        () -> assertTrue(newer.process().isAlive()),
                        () -> assertEquals(1, ended.size(), events.toString()),
                        () -> assertEquals(1, ended.getFirst().path("generation").asInt()),
                        () -> assertEquals(2, ended.getFirst().path("by").asInt()),
                        () -> assertEquals(Optional.empty(), driver.launchFailure()),
                        () -> assertEquals(Optional.empty(), driver.earlyExit()));
            }
        }

        private QuestionDriver driver(Map<String, Quorum> quorums, String exec) throws IOException {
            var stubSettings = Settings.of(directory.resolve("stub"), socket).withWindow(Duration.ofMillis(300))
                    .withQuorums(quorums);
            return QuestionDriver.start(stubSettings, new QuestionDriver.Settings(Host.CLAUDE, null,
                    directory.resolve("workers"), directory.resolve("run"),
                    List.of(script(directory.resolve("pm-exec"), exec).toString())));
        }

        @Test
        @DisplayName("starts no generation for a continuation the holding asker took and submitted before the quorum's "
                + "answer was taken, and records it as continued by hold")
        void shouldNotStartGenerationForContinuationSubmittedByHold() throws Exception {
            try (var driver = driver(QUORUMS, "exit 0")) {
                var asker = ask(driver);
                vote(driver, VOTE_A, "quorum-a");
                vote(driver, VOTE_B, "quorum-b");
                var offered = asker.call("pm_wait", QuestionStub.JSON.createObjectNode());
                asker.link("ack", QuestionStub.JSON.createObjectNode());
                var submitted = asker.link("submit", submission());
                var answered = assertTimeoutPreemptively(Duration.ofSeconds(10),
                        () -> driver.awaitAnswer("Q-001", Duration.ofMinutes(15), Duration.ZERO, decision -> {
                            throw new IllegalStateException("the operator was asked");
                        }));

                var held = driver.continuedByHold("Q-001");

                var ended = endHold(driver, asker);
                var events = Files.readString(driver.stub().events());
                assertAll("the continuation by hold",
                        () -> assertEquals(Optional.of("Q-001"), answered),
                        () -> assertTrue(offered.contains("state: task.offered"), offered),
                        () -> assertTrue(submitted.contains("state: entity.completed"), submitted),
                        () -> assertTrue(held.isPresent(), events),
                        () -> assertEquals("T-1-Q-001", held.orElseThrow().task()),
                        () -> assertEquals(WORKER, held.orElseThrow().worker()),
                        () -> assertEquals(1, held.orElseThrow().generation()),
                        () -> assertEquals("submitted", held.orElseThrow().outcome()),
                        () -> assertTrue(held.orElseThrow().submittedAt() > 0, held.toString()),
                        () -> assertTrue(held.orElseThrow().closedAt() >= held.orElseThrow().submittedAt(),
                                held.toString()),
                        () -> assertEquals("quorum", held.orElseThrow().channel()),
                        () -> assertEquals(List.of(), driver.workers(), "a generation was started"),
                        () -> assertEquals(Optional.of("entity.completed"), driver.stub().state("T-1-Q-001")),
                        () -> assertEquals(Optional.of("task.asked"), driver.stub().state(TASK)),
                        () -> assertTrue(events.contains("\"event\":\"" + QuestionDriver.CONTINUED_BY_HOLD + "\""),
                                "no continued_by_hold event"),
                        () -> assertEquals("wait: end", ended.strip()));
            }
        }

        @Test
        @DisplayName("a quorum that decides after the asker exited leaves the continuation queued, and a new generation "
                + "takes it as before")
        void shouldStartGenerationAfterLateQuorumDecision() throws Exception {
            try (var driver = driver(QUORUMS, "exit 0")) {
                ask(driver).close();
                vote(driver, VOTE_A, "quorum-a");
                vote(driver, VOTE_B, "quorum-b");
                assertEquals(Optional.of("Q-001"), assertTimeoutPreemptively(Duration.ofSeconds(10),
                        () -> driver.awaitAnswer("Q-001", Duration.ofMinutes(15), Duration.ZERO, decision -> {
                            throw new IllegalStateException("the operator was asked");
                        })));

                var held = driver.continuedByHold("Q-001");
                var queued = driver.stub().state("T-1-Q-001");
                var continuation = driver.startLeased("T-1-Q-001", TASK, new Launch(WORKER, 2, ROLE, "Do it.",
                        false, null, List.of(), null));

                var events = QuestionResults.readJsonLines(driver.stub().events());
                assertAll("the late decision",
                        () -> assertEquals(Optional.empty(), held),
                        () -> assertEquals(Optional.of("task.queued"), queued),
                        () -> assertEquals(2, continuation.generation()),
                        () -> assertEquals(List.of(continuation), driver.workers()),
                        () -> assertTrue(events.stream().anyMatch(event -> "offered".equals(
                                event.path("event").asText()) && "T-1-Q-001".equals(event.path("task").asText())
                                && event.path("generation").asInt() == 2 && event.path("at_spawn").asBoolean()),
                                events.toString()),
                        () -> assertTrue(events.stream().noneMatch(event -> QuestionDriver.CONTINUED_BY_HOLD
                                .equals(event.path("event").asText())), events.toString()));
            }
        }

        @Test
        @DisplayName("the operator's path of the consult runs is unchanged: a continuation still queued, or taken and "
                + "not closed, is no continuation by hold")
        void shouldKeepOperatorPathWithoutContinuationByHold() throws Exception {
            try (var driver = driver(Map.of(), "exit 0")) {
                var asker = ask(driver);
                assertEquals(Optional.of("Q-001"), assertTimeoutPreemptively(Duration.ofSeconds(10),
                        () -> driver.awaitAnswer("Q-001", Duration.ofMinutes(15), Duration.ZERO,
                                decision -> List.of(answer("Q-001")))));

                var whileQueued = driver.continuedByHold("Q-001");
                asker.call("pm_wait", QuestionStub.JSON.createObjectNode());
                asker.link("ack", QuestionStub.JSON.createObjectNode());
                var whileActive = driver.continuedByHold("Q-001");

                var events = Files.readString(driver.stub().events());
                assertAll("the operator's path",
                        () -> assertEquals(Optional.empty(), whileQueued),
                        () -> assertEquals(Optional.empty(), whileActive),
                        () -> assertEquals(Optional.of("entity.active"), driver.stub().state("T-1-Q-001")),
                        () -> assertTrue(events.contains("\"channel\":\"cli\""), "not answered as the operator"),
                        () -> assertFalse(events.contains(QuestionDriver.CONTINUED_BY_HOLD), events));
            }
        }

        /** Ends the asker's hold as the run's end does ({@code end}, then its next {@code pm_wait}). */
        private static String endHold(QuestionDriver driver, Client asker) throws IOException {
            driver.stub().end(WORKER, 1);
            return asker.call("pm_wait", QuestionStub.JSON.createObjectNode());
        }

        private static ObjectNode submission() {
            var args = QuestionStub.JSON.createObjectNode();
            args.put("answer", "False");
            args.put("judgment", "Applied the decided reading of the contract.");
            return args;
        }

        /** The asker leased the task, acknowledged it and asked the example envelope (an interpretation). */
        private Client ask(QuestionDriver driver) throws IOException {
            driver.stub().queue(task(Form.FALLBACK));
            driver.stub().leaseAtSpawn(TASK, WORKER, 1);
            var asker = Client.connect(socket, WORKER, 1);
            clients.add(asker);
            asker.state();
            asker.link("ack", QuestionStub.JSON.createObjectNode());
            var asked = asker.ask("ask", validEnvelope());
            assertTrue(asked.contains("state: task.asked"), asked);
            return asker;
        }

        /** A voter leased its vote task, acknowledged it and voted for the description with confidence 95. */
        private void vote(QuestionDriver driver, String voteTask, String role) throws IOException {
            driver.stub().leaseAtSpawn(voteTask, voteTask, 1);
            var voter = Client.connect(socket, voteTask, 1, role);
            clients.add(voter);
            voter.state(voteTask);
            voter.link(voteTask, "ack", QuestionStub.JSON.createObjectNode());
            var args = QuestionStub.JSON.createObjectNode();
            var item = args.putArray("answers").addObject();
            item.put("question_id", "Q-001");
            item.put("choice", "description");
            item.put("confidence", 95);
            item.put("rationale", "The text states the intent of the function.");
            var voted = voter.link(voteTask, "submit:vote", args);
            assertTrue(voted.contains("state: entity.completed"), voted);
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

        @ParameterizedTest(name = "{0}")
        @EnumSource(Form.class)
        @DisplayName("an open, unanswered question of every form is queued for the operator again after a restart")
        void shouldRequeueOpenQuestionOfEveryForm(Form form) throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            activeWorker(form).ask("ask", validEnvelope());
            var before = stub.operatorDecisions().poll();
            stub.close();

            stub = start(QuestionStub.QUESTION_BOUND);
            var requeued = List.copyOf(stub.operatorDecisions());

            assertAll("the requeue of " + form,
                    () -> assertEquals("Q-001", before),
                    () -> assertEquals(List.of("Q-001"), requeued),
                    () -> assertEquals(AnswerResult.ANSWERED, stub.answer("Q-001", 1, List.of(answer("Q-001")))));
        }

        @Test
        @DisplayName("an open resume-form question is queued after a restart, and its answer makes the rebuilt task "
                + "resumable instead of continuing it in a new task")
        void shouldResumeRebuiltTaskAfterRestart() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            activeWorker(Form.RESUME).ask("ask", validEnvelope());
            stub.close();

            stub = start(QuestionStub.QUESTION_BOUND);
            var suspended = stub.state(TASK);
            var requeued = stub.operatorDecisions().peek();
            var answered = stub.answer("Q-001", 1, List.of(answer("Q-001")));

            assertAll("the resume after the restart",
                    () -> assertEquals(Optional.of("task.suspended"), suspended),
                    () -> assertEquals("Q-001", requeued),
                    () -> assertEquals(AnswerResult.ANSWERED, answered),
                    () -> assertEquals(Optional.of("task.resumable"), stub.state(TASK)),
                    () -> assertEquals(List.of(TASK), stub.taskIds()));
        }
    }

    @Nested
    @DisplayName("Q7 open time")
    class Q7OpenTime {

        private static final String CLAUDE_PROPERTY = QuestionDriver.EXECUTABLE_PROPERTY + "claude";
        private static final String WARM = "w-warm";
        private static final String CONTINUATION = "T-1-Q-001";

        private String previous;

        @BeforeEach
        void claudeExecutable() throws IOException {
            var claude = script(directory.resolve("bin").resolve("claude"), "exit 0");
            previous = System.getProperty(CLAUDE_PROPERTY);
            System.setProperty(CLAUDE_PROPERTY, claude.toString());
        }

        @AfterEach
        void restoreProperty() {
            if (previous == null) {
                System.clearProperty(CLAUDE_PROPERTY);
            } else {
                System.setProperty(CLAUDE_PROPERTY, previous);
            }
        }

        @Test
        @DisplayName("a held pm_wait the stopping stub ends is refused stub_stopped, not generation_fenced")
        void shouldRefuseHeldWaitAtStopAsStubStopped() throws Exception {
            stub = QuestionStub.start(Settings.of(directory.resolve("stub"), socket)
                    .withWindow(Duration.ofSeconds(30)));
            var client = Client.connect(socket, WORKER, 1);
            clients.add(client);
            var held = Thread.ofVirtual().start(() -> {
                try {
                    client.call("pm_wait", QuestionStub.JSON.createObjectNode());
                } catch (IOException _) {
                    // the stopping stub may close the connection before the refusal arrives
                }
            });
            awaitEvent(stub.events(), "wait_started");

            stub.close();
            held.join(10_000);

            var refused = QuestionResults.readJsonLines(stub.events()).stream()
                    .filter(event -> "refused".equals(event.path("event").asText())).toList();
            stub = null;
            assertAll("the refusal at the stop",
                    () -> assertEquals(1, refused.size(), refused.toString()),
                    () -> assertEquals(QuestionStub.STUB_STOPPED, refused.getFirst().path("reason").asText()),
                    () -> assertEquals(WORKER, refused.getFirst().path("worker").asText()));
        }

        @Test
        @DisplayName("the driver ends a fresh asker that still holds pm_wait at its silence limit, before the open "
                + "time, and records it")
        void shouldEndHoldingAskerBeforeOpenTime() throws Exception {
            try (var driver = driver("exec sleep 30")) {
                var asker = driver.startFresh(task(Form.FALLBACK), launch());
                var client = Client.connect(socket, WORKER, 1);
                clients.add(client);
                client.state();
                client.link("ack", QuestionStub.JSON.createObjectNode());
                client.ask("ask", validEnvelope());
                var askedAt = firstAt(driver.stub().events(), "asked");
                Thread.ofVirtual().start(() -> {
                    try {
                        client.call("pm_wait", QuestionStub.JSON.createObjectNode());
                    } catch (IOException _) {
                        // the connection ends with the test
                    }
                });
                awaitEvent(driver.stub().events(), "wait_started");

                var end = driver.endAsker(asker, askedAt, Duration.ofMillis(200));

                Thread.sleep(100);
                driver.stub().event("answer_recorded", Map.of("decision", "Q-001"));
                var events = QuestionResults.readJsonLines(driver.stub().events());
                var figures = QuestionResults.q7Run(new QuestionResults.RunView(run(), events, List.of(),
                        List.of()));
                var names = events.stream().map(event -> event.path("event").asText()).toList();
                assertAll("the ended asker",
                        () -> assertTrue(end.endedByDriver(), end.toString()),
                        () -> assertTrue(end.holdingWait(), end.toString()),
                        () -> assertTrue(end.afterAskMs() >= 200, end.toString()),
                        () -> assertFalse(asker.process().isAlive()),
                        () -> assertTrue(names.contains(QuestionDriver.ASKER_ENDED_BY_DRIVER), names.toString()),
                        () -> assertTrue(names.indexOf("worker_exited") < names.indexOf(
                                QuestionDriver.OPEN_TIME_STARTED), names.toString()),
                        () -> assertEquals(QuestionDriver.OPEN_TIME_STARTED, figures.path("open_time_from").asText()),
                        () -> assertEquals(0, figures.path(QuestionResults.HELD_WAIT_GENERATIONS).asInt(),
                                figures.toString()),
                        () -> assertEquals(0, figures.path(QuestionResults.WORKERS_CONNECTED_WHILE_OPEN).asInt(),
                                figures.toString()),
                        () -> assertEquals(0, figures.path(QuestionResults.HELD_WAIT_MS).asLong(), figures.toString()),
                        () -> assertEquals(0, figures.path(QuestionResults.LEASES_WHILE_OPEN).asInt(),
                                figures.toString()));
            }
        }

        @Test
        @DisplayName("an asker that exited by itself is not ended by the driver, and the open time starts at once")
        void shouldNotEndExitedAsker() throws Exception {
            try (var driver = driver("exit 0")) {
                var asker = driver.startFresh(task(Form.FALLBACK), launch());
                assertTrue(driver.awaitExit(asker, Duration.ofSeconds(10)).isPresent());

                var end = driver.endAsker(asker, System.currentTimeMillis(), Duration.ofSeconds(5));

                var names = QuestionResults.readJsonLines(driver.stub().events()).stream()
                        .map(event -> event.path("event").asText()).toList();
                assertAll("the asker that exited",
                        () -> assertFalse(end.endedByDriver(), end.toString()),
                        () -> assertFalse(end.holdingWait(), end.toString()),
                        () -> assertFalse(names.contains(QuestionDriver.ASKER_ENDED_BY_DRIVER), names.toString()),
                        () -> assertTrue(names.contains(QuestionDriver.OPEN_TIME_STARTED), names.toString()));
            }
        }

        @Test
        @DisplayName("an asker that held pm_wait through the open time and a restart counts as a held slot, and its "
                + "fenced, unacknowledged offer is reported")
        void shouldCountHeldWaitAsHeldSlot() {
            var view = new QuestionResults.RunView(run(), heldThroughOpenTime(true), List.of(), List.of());

            var figures = QuestionResults.q7Run(view);

            assertAll("the figures of the held asker",
                    () -> assertEquals("stop_wait_end", figures.path("open_time_from").asText()),
                    () -> assertEquals(220_000, figures.path("open_time_ms").asLong()),
                    () -> assertEquals(0, figures.path("offers_while_open").asInt()),
                    () -> assertEquals(1, figures.path(QuestionResults.HELD_WAIT_GENERATIONS).asInt()),
                    () -> assertEquals(1, figures.path(QuestionResults.WORKERS_CONNECTED_WHILE_OPEN).asInt()),
                    () -> assertEquals(20_000 + 99_001 + 96_900, figures.path(QuestionResults.HELD_WAIT_MS).asLong()),
                    () -> assertEquals(1, figures.path(QuestionResults.LEASES_WHILE_OPEN).asInt()),
                    () -> assertEquals("not recorded", figures.path(QuestionDriver.ASKER_ENDED_BY_DRIVER).asText()),
                    () -> assertEquals(2, figures.path("continuing_task_offers").asInt()),
                    () -> assertEquals(1, figures.path("fenced_unacknowledged_offers").asInt()),
                    () -> assertTrue(figures.path(QuestionResults.OFFER_COUNTED_TWICE_FENCED).asBoolean()),
                    () -> assertEquals(1, figures.path("refused_fenced_without_fence").asInt()));
        }

        @Test
        @DisplayName("a stub log without the end of the stop wait leaves the held figures unavailable")
        void shouldMarkFiguresUnavailableWithoutOpenTime() {
            var view = new QuestionResults.RunView(run(), heldThroughOpenTime(false), List.of(), List.of());

            var figures = QuestionResults.q7Run(view);

            assertAll("the unavailable figures",
                    () -> assertEquals(QuestionResults.UNAVAILABLE, figures.path(QuestionResults.HELD_WAIT_MS).asText()),
                    () -> assertEquals(QuestionResults.UNAVAILABLE,
                            figures.path(QuestionResults.WORKERS_CONNECTED_WHILE_OPEN).asText()),
                    () -> assertEquals(0, figures.path(QuestionResults.LEASES_WHILE_OPEN).asInt()));
        }

        @Test
        @DisplayName("the group reads leases_while_open with the held slot, reports the new figures beside it, and "
                + "keeps the criterion")
        void shouldReportHeldFiguresInGroup() {
            var held = new QuestionResults.RunView(run(), heldThroughOpenTime(true), List.of(), List.of());
            var unreadable = new QuestionResults.RunView(run(), heldThroughOpenTime(false), List.of(), List.of());
            var group = QuestionStub.JSON.createObjectNode();

            var pass = QuestionResults.q7(List.of(held, unreadable), group);

            assertAll("the group",
                    () -> assertFalse(pass),
                    () -> assertEquals(1, group.path(QuestionResults.LEASES_WHILE_OPEN).asInt()),
                    () -> assertEquals(1, group.path(QuestionResults.WORKERS_CONNECTED_WHILE_OPEN).asInt()),
                    () -> assertEquals(215_901, group.path(QuestionResults.HELD_WAIT_MS).asLong()),
                    () -> assertEquals(1, group.path("open_time_unavailable").asInt()),
                    () -> assertEquals(2, group.path(QuestionDriver.ASKER_ENDED_BY_DRIVER + "_not_recorded").asInt()),
                    () -> assertEquals(2, group.path(QuestionResults.FENCED_UNACKNOWLEDGED_OFFER_RUNS).asInt()),
                    () -> assertEquals(2, group.path(QuestionResults.Q7_RUNS).size()),
                    () -> assertEquals("No worker or slot held while the record is open; the answer delivered "
                            + "exactly once after the restart.", QuestionResults.CRITERIA.get("Q7")));
        }

        @Test
        @DisplayName("the limit names the driver's end of the asker as a harness action and the runs it applies to")
        void shouldNameAskerEndInLimit() {
            var ended = QuestionStub.JSON.createObjectNode();
            ended.put("run", "Q7-new-000");
            ended.put(QuestionDriver.ASKER_ENDED_BY_DRIVER, true);
            ended.put(QuestionResults.HELD_WAIT_MS, 0);
            var exited = QuestionStub.JSON.createObjectNode();
            exited.put("run", "Q7-new-001");
            exited.put(QuestionDriver.ASKER_ENDED_BY_DRIVER, false);
            exited.put(QuestionResults.HELD_WAIT_MS, 0);
            var old = QuestionResults.q7Run(new QuestionResults.RunView(run(), heldThroughOpenTime(true), List.of(),
                    List.of()));
            old.put("run", "Q7-old-002");
            var result = QuestionStub.JSON.createObjectNode();

            QuestionResults.limitOfQ7Figures(result, List.of(ended, exited, old));

            var limit = result.path("limits").path(0).asText();
            assertAll("the limit",
                    () -> assertTrue(limit.contains("a harness action"), limit),
                    () -> assertTrue(limit.contains("a finding of the run, not of the server"), limit),
                    () -> assertTrue(limit.contains("ended the asker in 1 of 2 runs (Q7-new-000)"), limit),
                    () -> assertTrue(limit.contains("Q7-old-002 215901 ms"), limit),
                    () -> assertTrue(limit.contains(QuestionResults.OFFER_COUNTED_TWICE_FENCED), limit),
                    () -> assertTrue(limit.contains(QuestionStub.STUB_STOPPED), limit));
        }

        @Test
        @DisplayName("the stub's stop ends every held wait, and an offer at spawn ends none")
        void shouldEndWaitsAtStubStop() {
            var events = List.of(event(1, "wait_started", WORKER, 1, Map.of()),
                    event(2, "offered", WORKER, 1, Map.of("task", TASK, "at_spawn", true)),
                    event(3, "stub_stopped", null, 0, Map.of()),
                    event(4, "wait_started", WORKER, 1, Map.of()));

            var waits = QuestionResults.waits(events);

            assertAll("the waits",
                    () -> assertEquals(new QuestionResults.Wait(WORKER, 1, 1, 3), waits.getFirst()),
                    () -> assertEquals(new QuestionResults.Wait(WORKER, 1, 4, Long.MAX_VALUE), waits.get(1)),
                    () -> assertTrue(QuestionResults.holdingWait(events, WORKER, 1)));
        }

        private QuestionDriver driver(String exec) throws IOException {
            var stubSettings = Settings.of(directory.resolve("stub"), socket).withWindow(Duration.ofSeconds(30));
            return QuestionDriver.start(stubSettings, new QuestionDriver.Settings(Host.CLAUDE, null,
                    directory.resolve("workers"), directory.resolve("run"),
                    List.of(script(directory.resolve("pm-exec"), exec).toString())));
        }

        private static Launch launch() {
            return new Launch(WORKER, 1, ROLE, "Do it.", false, null, List.of(), null);
        }

        private static ObjectNode run() {
            var run = QuestionStub.JSON.createObjectNode();
            run.put("item", "Q7");
            run.put("task_id", TASK);
            run.put("warm_worker", WARM);
            return run;
        }

        /**
         * The stub log of a Q7 run whose fresh asker {@code w1} generation 1 held {@code pm_wait} through its open
         * time, as in run {@code Q7-claude-fresh-question_apply-answer_he-106-002-aa970d29} of the Q7 smoke pass, in
         * milliseconds from its start: it asks (10 s) and holds {@code pm_wait} from 12 s; the driver's stop wait ends
         * at 280 s (its second {@code worktree_digest}, omitted when {@code stopWaitEnd} is false); a stub restart at
         * 400 s refuses its held wait {@code generation_fenced}; it reconnects and holds again until the answer
         * (500 s); the continuing task is offered over its wait, the driver's continuation fences it before it
         * acknowledged, and generation 2 takes the task at spawn. The warm worker's own wait is beside it.
         */
        private static List<JsonNode> heldThroughOpenTime(boolean stopWaitEnd) {
            var events = new ArrayList<JsonNode>();
            events.add(event(0, "connected", WORKER, 1, Map.of()));
            events.add(event(500, "connected", WARM, 1, Map.of()));
            events.add(event(1_000, "wait_started", WARM, 1, Map.of()));
            events.add(event(10_000, "asked", WORKER, 1, Map.of("task", TASK, "decision", "Q-001")));
            events.add(event(10_001, "closed", WORKER, 1, Map.of("task", TASK, "outcome", "asked")));
            events.add(event(10_002, "worktree_digest", WORKER, 1, Map.of()));
            events.add(event(12_000, "wait_started", WORKER, 1, Map.of()));
            if (stopWaitEnd) {
                events.add(event(280_000, "worktree_digest", WORKER, 1, Map.of()));
            }
            events.add(event(300_000, "wait_again", WORKER, 1, Map.of("reason", "window")));
            events.add(event(301_000, "wait_started", WORKER, 1, Map.of()));
            events.add(event(400_000, "stub_restart", null, 0, Map.of()));
            events.add(event(400_001, "stub_stopped", null, 0, Map.of()));
            events.add(event(400_002, "refused", WORKER, 1, Map.of("reason", "generation_fenced")));
            events.add(event(400_003, "disconnected", WORKER, 1, Map.of()));
            events.add(event(403_000, "connected", WORKER, 1, Map.of("fenced", false)));
            events.add(event(403_100, "wait_started", WORKER, 1, Map.of()));
            events.add(event(500_000, "answer_recorded", null, 0, Map.of("decision", "Q-001", "channel", "cli")));
            events.add(event(500_002, "continued", null, 0, Map.of("decision", "Q-001", "task", CONTINUATION)));
            events.add(event(500_003, "offered", WORKER, 1, Map.of("task", CONTINUATION, "at_spawn", false)));
            events.add(event(500_004, "fenced", WORKER, 1, Map.of("by", 2)));
            events.add(event(500_005, "released", WORKER, 1, Map.of("task", CONTINUATION, "reason", "fenced")));
            events.add(event(500_006, "offered", WORKER, 2, Map.of("task", CONTINUATION, "at_spawn", true)));
            events.add(event(500_007, QuestionDriver.SUPERSEDED_ENDED, WORKER, 1, Map.of("by", 2)));
            events.add(event(503_000, "worker_exited", WORKER, 1, Map.of("exit", 143)));
            events.add(event(505_000, "connected", WORKER, 2, Map.of()));
            events.add(event(510_000, "acknowledged", WORKER, 2, Map.of("task", CONTINUATION, "kind", "offer")));
            return events;
        }

        private static JsonNode event(long at, String name, String worker, int generation, Map<String, ?> fields) {
            var node = QuestionStub.JSON.createObjectNode();
            node.put("at", at);
            node.put("event", name);
            if (worker != null) {
                node.put("worker", worker);
                node.put("generation", generation);
            }
            fields.forEach((key, value) -> node.set(key, QuestionStub.JSON.valueToTree(value)));
            return node;
        }

        private static long firstAt(Path events, String name) throws IOException {
            return QuestionResults.readJsonLines(events).stream()
                    .filter(event -> name.equals(event.path("event").asText())).findFirst().orElseThrow()
                    .path("at").asLong();
        }

        private static void awaitEvent(Path events, String name) throws IOException, InterruptedException {
            var deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                if (QuestionResults.readJsonLines(events).stream()
                        .anyMatch(event -> name.equals(event.path("event").asText()))) {
                    return;
                }
                Thread.sleep(50);
            }
            throw new IllegalStateException("no event " + name + " in " + events);
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
                                "the plan-marshall tools are unavailable", false, false)), early),
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
                                "the pm_state, pm_wait and pm_do tools are not available", true, false)), early),
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

        @Test
        @DisplayName("a voter that exits without a call counts as voter_early_exit, not as early_exit, and the run "
                + "keeps its ask; the limits of Q6 state how the missing vote is decided")
        void shouldCountVoterEarlyExitApart() throws Exception {
            var voter = "vote-Q-001-quorum-b";
            var run = (ObjectNode) q3Run("voter", "fresh", false, "", event(500, "worker_started"),
                    event(600, QuestionDriver.CONNECTED), event(700, QuestionDriver.CALL), event(1_000, "asked"),
                    event(1_100, "worker_started").put("worker", voter), event(1_200, QuestionDriver.CONNECTED)
                            .put("worker", voter), event(1_300, "worker_exited").put("worker", voter).put("exit", 3),
                    event(1_400, "lost").put("task", voter), event(1_500, "closed").put("task", voter)
                            .put("outcome", "lost"), event(2_000, "continued"), event(3_000, "worker_exited"));
            run.put("item", "Q6");
            run.put("kind", "vote");

            var result = QuestionResults.result("Q6", List.of(run), List.of(invocation("i1", "Q6", 3, 3, 2)),
                    () -> 0);

            var group = result.path("groups").get(0);
            var limits = new ArrayList<String>();
            result.path("limits").forEach(limit -> limits.add(limit.asText()));
            assertAll("the result",
                    () -> assertEquals(1, group.path(QuestionResults.VOTER_EARLY_EXIT).asInt()),
                    () -> assertEquals(0, group.path(QuestionResults.EARLY_EXIT).asInt()),
                    () -> assertEquals(1, group.path("asked").asInt()),
                    () -> assertEquals(1, group.path("votes").asInt()),
                    () -> assertEquals(0, group.path("valid_votes").asInt()),
                    () -> assertTrue(limits.stream().anyMatch(limit -> limit.contains("escalates it with the reason "
                            + "role_unavailable, never as agreement") && limit.endsWith(": voter.")),
                            limits.toString()));
        }

        @Test
        @DisplayName("Q4 reports its cross-host runs per group and names the mixing of host and reply in its limits")
        void shouldReportCrossHostRuns() throws Exception {
            var cross = (ObjectNode) q3Run("cross", "fresh", false, "", event(1_000, "asked"),
                    event(2_000, "continued"), event(3_000, "submitted"));
            cross.put("continue_host", "opencode");
            cross.put("reply", "text");
            var same = (ObjectNode) q3Run("same", "fresh", false, "", event(1_000, "asked"),
                    event(2_000, "continued"));
            same.put("continue_host", "");
            same.put("reply", "option");

            var result = QuestionResults.result("Q4", List.of(cross, same), List.of(invocation("i1", "Q4", 3, 3,
                    2)), () -> 0);

            var groups = result.path("groups");
            var limits = new ArrayList<String>();
            result.path("limits").forEach(limit -> limits.add(limit.asText()));
            var option = groups.get(0).path(QuestionResults.CROSS_HOST_RUNS);
            var text = groups.get(1).path(QuestionResults.CROSS_HOST_RUNS);
            assertAll("the result",
                    () -> assertEquals(0, option.path("runs").asInt(-1)),
                    () -> assertEquals(1, text.path("runs").asInt()),
                    () -> assertEquals("[\"opencode\"]", text.path("continue_hosts").toString()),
                    () -> assertEquals(List.of(1, 1, 1), List.of(text.path("asked").asInt(),
                            text.path("continued").asInt(), text.path("submitted").asInt())),
                    () -> assertTrue(limits.stream().anyMatch(limit -> limit.endsWith("the cross-host runs "
                            + "(claude->opencode) had the replies text 1, the same-host runs option 1.")),
                            limits.toString()));
        }

        @Test
        @DisplayName("Q5 states that bound_reached is the planned flag of the form")
        void shouldStateBoundIsPlanned() {
            var result = verdict("pass");

            QuestionResults.limitOfQ5Bound(result);

            assertTrue(result.path("limits").get(0).asText().startsWith("bound_reached is the planned flag"),
                    result.toString());
        }

        @Test
        @DisplayName("a generation that connected twice while an older one still ran is re-identified; one after the "
                + "older exited is not")
        void shouldFindReidentifiedGeneration() throws Exception {
            var reidentified = q3Run("twice", "fresh", false, "", event(0, "worker_started"),
                    event(100, "worker_started").put("generation", 2),
                    event(110, QuestionDriver.CONNECTED).put("generation", 2),
                    event(120, QuestionDriver.CONNECTED).put("generation", 2),
                    event(200, "worker_exited").put("generation", 2), event(300, "worker_exited"));
            var afterExit = q3Run("after", "fresh", false, "", event(0, "worker_started"),
                    event(100, "worker_started").put("generation", 2), event(105, "worker_exited"),
                    event(110, QuestionDriver.CONNECTED).put("generation", 2),
                    event(120, QuestionDriver.CONNECTED).put("generation", 2));
            var result = verdict("pass");

            QuestionResults.limitOfReidentifiedGenerations(result, List.of(reidentified, afterExit));

            var limit = result.path("limits").get(0).asText();
            assertAll("the limit",
                    () -> assertEquals(1, result.path("limits").size()),
                    () -> assertTrue(limit.endsWith(": claude 1 (twice)."), limit));
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

    /**
     * The canary re-read of R1 and the figures of R1 and R2 beside their verdicts. The output lines are stored worker
     * outputs of the smoke passes under {@code pm-e2e/target/question-loop/runs/}, shortened to the fields the
     * detector reads; where a text was changed to name the canary file, the comment says so and the shape is the
     * stored one.
     */
    @Nested
    @DisplayName("R1 and R2 results")
    class R1R2Results {

        /** Claude Code {@code Read} of the canary (R1-claude-fresh-probe-resumability-001-868f38e9, line 48). */
        private static final String CLAUDE_READ = """
                {"type":"assistant","message":{"model":"claude-haiku-4-5-20251001","type":"message",\
                "role":"assistant","content":[{"type":"tool_use","id":"toolu_01Nnzxoo85Dbd9czYfbfLjXP","name":"Read",\
                "input":{"file_path":"/private/var/folders/rg/T/pm-qlv/R1-claude-fresh-probe-resumability-001-868f38e9\
                /workers/w1/canary.txt"},"caller":{"type":"direct"}}]},"parent_tool_use_id":null,\
                "session_id":"b81b563c-9b9e-49e5-958d-d5d589ed719e"}""";

        /** Claude Code's submit whose judgment names the canary (R1-claude-fresh-probe-resumability-000-e76e523e). */
        private static final String CLAUDE_SUBMIT = """
                {"type":"assistant","message":{"model":"claude-haiku-4-5-20251001","type":"message",\
                "role":"assistant","content":[{"type":"tool_use","id":"toolu_01FBFPFoaA1Jg1bVZ2WHRfVd",\
                "name":"mcp__plan-marshall__pm_do","input":{"plan_id":"qlv","entity":"T-1","link":"submit",\
                "args":{"answer":"canary-56afedbdb9643c99","judgment":"Task completed successfully. I read the \
                canary.txt file and retained its token."}},"caller":{"type":"direct"}}]},"parent_tool_use_id":null,\
                "session_id":"9ac7ee87-cc5d-4098-807b-f73bbd23c209","tool_use_meta":[{"id":"toolu_01FBFPFoaA1Jg1bVZ2WHRfVd",\
                "display_name":"Pm Do","server_display_name":"plan-marshall","tool_name":"pm_do"}]}""";

        /** The stub's representation, which quotes the task naming the canary (868f38e9, line 37). */
        private static final String CLAUDE_TOOL_RESULT = """
                {"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_018TtFrHUdLgNM2y32oukwtp",\
                "type":"tool_result","content":[{"type":"text","text":"state: task.answer\\nfacts:\\n  task: \\"Read \
                canary.txt of your working directory and keep its token.\\""}]}]},"parent_tool_use_id":null}""";

        /** Claude Code's prose in the stored shape of a text item; its text changed to name the canary. */
        private static final String CLAUDE_PROSE = """
                {"type":"assistant","message":{"model":"claude-haiku-4-5-20251001","type":"message",\
                "role":"assistant","content":[{"type":"text","text":"I will not read canary.txt again."}]}}""";

        /** OpenCode {@code read} of the canary (Q2-opencode-warm-question_ask-or-proceed_he-0-original-000-4e0451b0). */
        private static final String OPENCODE_READ = """
                {"type":"tool_use","timestamp":1792668201283,"sessionID":"ses_e9ca65464ffeAiLUq45JbhQYpJ",\
                "part":{"type":"tool","id":"call_qdF8BmPb7ZBn5a5RxPCT6vRw","tool":"read","state":{"status":"completed",\
                "input":{"path":"canary.txt","limit":200},"output":"Read file canary.txt, lines 1-1\\n1: \
                canary-d296209ad8bef1d9","title":"read"}}}""";

        /** OpenCode {@code read} of the directory, whose output lists the canary (Q1-opencode-…-3cd7db92). */
        private static final String OPENCODE_LISTING = """
                {"type":"tool_use","timestamp":1792649754936,"sessionID":"ses_e9dc48d0dffeeTHes4OBLzePRE",\
                "part":{"type":"tool","id":"call_wGvBZ6tVIzySiOjx7p76EdUj","tool":"read","state":{"status":"completed",\
                "input":{"path":"."},"output":"Read directory ., entries 1-2\\ncanary.txt\\nopencode.json",\
                "title":"read"}}}""";

        /**
         * OpenCode's submit through {@code execute} (Q1-opencode-…-3cd7db92, submit:TASK-002); its judgment changed
         * to name the canary.
         */
        private static final String OPENCODE_SUBMIT = """
                {"type":"tool_use","timestamp":1792649769978,"sessionID":"ses_e9dc48d0dffeeTHes4OBLzePRE",\
                "part":{"type":"tool","id":"call_bdPVj7OVOzDFriNb316Qxbdk","tool":"execute","state":{"status":"completed",\
                "input":{"code":"return await tools[\\"plan-marshall\\"].pm_do({plan_id:\\"qlv\\",entity:\\"T-1\\",\
                link:\\"submit\\",args:{answer:\\"False\\",judgment:\\"Read canary.txt before the ask.\\"}});"},\
                "output":"state: entity.completed","title":"execute"}}}""";

        /** Antigravity's final object (Q1-agy-fresh-question_apply-answer_he-0-000-5632eacb); response changed. */
        private static final String ANTIGRAVITY_RESULT = """
                {"conversation_id":"444e4726-420e-48d4-838c-36539b370773","status":"SUCCESS",\
                "response":"I read canary.txt once before the ask.","duration_seconds":297.726415,"num_turns":1,\
                "usage":{"input_tokens":193040,"output_tokens":6957,"total_tokens":199997}}""";

        @Test
        @DisplayName("Claude Code: a Read of the canary counts; the submit, the stub's facts and prose that name it "
                + "do not")
        void shouldDetectClaudeCodeRead() {
            var file = QuestionFixtures.CANARY_FILE;

            assertAll("Claude Code",
                    () -> assertTrue(QuestionResults.readsFile(CLAUDE_READ, file)),
                    () -> assertFalse(QuestionResults.readsFile(CLAUDE_SUBMIT, file)),
                    () -> assertFalse(QuestionResults.readsFile(CLAUDE_TOOL_RESULT, file)),
                    () -> assertFalse(QuestionResults.readsFile(CLAUDE_PROSE, file)));
        }

        @Test
        @DisplayName("OpenCode: a read of the canary counts; a listing that names it and a submit through execute do "
                + "not")
        void shouldDetectOpenCodeRead() {
            var file = QuestionFixtures.CANARY_FILE;

            assertAll("OpenCode",
                    () -> assertTrue(QuestionResults.readsFile(OPENCODE_READ, file)),
                    () -> assertFalse(QuestionResults.readsFile(OPENCODE_LISTING, file)),
                    () -> assertFalse(QuestionResults.readsFile(OPENCODE_SUBMIT, file)));
        }

        @Test
        @DisplayName("Antigravity: its final object carries no tool call, so nothing in it counts (not verified)")
        void shouldNotDetectAntigravityRead() {
            assertFalse(QuestionResults.readsFile(ANTIGRAVITY_RESULT, QuestionFixtures.CANARY_FILE));
        }

        @Test
        @DisplayName("only a read after the ask counts, and a line that is no JSON never counts")
        void shouldCountReadAfterAskOnly() {
            var before = List.<JsonNode>of(stampedAt(100, CLAUDE_READ), stampedAt(300, CLAUDE_SUBMIT),
                    stampedAt(300, "canary.txt \"tool_use\""));
            var after = List.<JsonNode>of(stampedAt(300, CLAUDE_SUBMIT), stampedAt(400, OPENCODE_READ));

            assertAll("the re-read",
                    () -> assertFalse(QuestionResults.rereadsCanary(before, 200)),
                    () -> assertTrue(QuestionResults.rereadsCanary(after, 200)));
        }

        @Test
        @DisplayName("the longest passing duration is reported per form beside the pooled figure")
        void shouldReportLongestPassingPerForm() {
            var groups = QuestionStub.JSON.createArrayNode();
            groups.add(group("fresh", 3600, "pass"));
            groups.add(group("fresh", 1800, "pass"));
            groups.add(group("warm", 3600, "fail"));
            groups.add(group("warm", 1800, "pass"));

            var perForm = QuestionResults.longestPassingPerForm(groups, "duration_s");

            assertAll("per form",
                    () -> assertEquals(3600, perForm.path("fresh").path("claude m").asInt()),
                    () -> assertEquals(1800, perForm.path("warm").path("claude m").asInt()));
        }

        @Test
        @DisplayName("an R2 group splits its runs by restart without changing its key or verdict")
        void shouldBreakDownRestartRuns() throws Exception {
            var submitted = event(4, "submitted");
            submitted.putObject("args").put("answer", "canary-1");
            var continued = List.<JsonNode>of(event(1, "asked"), answerDelivered(2), event(3, "acknowledged")
                    .put("kind", "answer"), submitted);
            var plain = new QuestionResults.RunView(r2Run(false, false), continued, List.of(), List.of());
            var restarted = new QuestionResults.RunView(r2Run(true, true), List.<JsonNode>of(event(1, "asked")),
                    List.of(), List.of());
            var group = QuestionStub.JSON.createObjectNode();

            QuestionResults.restartBreakdown(List.of(plain, restarted), group);

            var breakdown = group.path(QuestionResults.RESTART_BREAKDOWN);
            assertAll("the breakdown",
                    () -> assertEquals(List.of(1, 1, 0, 0, 1), figures(breakdown.path("true"))),
                    () -> assertEquals(List.of(1, 1, 1, 1, 0), figures(breakdown.path("false"))));
        }

        @Test
        @DisplayName("R2 names the age of 24 hours as not run when its invocations did not run it")
        void shouldNameMissingDayAge() {
            var without = QuestionStub.JSON.createObjectNode();
            without.putArray("r2_ages_s").add(300).add(3600);
            var withDay = QuestionStub.JSON.createObjectNode();
            withDay.putArray("r2_ages_s").add(300).add(86_400);
            var dropped = QuestionStub.JSON.createObjectNode();
            var run = QuestionStub.JSON.createObjectNode();
            var kept = QuestionStub.JSON.createObjectNode();

            QuestionResults.limitOfR2Ages(dropped, List.of(run), List.of(without));
            QuestionResults.limitOfR2Ages(kept, List.of(run), List.of(withDay));

            assertAll("the limit",
                    () -> assertEquals("the age 24 hours was not run, operator decision; the criterion text is "
                            + "unchanged", dropped.path("limits").get(0).asText()),
                    () -> assertTrue(kept.path("limits").isMissingNode(), kept.toString()));
        }

        private static JsonNode stampedAt(long at, String line) {
            var node = QuestionStub.JSON.createObjectNode();
            node.put("at", at);
            node.put("line", line);
            return node;
        }

        private static ObjectNode group(String form, int duration, String verdict) {
            var node = QuestionStub.JSON.createObjectNode();
            node.put("host", "claude");
            node.put("model", "m");
            node.put("form", form);
            node.put("duration_s", duration);
            node.put("verdict", verdict);
            return node;
        }

        private static ObjectNode event(long at, String name) {
            var node = QuestionStub.JSON.createObjectNode();
            node.put("at", at);
            node.put("event", name);
            node.put("worker", WORKER);
            node.put("generation", 1);
            return node;
        }

        private static ObjectNode answerDelivered(long at) {
            return event(at, "answer_delivered").put("resumed", true);
        }

        private static ObjectNode r2Run(boolean restart, boolean timedOut) {
            var run = QuestionStub.JSON.createObjectNode();
            run.put("item", "R2");
            run.put("host", "claude");
            run.put("form", "fresh");
            run.put("age_s", 300);
            run.put("restart", restart);
            run.put("negative", false);
            run.put("canary", "canary-1");
            run.put(QuestionResults.TIMED_OUT, timedOut);
            return run;
        }

        private static List<Integer> figures(JsonNode part) {
            return List.of("runs", "asked", "continued_from_session", "submitted", "timed_out").stream()
                    .map(field -> part.path(field).asInt(-1)).toList();
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

    @Nested
    @DisplayName("continuation by hold")
    class ContinuationByHold {

        private static final String CONTINUATION = "T-1-Q-001";

        @Test
        @DisplayName("reads the sequence of the second Q6 attempt's vote run as a continuation made by hold")
        void shouldReadDefectRunSequence() {
            var events = log("w1", 1, false, true);

            var held = QuestionDriver.heldContinuation(events, "Q-001");

            assertEquals(Optional.of(new QuestionDriver.HeldContinuation("Q-001", CONTINUATION, "w1", 1, "submitted",
                    81_401L, 81_400L, "quorum")), held);
        }

        @ParameterizedTest(name = "closed by {0} generation {1}, offered at spawn {2} -> by hold {3}")
        @CsvSource({
                "w1, 1, false, true",
                "w1, 2, false, true",
                "w1, 2, true, false",
                "w-warm, 1, false, false"})
        @DisplayName("only a generation of the asking worker that took the continuation over pm_wait and closed it "
                + "made it by hold")
        void shouldRecogniseOnlyAskingWorkerOverWait(String worker, int generation, boolean atSpawn,
                boolean expected) {
            var events = log(worker, generation, atSpawn, true);

            assertEquals(expected, QuestionDriver.heldContinuation(events, "Q-001").isPresent());
        }

        @Test
        @DisplayName("a continuation taken over pm_wait but not closed is not made by hold")
        void shouldIgnoreOpenContinuation() {
            var events = log("w1", 1, false, false);

            assertEquals(Optional.empty(), QuestionDriver.heldContinuation(events, "Q-001"));
        }

        @Test
        @DisplayName("the result names a run continued by hold in its limits and keeps its verdict logic")
        void shouldNameContinuationByHoldInLimits() {
            var held = QuestionStub.JSON.createObjectNode();
            held.put("host", "claude");
            held.put("stub_dir", "/tmp/pm-qlv/Q6-claude-fresh-vote-000/stub");
            held.put(QuestionDriver.CONTINUED_BY_HOLD, true);
            var other = QuestionStub.JSON.createObjectNode();
            other.put("host", "claude");
            other.put("stub_dir", "/tmp/pm-qlv/Q6-claude-fresh-consult-000/stub");
            var withHeld = QuestionStub.JSON.createObjectNode();
            var withoutHeld = QuestionStub.JSON.createObjectNode();

            QuestionResults.limitOfContinuationsByHold(withHeld, List.of(held, other));
            QuestionResults.limitOfContinuationsByHold(withoutHeld, List.of(other));

            var limit = withHeld.path("limits").path(0).asText();
            assertAll("the limit",
                    () -> assertTrue(limit.contains("claude 1 (Q6-claude-fresh-vote-000)"), limit),
                    () -> assertTrue(limit.contains(QuestionDriver.CONTINUED_BY_HOLD), limit),
                    () -> assertTrue(limit.contains("counts as a continuation by hold"), limit),
                    () -> assertFalse(limit.contains("consult"), limit),
                    () -> assertFalse(withoutHeld.has("limits"), withoutHeld.toString()));
        }

        /**
         * The stub log of the vote run of the second Q6 attempt, as far as the rule reads it, with the times of that
         * run in milliseconds from its start: the asker {@code w1} generation 1 asks Q-001 (31.9 s); the quorum
         * decides and the answer is recorded through it (60.4 s); the continuation is created and offered over
         * {@code pm_wait} (60.4 s), acknowledged (67.2 s), submitted and closed (81.4 s), here by the given worker and
         * generation.
         */
        private static List<JsonNode> log(String worker, int generation, boolean atSpawn, boolean closed) {
            var events = new ArrayList<JsonNode>();
            events.add(event(31_869L, "asked", "w1", 1, Map.of("task", TASK, "decision", "Q-001", "form", "FALLBACK")));
            events.add(event(60_403L, "adjudicated", null, 0, Map.of("decision", "Q-001", "outcome", "decided")));
            events.add(event(60_416L, "answer_recorded", null, 0, Map.of("decision", "Q-001", "channel", "quorum")));
            events.add(event(60_417L, "continued", null, 0, Map.of("decision", "Q-001", "task", CONTINUATION,
                    "entity", TASK)));
            events.add(event(60_426L, "offered", worker, generation, Map.of("task", CONTINUATION, "at_spawn",
                    atSpawn)));
            events.add(event(67_246L, "acknowledged", worker, generation, Map.of("task", CONTINUATION)));
            if (closed) {
                events.add(event(81_400L, "submitted", worker, generation, Map.of("task", CONTINUATION)));
                events.add(event(81_401L, "closed", worker, generation, Map.of("task", CONTINUATION, "outcome",
                        "submitted")));
            }
            return events;
        }

        private static JsonNode event(long at, String name, String worker, int generation, Map<String, ?> fields) {
            var node = QuestionStub.JSON.createObjectNode();
            node.put("at", at);
            node.put("event", name);
            if (worker != null) {
                node.put("worker", worker);
                node.put("generation", generation);
            }
            fields.forEach((key, value) -> node.set(key, QuestionStub.JSON.valueToTree(value)));
            return node;
        }
    }

    /**
     * The faults of R3 against the live stub, with a scripted worker over the socket as the host, a fake
     * {@code pm-exec} ({@code exec sleep 60}) as its process, and no model. The detections wait out Claude Code's
     * acknowledgement deadline of 15 s.
     */
    @Nested
    @DisplayName("R3 faults")
    class R3Faults {

        private static final String CLAUDE_PROPERTY = QuestionDriver.EXECUTABLE_PROPERTY + "claude";
        private static final String CONTINUATION = "T-1-Q-001";
        private static final String EVENT = "event";
        private static final String LOST_DETECTED = QuestionDriver.LOST_DETECTED;
        private static final Duration WINDOW = Duration.ofSeconds(45);
        private static final Duration DELIVERY = Duration.ofSeconds(10);

        private String previous;

        @BeforeEach
        void claudeExecutable() throws IOException {
            var claude = script(directory.resolve("bin").resolve("claude"), "exit 0");
            previous = System.getProperty(CLAUDE_PROPERTY);
            System.setProperty(CLAUDE_PROPERTY, claude.toString());
        }

        @AfterEach
        void restoreProperty() {
            if (previous == null) {
                System.clearProperty(CLAUDE_PROPERTY);
            } else {
                System.setProperty(CLAUDE_PROPERTY, previous);
            }
        }

        @Test
        @DisplayName("STOP_BEFORE_ANSWER: stops the asker only once it holds pm_wait; the missing acknowledgement "
                + "detects it at the deadline, and the task is offered again with facts.answers once")
        void shouldStopOnlyWhileHolding() throws Exception {
            try (var driver = driver(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var worker = startAsker(driver, Form.HOLD);
                var asker = askingClient(worker);
                var askedAt = askedAt(driver);
                var stop = executor.submit(() -> driver.stopWhileHolding(worker, askedAt, DELIVERY));
                Thread.sleep(500);
                var beforeHold = named(driver, QuestionDriver.FAULT_INJECTED);

                asker.request("pm_wait", QuestionStub.JSON.createObjectNode());
                var held = stop.get(DELIVERY.toSeconds(), TimeUnit.SECONDS);
                answerAsOperator(driver);
                var acknowledged = driver.superviseAnswer(worker, DELIVERY);
                var recovery = driver.recovery(worker, "Q-001", TASK);
                var continued = continuationText(driver);

                var events = QuestionResults.readJsonLines(driver.stub().events());
                var waitStarted = first(events, "wait_started");
                var injected = first(events, QuestionDriver.FAULT_INJECTED);
                var lost = first(events, LOST_DETECTED);
                assertAll("the stop while holding",
                        () -> assertEquals(List.of(), beforeHold, "stopped before the hold"),
                        () -> assertTrue(held.isPresent(), events.toString()),
                        () -> assertEquals("STOP_BEFORE_ANSWER", injected.path("fault").asText()),
                        () -> assertTrue(injected.path("held").asBoolean(), injected.toString()),
                        () -> assertEquals(waitStarted.path("at").asLong(), injected.path("hold_started_at").asLong()),
                        () -> assertTrue(events.indexOf(waitStarted) < events.indexOf(injected), events.toString()),
                        () -> assertTrue(events.indexOf(injected) < events.indexOf(first(events, "answer_delivered")),
                                events.toString()),
                        () -> assertFalse(acknowledged),
                        () -> assertEquals("answer_ack_missing", lost.path("by").asText()),
                        () -> assertTrue(lost.path("after_ms").asLong() >= Host.CLAUDE.ackDeadline().toMillis(),
                                lost.toString()),
                        () -> assertEquals("driver", lost.path("first_by").asText()),
                        () -> assertTrue(events.indexOf(lost) < events.indexOf(first(events, "continued")),
                                "the continuation precedes the detection: " + events),
                        () -> assertEquals(1, count(events, "continued")),
                        () -> assertEquals(QuestionDriver.RecoveryStep.CONTINUE, recovery.step()),
                        () -> assertEquals(CONTINUATION, recovery.task()),
                        () -> assertTrue(continued.contains("Q-001,null,"), continued));
            }
        }

        @Test
        @DisplayName("STOP_BEFORE_ANSWER: an asker that never holds pm_wait is not stopped, and the run says so")
        void shouldNotStopWithoutHold() throws Exception {
            try (var driver = driver()) {
                var worker = startAsker(driver, Form.HOLD);
                askingClient(worker);

                var held = driver.stopWhileHolding(worker, askedAt(driver), Duration.ofMillis(500));

                var events = QuestionResults.readJsonLines(driver.stub().events());
                assertAll("no stop",
                        () -> assertEquals(Optional.empty(), held),
                        () -> assertEquals(List.of(), named(driver, QuestionDriver.FAULT_INJECTED)),
                        () -> assertEquals("no_hold", first(events, QuestionDriver.FAULT_NOT_INJECTED)
                                .path("reason").asText()),
                        () -> assertTrue(worker.process().isAlive()));
            }
        }

        @Test
        @DisplayName("CALL_CUT: a fake host that honours its tool timeout cuts the held call; the cut is logged as "
                + "the fault with its evidence, and the missing acknowledgement detects the loss")
        void shouldDetectCallCutByHost() throws Exception {
            var toolTimeout = Duration.ofSeconds(1);
            var setting = new QuestionDriver.CutSetting(toolTimeout, toolTimeout,
                    QuestionDriver.CONFIGURED_TOOL_TIMEOUT);
            try (var driver = driver(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var worker = startAsker(driver, Form.HOLD);
                var host = askingClient(worker).relayingTo(worker.relayLog());
                var askedAt = askedAt(driver);
                var watch = executor.submit(() -> driver.awaitCallCut(worker, askedAt,
                        System.currentTimeMillis() + DELIVERY.toMillis(), setting));

                var held = host.request("pm_wait", QuestionStub.JSON.createObjectNode());
                Thread.sleep(toolTimeout.plusMillis(100));
                host.cancel(held);
                var cut = watch.get(DELIVERY.toSeconds(), TimeUnit.SECONDS);
                answerAsOperator(driver);
                var acknowledged = driver.superviseAnswer(worker, DELIVERY);

                var events = QuestionResults.readJsonLines(driver.stub().events());
                var injected = first(events, QuestionDriver.FAULT_INJECTED);
                var lost = first(events, LOST_DETECTED);
                assertAll("the cut",
                        () -> assertTrue(cut.isPresent(), events.toString()),
                        () -> assertEquals(QuestionDriver.CUT_CANCELLED, cut.orElseThrow().evidence()),
                        () -> assertTrue(cut.orElseThrow().afterMs() >= toolTimeout.toMillis(), cut.toString()),
                        () -> assertEquals("CALL_CUT", injected.path("fault").asText()),
                        () -> assertEquals("host", injected.path("cut_by").asText()),
                        () -> assertEquals(QuestionDriver.CUT_CANCELLED, injected.path("evidence").asText()),
                        () -> assertEquals(QuestionDriver.CONFIGURED_TOOL_TIMEOUT, injected.path("means").asText()),
                        () -> assertEquals(toolTimeout.toMillis(), injected.path("tool_timeout_ms").asLong()),
                        () -> assertFalse(acknowledged),
                        () -> assertEquals("answer_ack_missing", lost.path("by").asText()),
                        () -> assertTrue(lost.path("after_ms").asLong() >= Host.CLAUDE.ackDeadline().toMillis(),
                                lost.toString()),
                        () -> assertEquals(1, count(events, "continued")));
            }
        }

        @Test
        @DisplayName("CALL_CUT: a host that calls pm_wait again after the cut gets the answer over the new call; the "
                + "cut is recorded, and no loss")
        void shouldRecordCutRecoveredByNewWait() throws Exception {
            var setting = QuestionDriver.cutSetting(Host.CLAUDE);
            try (var driver = driver(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var worker = startAsker(driver, Form.HOLD);
                var host = askingClient(worker);
                var askedAt = askedAt(driver);
                var watch = executor.submit(() -> driver.awaitCallCut(worker, askedAt,
                        System.currentTimeMillis() + DELIVERY.toMillis(), setting));

                host.request("pm_wait", QuestionStub.JSON.createObjectNode());
                Thread.sleep(500);
                var again = host.request("pm_wait", QuestionStub.JSON.createObjectNode());
                var cut = watch.get(DELIVERY.toSeconds(), TimeUnit.SECONDS);
                answerAsOperator(driver);
                var delivered = host.text(again);
                host.link("ack", QuestionStub.JSON.createObjectNode());
                var acknowledged = driver.superviseAnswer(worker, DELIVERY);

                assertAll("the cut recovered by the worker",
                        () -> assertEquals(QuestionDriver.CUT_SUPERSEDED, cut.orElseThrow().evidence()),
                        () -> assertTrue(delivered.contains("state: task.answer"), delivered),
                        () -> assertTrue(acknowledged),
                        () -> assertEquals(List.of(), named(driver, LOST_DETECTED)));
            }
        }

        @Test
        @DisplayName("CALL_CUT: a held call the host does not cut is logged as not cut, never as the fault")
        void shouldLogCallNotCut() throws Exception {
            var setting = QuestionDriver.cutSetting(Host.CLAUDE);
            try (var driver = driver()) {
                var worker = startAsker(driver, Form.HOLD);
                askingClient(worker).relayingTo(worker.relayLog()).request("pm_wait",
                        QuestionStub.JSON.createObjectNode());
                var askedAt = askedAt(driver);

                var cut = driver.awaitCallCut(worker, askedAt, System.currentTimeMillis() + 1000, setting);

                var events = QuestionResults.readJsonLines(driver.stub().events());
                var notCut = first(events, QuestionDriver.CALL_NOT_CUT);
                assertAll("no cut",
                        () -> assertEquals(Optional.empty(), cut),
                        () -> assertEquals(List.of(), named(driver, QuestionDriver.FAULT_INJECTED)),
                        () -> assertTrue(notCut.path("held").asBoolean(), notCut.toString()),
                        () -> assertEquals(QuestionDriver.CONFIGURED_TOOL_TIMEOUT, notCut.path("means").asText()));
            }
        }

        @Test
        @DisplayName("CALL_CUT: the R3 smoke attempt's held call (30 s, progress every 5 s, no cancellation) is no "
                + "cut; a cancellation of another request is none either")
        void shouldReadAttemptSequenceAsNoCut() {
            var events = List.of(logEvent(31_800L, "asked", Map.of("task", TASK, "decision", "Q-001")),
                    logEvent(34_100L, "wait_started", Map.of()),
                    logEvent(61_900L, "answer_delivered", Map.of("task", TASK, "decision", "Q-001")));
            var waitCall = relayEntry(34_090L, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"pm_wait\",\"arguments\":{},\"_meta\":{\"progressToken\":5}}}");
            var progress = relayEntry(39_100L, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\","
                    + "\"params\":{\"progressToken\":5,\"progress\":1}}");
            var otherCancel = relayEntry(40_000L, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
                    + "\"params\":{\"requestId\":4}}");
            var cancel = relayEntry(40_000L, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
                    + "\"params\":{\"requestId\":5}}");

            assertAll("the attempt's sequence",
                    () -> assertEquals(Optional.empty(), QuestionDriver.callCut(events, List.of(waitCall, progress),
                            WORKER, 1, 31_800L)),
                    () -> assertEquals(Optional.empty(), QuestionDriver.callCut(events, List.of(waitCall,
                            otherCancel), WORKER, 1, 31_800L)),
                    () -> assertEquals(Optional.of(new QuestionDriver.CallCut(WORKER, 1, 34_100L, 40_000L,
                            QuestionDriver.CUT_CANCELLED)), QuestionDriver.callCut(events, List.of(waitCall, cancel),
                            WORKER, 1, 31_800L)));
        }

        @ParameterizedTest(name = "{0}: tool timeout {1} ms, cut at {2} s, hold {3} s, window {4} s, {5}")
        @CsvSource({
                "CLAUDE, 60000, 60, 90, 240, configured_tool_timeout",
                "OPENCODE, 60000, 60, 90, 240, configured_tool_timeout",
                "ANTIGRAVITY, -1, 180, 210, 240, host_call_limit"})
        @DisplayName("CALL_CUT: the held call outlasts the host's cut, without progress, and the stub's window "
                + "outlasts the hold")
        void shouldPlanCutPerHost(Host host, long toolTimeoutMs, long cutSeconds, long holdSeconds,
                long windowSeconds, String means) {
            var setting = QuestionDriver.cutSetting(host);
            var plan = r3Plan(host, Fault.CALL_CUT);

            var stubSettings = QuestionLoopExperiment.stubSettings(plan, directory.resolve("stub"), socket);

            assertAll("the cut setting of " + host,
                    () -> assertEquals(toolTimeoutMs, setting.toolTimeout() == null ? -1
                            : setting.toolTimeout().toMillis()),
                    () -> assertEquals(toolTimeoutMs < 0 ? null : Duration.ofMillis(toolTimeoutMs),
                            QuestionLoopExperiment.toolTimeout(plan)),
                    () -> assertEquals(cutSeconds, setting.hostCut().toSeconds()),
                    () -> assertEquals(holdSeconds, plan.hold().toSeconds()),
                    () -> assertEquals(windowSeconds, stubSettings.window().toSeconds()),
                    () -> assertTrue(stubSettings.progressEvery().compareTo(stubSettings.window()) > 0,
                            "a progress frame within the window"),
                    () -> assertEquals(means, setting.means()));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Fault.class, names = {"STOP_BEFORE_ANSWER", "MODEL_HANG", "KILL", "RESUMED_EXIT"})
        @DisplayName("the other faults keep the host's window, progress every 5 s, the 30 s hold and the host's "
                + "tool timeout")
        void shouldKeepOtherFaultSettings(Fault fault) {
            var plan = r3Plan(Host.CLAUDE, fault);

            var stubSettings = QuestionLoopExperiment.stubSettings(plan, directory.resolve("stub"), socket);

            assertAll("the settings of " + fault,
                    () -> assertEquals(Host.CLAUDE.boundedWait(), stubSettings.window()),
                    () -> assertEquals(Duration.ofSeconds(5), stubSettings.progressEvery()),
                    () -> assertEquals(QuestionLoopExperiment.R3_HOLD, plan.hold()),
                    () -> assertNull(QuestionLoopExperiment.toolTimeout(plan)));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Fault.class, names = {"MODEL_HANG", "KILL"})
        @DisplayName("MODEL_HANG and KILL: injected once the answer is delivered over the held wait, detected by the "
                + "missing acknowledgement at the deadline, and continued in the stub's new task as before")
        void shouldDetectHangAndKillAsBefore(Fault fault) throws Exception {
            try (var driver = driver()) {
                var worker = startAsker(driver, Form.HOLD);
                askingClient(worker).request("pm_wait", QuestionStub.JSON.createObjectNode());
                answerAsOperator(driver);
                driver.awaitEvent(event -> "answer_delivered".equals(event.path(EVENT).asText()),
                        System.currentTimeMillis() + DELIVERY.toMillis());
                driver.inject(worker, fault);

                var acknowledged = driver.superviseAnswer(worker, DELIVERY);
                var recovery = driver.recovery(worker, "Q-001", TASK);

                var events = QuestionResults.readJsonLines(driver.stub().events());
                var lost = first(events, LOST_DETECTED);
                assertAll("the detection of " + fault,
                        () -> assertFalse(acknowledged),
                        () -> assertEquals("answer_ack_missing", lost.path("by").asText()),
                        () -> assertTrue(lost.path("after_ms").asLong() >= Host.CLAUDE.ackDeadline().toMillis(),
                                lost.toString()),
                        () -> assertEquals(fault == Fault.KILL ? "stub" : "driver", lost.path("first_by").asText()),
                        () -> assertEquals(1, count(events, "continued")),
                        () -> assertEquals(QuestionDriver.RecoveryStep.CONTINUE, recovery.step()),
                        () -> assertEquals(CONTINUATION, recovery.task()));
            }
        }

        @Test
        @DisplayName("RESUMED_EXIT: when the stub continued the task at the resumed process's exit, the driver starts "
                + "no second resume of the closed task, a generation only for the queued continuation, and none "
                + "once a generation holds it")
        void shouldNotResumeTaskStubContinued() throws Exception {
            try (var driver = driver()) {
                var asker = startAsker(driver, Form.RESUME);
                askingClient(asker).request("pm_wait", QuestionStub.JSON.createObjectNode());
                answerAsOperator(driver);
                var resumed = driver.resume(asker, TASK, "session-1", 2);
                driver.inject(resumed, Fault.RESUMED_EXIT);
                driver.awaitEvent(event -> "continued".equals(event.path(EVENT).asText()),
                        System.currentTimeMillis() + DELIVERY.toMillis());

                var acknowledged = driver.superviseAnswer(resumed, DELIVERY);
                var recovery = driver.recovery(resumed, "Q-001", TASK);
                var continuation = driver.startLeased(recovery.task(), recovery.entity(),
                        new Launch(WORKER, 3, ROLE, "Do it.", true, null, List.of(), null));
                var afterStart = driver.recovery(resumed, "Q-001", TASK);

                var events = QuestionResults.readJsonLines(driver.stub().events());
                var lost = first(events, LOST_DETECTED);
                assertAll("the recovery",
                        () -> assertFalse(acknowledged),
                        () -> assertEquals("stub", lost.path("first_by").asText(), lost.toString()),
                        () -> assertEquals("exited", lost.path("stub_reason").asText()),
                        () -> assertTrue(lost.path("stub_after_ms").asLong() < Host.CLAUDE.ackDeadline().toMillis(),
                                lost.toString()),
                        () -> assertEquals(QuestionDriver.RecoveryStep.CONTINUE, recovery.step()),
                        () -> assertEquals(CONTINUATION, recovery.task()),
                        () -> assertEquals(TASK, recovery.entity()),
                        () -> assertEquals(3, continuation.generation()),
                        () -> assertEquals(QuestionDriver.RecoveryStep.TAKEN, afterStart.step()),
                        () -> assertEquals(WORKER + "#3", afterStart.holder()),
                        () -> assertEquals(Optional.of("task.lost"), driver.stub().state(TASK)),
                        () -> assertEquals(1, count(events, "continued")),
                        () -> assertTrue(events.stream().noneMatch(event -> TASK.equals(event.path("task").asText())
                                && event.path("generation").asInt() == 3), "the closed task was leased again"));
            }
        }

        @ParameterizedTest(name = "lost task {0}, last held by {1} -> {2}")
        @CsvSource({
                "task.resumable, w1#2, RESUME",
                "task.lost, w1#2, WAIT",
                "task.queued, w1#2, WAIT",
                "entity.active, w1#3, TAKEN",
                "entity.completed, w1#3, TAKEN"})
        @DisplayName("without a continuation of the stub, the lost task's state decides: resumable is resumed again, "
                + "held or closed by another generation is left alone")
        void shouldDecideRecoveryWithoutContinuation(String state, String holder, String expected) {
            var generation = Integer.parseInt(holder.substring(holder.indexOf('#') + 1));
            var events = List.of(logEvent(1_000L, "answer_delivered", Map.of("task", TASK, "at_spawn", true)),
                    eventOf(2_000L, "offered", generation, Map.of("task", TASK, "at_spawn", true)));

            var recovery = QuestionDriver.recovery(events, "Q-001", TASK, WORKER + "#2",
                    task -> TASK.equals(task) ? Optional.of(state) : Optional.empty());

            assertEquals(QuestionDriver.RecoveryStep.valueOf(expected), recovery.step(), recovery.toString());
        }

        @Test
        @DisplayName("the result of R3 states in its limits how each fault was produced and how often as specified")
        void shouldStateFaultsInLimits() {
            var result = QuestionStub.JSON.createObjectNode();
            var runs = List.<JsonNode>of(
                    run(Map.of("fault", "STOP_BEFORE_ANSWER", "fault_held", true)),
                    run(Map.of("fault", "STOP_BEFORE_ANSWER", "fault_held", false)),
                    run(Map.of("fault", "CALL_CUT", "host", "claude", QuestionResults.CALL_CUT_FIELD, true)),
                    run(Map.of("fault", "CALL_CUT", "host", "opencode", QuestionResults.CALL_CUT_FIELD, false)),
                    run(Map.of("fault", "KILL", "loss_by", "answer_ack_missing", "loss_first_by", "stub")),
                    run(Map.of("fault", "MODEL_HANG", "loss_by", "answer_ack_missing", "loss_first_by", "driver")));

            QuestionResults.limitsOfR3(result, runs);

            var limits = result.path("limits").toString();
            assertAll("the limits",
                    () -> assertTrue(limits.contains("Stopped while holding: 1 of 2 runs."), limits),
                    () -> assertTrue(limits.contains("Cut: claude 1 of 1, opencode 0 of 1."), limits),
                    () -> assertTrue(limits.contains("MCP_TOOL_TIMEOUT of 60 s"), limits),
                    () -> assertTrue(limits.contains("its 180 s limit on every call"), limits),
                    () -> assertTrue(limits.contains("In 1 of 2 runs with a detected loss the stub"), limits));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Fault.class)
        @DisplayName("every R3 run gets a stub that does not lose a worker at its process exit")
        void shouldNotLoseOnExitInR3(Fault fault) {
            var stubSettings = QuestionLoopExperiment.stubSettings(r3Plan(Host.CLAUDE, fault),
                    directory.resolve("stub"), socket);

            assertFalse(stubSettings.loseOnExit(), stubSettings.toString());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"Q1, FALLBACK", "Q2, FALLBACK", "Q3, FALLBACK", "Q4, FALLBACK", "Q5, FALLBACK", "Q6, FALLBACK",
                "Q7, FALLBACK", "R1, HOLD", "R2, RESUME", "R4, HOLD"})
        @DisplayName("every other item keeps the stub's loss of a worker at its process exit, and its start event "
                + "unchanged")
        void shouldKeepLossOnExitOutsideR3(String item, Form form) throws Exception {
            var plan = QuestionLoopExperiment.RunPlan.of(item, 0, Host.CLAUDE, null, false, form,
                    QuestionFixtures.probe(), "entity");

            var stubSettings = QuestionLoopExperiment.stubSettings(plan, directory.resolve("stub"), socket);
            stub = QuestionStub.start(stubSettings);

            var started = first(QuestionResults.readJsonLines(stub.events()), "stub_started");
            assertAll("the settings of " + item,
                    () -> assertTrue(stubSettings.loseOnExit()),
                    () -> assertTrue(Settings.of(directory.resolve("stub"), socket).loseOnExit()),
                    () -> assertEquals(List.of("at", "nanos", EVENT, "records"), fieldNames(started)));
        }

        @ParameterizedTest(name = "lose on exit {0}")
        @CsvSource({"true", "false"})
        @DisplayName("the exit of a worker whose answer was delivered: the stub of R3 observes it and keeps the "
                + "lease, every other item's stub closes the task lost and continues it, as before")
        void shouldObserveExitWithoutLossInR3(boolean loseOnExit) throws Exception {
            stub = QuestionStub.start(Settings.of(directory.resolve("stub"), socket)
                    .withWindow(Duration.ofSeconds(30)).withLoseOnExit(loseOnExit));
            var client = activeWorker(Form.HOLD);
            client.ask("ask", validEnvelope());
            stub.answer("Q-001", 1, List.of(answer("Q-001")));
            var delivered = client.call("pm_wait", QuestionStub.JSON.createObjectNode());

            stub.workerExited(WORKER, 1, false);

            var events = QuestionResults.readJsonLines(stub.events());
            var names = events.stream().map(event -> event.path(EVENT).asText()).toList();
            var started = first(events, "stub_started");
            if (loseOnExit) {
                assertAll("the loss at the exit",
                        () -> assertTrue(delivered.contains("state: task.answer"), delivered),
                        () -> assertEquals(Optional.of("task.lost"), stub.state(TASK)),
                        () -> assertEquals("exited", first(events, "lost").path("reason").asText()),
                        () -> assertEquals(1, count(events, "continued")),
                        () -> assertFalse(names.contains(QuestionStub.EXIT_OBSERVED), names.toString()),
                        () -> assertTrue(started.path("lose_on_exit").isMissingNode(), started.toString()));
            } else {
                var observed = first(events, QuestionStub.EXIT_OBSERVED);
                assertAll("the observation at the exit",
                        () -> assertEquals(Optional.of("task.answer"), stub.state(TASK)),
                        () -> assertEquals("task.answer", observed.path("leased").path(TASK).asText(),
                                observed.toString()),
                        () -> assertEquals(1, observed.path("generation").asInt()),
                        () -> assertFalse(names.contains("lost"), names.toString()),
                        () -> assertFalse(names.contains("released"), names.toString()),
                        () -> assertFalse(names.contains("closed"), names.toString()),
                        () -> assertFalse(names.contains("continued"), names.toString()),
                        () -> assertFalse(started.path("lose_on_exit").asBoolean(true), started.toString()));
            }
        }

        @Test
        @DisplayName("the stub of R3 records a suspended task's stored session at the exit and keeps its lease; the "
                + "resumed generation's exit leaves its task offered until its loss makes it resumable again")
        void shouldKeepStoredSessionAtExitInR3() throws Exception {
            stub = QuestionStub.start(Settings.of(directory.resolve("stub"), socket)
                    .withWindow(Duration.ofSeconds(30)).withLoseOnExit(false));
            var asked = activeWorker(Form.RESUME).ask("ask", validEnvelope());

            stub.workerExited(WORKER, 1, true);
            var suspended = stub.state(TASK);
            stub.answer("Q-001", 1, List.of(answer("Q-001")));
            var answered = stub.state(TASK);
            stub.leaseAtSpawn(TASK, WORKER, 2);
            stub.workerExited(WORKER, 2, true);
            var afterResumedExit = stub.state(TASK);
            stub.lost(WORKER, 2, "answer_ack_missing");

            var events = QuestionResults.readJsonLines(stub.events());
            var observed = events.stream().filter(event -> QuestionStub.EXIT_OBSERVED.equals(event.path(EVENT)
                    .asText())).toList();
            assertAll("the resume on the stub of R3",
                    () -> assertTrue(asked.contains("state: task.suspended"), asked),
                    () -> assertEquals(Optional.of("task.suspended"), suspended),
                    () -> assertTrue(first(events, "session_stored").path("stored").asBoolean()),
                    () -> assertEquals("task.suspended", observed.getFirst().path("leased").path(TASK).asText()),
                    () -> assertEquals(Optional.of("task.resumable"), answered),
                    () -> assertTrue(first(events, "answer_delivered").path("resumed").asBoolean()),
                    () -> assertEquals("fenced", first(events, "released").path("reason").asText()),
                    () -> assertEquals("task.offered", observed.get(1).path("leased").path(TASK).asText()),
                    () -> assertEquals(Optional.of("task.offered"), afterResumedExit),
                    () -> assertEquals(Optional.of("task.resumable"), stub.state(TASK)),
                    () -> assertEquals(List.of("", "answer_ack_missing"), events.stream()
                            .filter(event -> "resumable".equals(event.path(EVENT).asText()))
                            .map(event -> event.path("reason").asText()).toList()),
                    () -> assertEquals(0, count(events, "continued")));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Fault.class, names = {"MODEL_HANG", "KILL"})
        @DisplayName("MODEL_HANG and KILL on the stub of R3: the task stays leased past the exit, the missing "
                + "acknowledgement alone detects the loss at the deadline, never earlier, and the task continues "
                + "once after the detection")
        void shouldDetectByAcknowledgementOnlyInR3(Fault fault) throws Exception {
            try (var driver = driver(false)) {
                var worker = startAsker(driver, Form.HOLD);
                askingClient(worker).request("pm_wait", QuestionStub.JSON.createObjectNode());
                answerAsOperator(driver);
                driver.awaitEvent(event -> "answer_delivered".equals(event.path(EVENT).asText()),
                        System.currentTimeMillis() + DELIVERY.toMillis());
                driver.inject(worker, fault);
                if (fault == Fault.KILL) {
                    driver.awaitEvent(event -> QuestionStub.EXIT_OBSERVED.equals(event.path(EVENT).asText()),
                            System.currentTimeMillis() + DELIVERY.toMillis()).orElseThrow();
                }
                var stateAfterFault = driver.stub().state(TASK);

                var acknowledged = driver.superviseAnswer(worker, DELIVERY);
                var recovery = driver.recovery(worker, "Q-001", TASK);
                driver.awaitEvent(event -> "worker_exited".equals(event.path(EVENT).asText()),
                        System.currentTimeMillis() + DELIVERY.toMillis()).orElseThrow();

                var events = QuestionResults.readJsonLines(driver.stub().events());
                var lost = first(events, LOST_DETECTED);
                var observation = QuestionResults.exitObservation(events, WORKER, 1);
                assertAll("the detection of " + fault + " on the stub of R3",
                        () -> assertEquals(Optional.of("task.answer"), stateAfterFault),
                        () -> assertFalse(acknowledged),
                        () -> assertEquals("answer_ack_missing", lost.path("by").asText()),
                        () -> assertTrue(lost.path("after_ms").asLong() >= Host.CLAUDE.ackDeadline().toMillis(),
                                lost.toString()),
                        () -> assertEquals("driver", lost.path("first_by").asText(), lost.toString()),
                        () -> assertTrue(events.indexOf(lost) < events.indexOf(first(events, "lost")),
                                "the release precedes the detection: " + events),
                        () -> assertTrue(events.indexOf(lost) < events.indexOf(first(events, "continued")),
                                "the continuation precedes the detection: " + events),
                        () -> assertEquals(1, count(events, "continued")),
                        () -> assertEquals(QuestionDriver.RecoveryStep.CONTINUE, recovery.step()),
                        () -> assertEquals(fault == Fault.KILL, observation.exitAt() < lost.path("at").asLong(),
                                observation.toString()),
                        () -> assertTrue(fault == Fault.MODEL_HANG
                                || observation.exitAfterDeliveryMs() < lost.path("after_ms").asLong(),
                                observation.toString()));
            }
        }

        @Test
        @DisplayName("RESUMED_EXIT on the stub of R3: the resumed task stays offered past the exit, and the missing "
                + "acknowledgement detects the loss at the deadline before the task continues")
        void shouldDetectResumedExitByAcknowledgementInR3() throws Exception {
            try (var driver = driver(false)) {
                var asker = startAsker(driver, Form.RESUME);
                askingClient(asker).request("pm_wait", QuestionStub.JSON.createObjectNode());
                answerAsOperator(driver);
                var resumed = driver.resume(asker, TASK, "session-1", 2);
                driver.inject(resumed, Fault.RESUMED_EXIT);
                driver.awaitEvent(event -> QuestionStub.EXIT_OBSERVED.equals(event.path(EVENT).asText())
                        && event.path("generation").asInt() == 2, System.currentTimeMillis() + DELIVERY.toMillis())
                        .orElseThrow();
                var stateAfterExit = driver.stub().state(TASK);
                var continuedBeforeDetection = named(driver, "continued");

                var acknowledged = driver.superviseAnswer(resumed, DELIVERY);
                var recovery = driver.recovery(resumed, "Q-001", TASK);

                var events = QuestionResults.readJsonLines(driver.stub().events());
                var lost = first(events, LOST_DETECTED);
                assertAll("the resumed exit on the stub of R3",
                        () -> assertEquals(Optional.of("task.offered"), stateAfterExit),
                        () -> assertEquals(List.of(), continuedBeforeDetection),
                        () -> assertFalse(acknowledged),
                        () -> assertEquals("driver", lost.path("first_by").asText(), lost.toString()),
                        () -> assertTrue(lost.path("after_ms").asLong() >= Host.CLAUDE.ackDeadline().toMillis(),
                                lost.toString()),
                        () -> assertTrue(events.indexOf(lost) < events.indexOf(first(events, "continued")),
                                events.toString()),
                        () -> assertEquals(1, count(events, "continued")),
                        () -> assertEquals(QuestionDriver.RecoveryStep.CONTINUE, recovery.step()),
                        () -> assertEquals(CONTINUATION, recovery.task()));
            }
        }

        @ParameterizedTest(name = "lose on exit {0}")
        @CsvSource({"true", "false"})
        @DisplayName("an asker that exits without a held pm_wait never gets its answer: the stub of R3 has the driver "
                + "end it after the delivery wait, without a detection; every other item's stub at the exit, as "
                + "before")
        void shouldContinueUndeliveredAnswerAfterExit(boolean loseOnExit) throws Exception {
            try (var driver = driver(loseOnExit)) {
                var worker = startAsker(driver, Form.HOLD);
                askingClient(worker);
                answerAsOperator(driver);
                driver.inject(worker, Fault.KILL);
                driver.awaitEvent(event -> "worker_exited".equals(event.path(EVENT).asText()),
                        System.currentTimeMillis() + DELIVERY.toMillis()).orElseThrow();
                Thread.sleep(200);

                var acknowledged = driver.superviseAnswer(worker, Duration.ofMillis(500));

                var events = QuestionResults.readJsonLines(driver.stub().events());
                var names = events.stream().map(event -> event.path(EVENT).asText()).toList();
                assertAll("the undelivered answer",
                        () -> assertFalse(acknowledged),
                        () -> assertFalse(names.contains(LOST_DETECTED), names.toString()),
                        () -> assertEquals(!loseOnExit, names.contains(QuestionDriver.ANSWER_UNDELIVERED),
                                names.toString()),
                        () -> assertEquals(loseOnExit ? "exited" : QuestionDriver.ANSWER_UNDELIVERED,
                                first(events, "lost").path("reason").asText()),
                        () -> assertEquals(Optional.of("task.lost"), driver.stub().state(TASK)),
                        () -> assertEquals(1, count(events, "continued")));
            }
        }

        @Test
        @DisplayName("the result of R3 reports per run the exit observation beside the detection, and its limits say "
                + "that the stub did not react to the exit; criterion and verdict logic unchanged")
        void shouldReportExitObservation() {
            var killed = new QuestionResults.RunView(run(Map.of("fault", "KILL")), List.of(
                    logEvent(1_000L, "answer_delivered", Map.of("task", TASK)),
                    logEvent(1_250L, "worker_exited", Map.of("exit", 137)),
                    logEvent(16_100L, LOST_DETECTED, Map.of("by", "answer_ack_missing", "after_ms", 15_100,
                            "first_by", "driver"))), List.of(), List.of());
            var hung = new QuestionResults.RunView(run(Map.of("fault", "MODEL_HANG")), List.of(
                    logEvent(1_000L, "answer_delivered", Map.of("task", TASK)),
                    logEvent(16_000L, LOST_DETECTED, Map.of("by", "answer_ack_missing", "after_ms", 15_000,
                            "first_by", "driver")),
                    logEvent(16_400L, "worker_exited", Map.of("exit", 143))), List.of(), List.of());
            var undetected = new QuestionResults.RunView(run(Map.of("fault", "KILL")), List.of(
                    logEvent(500L, QuestionDriver.FAULT_INJECTED, Map.of("fault", "KILL")),
                    logEvent(600L, "worker_exited", Map.of("exit", 137))), List.of(), List.of());
            var group = QuestionStub.JSON.createObjectNode();
            var result = QuestionStub.JSON.createObjectNode();

            QuestionResults.exitObservations(List.of(killed, hung, undetected), group);
            QuestionResults.limitsOfR3(result, List.of(run(Map.of("fault", "KILL",
                    QuestionResults.STUB_LOSE_ON_EXIT, false)), run(Map.of("fault", "KILL"))));

            var figures = group.path(QuestionResults.EXIT_OBSERVATION);
            var runs = figures.path("runs");
            var limits = result.path("limits").toString();
            assertAll("the exit observation",
                    () -> assertEquals(250, runs.get(0).path("exit_observed_after_delivery_ms").asLong()),
                    () -> assertEquals(15_100, runs.get(0).path("detection_after_ms").asLong()),
                    () -> assertTrue(runs.get(0).path("exit_before_detection").asBoolean()),
                    () -> assertEquals(15_400, runs.get(1).path("exit_observed_after_delivery_ms").asLong()),
                    () -> assertFalse(runs.get(1).path("exit_before_detection").asBoolean(true)),
                    () -> assertTrue(runs.get(2).path("exit_observed_after_delivery_ms").isNull()),
                    () -> assertTrue(runs.get(2).path("detection_after_ms").isNull()),
                    () -> assertEquals(2, figures.path("exits_observed").asInt()),
                    () -> assertEquals(1, figures.path("exits_before_detection").asInt()),
                    () -> assertEquals(15_100, figures.path("detection_after_ms_median_with_exit").asLong()),
                    () -> assertTrue(limits.contains("The stub did not react to a worker's process exit in R3"),
                            limits),
                    () -> assertTrue(limits.contains("Exit detection itself was measured in Part A"), limits),
                    () -> assertTrue(limits.contains("Runs with this stub: 1 of 2"), limits),
                    () -> assertEquals("Every fault detected by the missing answer acknowledgement within the host's "
                            + "acknowledgement deadline (by silence where the acknowledgement is informational), never "
                            + "earlier; the task then resumed or offered again with facts.answers exactly once.",
                            QuestionResults.CRITERIA.get("R3")));
        }

        private QuestionDriver driver() throws IOException {
            return driver(true);
        }

        private QuestionDriver driver(boolean loseOnExit) throws IOException {
            var stubSettings = Settings.of(directory.resolve("stub"), socket).withWindow(WINDOW)
                    .withLoseOnExit(loseOnExit);
            return QuestionDriver.start(stubSettings, new QuestionDriver.Settings(Host.CLAUDE, null,
                    directory.resolve("workers"), directory.resolve("run"),
                    List.of(script(directory.resolve("pm-exec"), "exec sleep 60").toString())));
        }

        private static List<String> fieldNames(JsonNode node) {
            var names = new ArrayList<String>();
            node.properties().forEach(entry -> names.add(entry.getKey()));
            return names;
        }

        /** Queues the task, leases it to generation 1 and starts its fake process. */
        private static Worker startAsker(QuestionDriver driver, Form form) throws IOException {
            return driver.startFresh(task(form), new Launch(WORKER, 1, ROLE, "Do it.", form == Form.RESUME, null,
                    List.of(), null));
        }

        /** The asker's relay connects, reads and acknowledges its task, and asks the example envelope. */
        private Client askingClient(Worker worker) throws IOException {
            var client = Client.connect(socket, worker.id(), worker.generation());
            clients.add(client);
            client.state();
            client.link("ack", QuestionStub.JSON.createObjectNode());
            client.ask("ask", validEnvelope());
            return client;
        }

        private static long askedAt(QuestionDriver driver) throws IOException, InterruptedException {
            return driver.awaitEvent(event -> "asked".equals(event.path(EVENT).asText()),
                    System.currentTimeMillis() + DELIVERY.toMillis()).orElseThrow().path("at").asLong();
        }

        private static void answerAsOperator(QuestionDriver driver) throws IOException, InterruptedException {
            assertEquals(Optional.of("Q-001"), driver.awaitAnswer("Q-001", DELIVERY, Duration.ZERO,
                    decision -> List.of(answer("Q-001"))));
        }

        /** Leases the stub's continuation to generation 2 and returns it as that generation acknowledges it. */
        private String continuationText(QuestionDriver driver) throws IOException {
            driver.stub().leaseAtSpawn(CONTINUATION, WORKER, 2);
            var client = Client.connect(socket, WORKER, 2);
            clients.add(client);
            client.state();
            return client.link("ack", QuestionStub.JSON.createObjectNode());
        }

        private static List<JsonNode> named(QuestionDriver driver, String name) throws IOException {
            return QuestionResults.readJsonLines(driver.stub().events()).stream()
                    .filter(event -> name.equals(event.path(EVENT).asText())).toList();
        }

        private static JsonNode first(List<JsonNode> events, String name) {
            return events.stream().filter(event -> name.equals(event.path(EVENT).asText())).findFirst()
                    .orElseThrow(() -> new AssertionError("no " + name + " in " + events));
        }

        private static long count(List<JsonNode> events, String name) {
            return events.stream().filter(event -> name.equals(event.path(EVENT).asText())).count();
        }

        private static QuestionLoopExperiment.RunPlan r3Plan(Host host, Fault fault) {
            return QuestionLoopExperiment.RunPlan.of("R3", 0, host, null, false,
                    fault == Fault.RESUMED_EXIT ? Form.RESUME : Form.HOLD, QuestionFixtures.probe(), "entity")
                    .holding(QuestionLoopExperiment.r3Hold(host, fault)).faulting(fault);
        }

        private static JsonNode logEvent(long at, String name, Map<String, ?> fields) {
            return eventOf(at, name, 1, fields);
        }

        private static JsonNode eventOf(long at, String name, int generation, Map<String, ?> fields) {
            var node = QuestionStub.JSON.createObjectNode();
            node.put("at", at);
            node.put(EVENT, name);
            node.put("worker", WORKER);
            node.put("generation", generation);
            fields.forEach((key, value) -> node.set(key, QuestionStub.JSON.valueToTree(value)));
            return node;
        }

        private static JsonNode relayEntry(long at, String line) {
            var node = QuestionStub.JSON.createObjectNode();
            node.put("at", at);
            node.put("dir", "from_host");
            node.put("line", line);
            return node;
        }

        private static JsonNode run(Map<String, ?> fields) {
            var node = QuestionStub.JSON.createObjectNode();
            fields.forEach((key, value) -> node.set(key, QuestionStub.JSON.valueToTree(value)));
            return node;
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
        private Path relayLog;

        private Client(SocketChannel channel) {
            this.channel = channel;
            this.reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel),
                    StandardCharsets.UTF_8));
            this.out = Channels.newOutputStream(channel);
        }

        static Client connect(Path socket, String worker, int generation) throws IOException {
            return connect(socket, worker, generation, ROLE);
        }

        static Client connect(Path socket, String worker, int generation, String role) throws IOException {
            var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socket));
            var client = new Client(channel);
            client.send("{\"worker\":\"" + worker + "\",\"generation\":" + generation + ",\"role\":\"" + role
                    + "\",\"harness\":\"test\"}");
            return client;
        }

        String state(String entity) throws IOException {
            var arguments = QuestionStub.JSON.createObjectNode();
            arguments.put("entity", entity);
            return call("pm_state", arguments);
        }

        String link(String entity, String link, ObjectNode args) throws IOException {
            var arguments = QuestionStub.JSON.createObjectNode();
            arguments.put("plan_id", QuestionStub.PLAN_ID);
            arguments.put("entity", entity);
            arguments.put("link", link);
            arguments.set("args", args);
            return call("pm_do", arguments);
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
            return text(request(tool, arguments));
        }

        /**
         * Writes every line this client sends, as the relay's host-side log does ({@code at}, {@code dir}
         * {@code from_host}, {@code line}), to a file: the relay a fake host would have.
         */
        Client relayingTo(Path log) {
            this.relayLog = log;
            return this;
        }

        /** Sends a tool call without waiting for its response, as a host does with a call it holds. */
        int request(String tool, ObjectNode arguments) throws IOException {
            var id = ++nextId;
            var request = QuestionStub.JSON.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", "tools/call");
            var params = request.putObject("params");
            params.put("name", tool);
            params.set("arguments", arguments);
            send(QuestionStub.JSON.writeValueAsString(request));
            return id;
        }

        /** Waits for the response of a call sent by {@link #request} and returns its text. */
        String text(int id) throws IOException {
            return response(id).path("result").path("content").get(0).path("text").asText();
        }

        /** Gives a held call up, as a host does at its tool timeout: {@code notifications/cancelled}. */
        void cancel(int id) throws IOException {
            send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":" + id
                    + ",\"reason\":\"tool timeout\"}}");
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
            if (relayLog != null) {
                var entry = QuestionStub.JSON.createObjectNode();
                entry.put("at", System.currentTimeMillis());
                entry.put("dir", "from_host");
                entry.put("line", line);
                Files.writeString(relayLog, QuestionStub.JSON.writeValueAsString(entry) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
