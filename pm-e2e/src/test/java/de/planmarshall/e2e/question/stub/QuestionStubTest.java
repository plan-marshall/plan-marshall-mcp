/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.e2e.question.stub;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.question.stub.QuestionStub.AnswerResult;
import de.planmarshall.e2e.question.stub.QuestionStub.Form;
import de.planmarshall.e2e.question.stub.QuestionStub.PartAnswer;
import de.planmarshall.e2e.question.stub.QuestionStub.Settings;
import de.planmarshall.e2e.question.stub.QuestionStub.TaskSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The stub of the question loop without a model: a client speaks to it over its socket as a relay does, with the
 * worker's identity in the first line.
 * <p>
 * Hazards it guards, each a premise of the measurement: an ask whose envelope the server would refuse being recorded
 * anyway, so Q1 counted asks no server takes; a lease of a fenced generation staying with it, so a replaced worker's
 * task is never offered again (PM-WORK-2); an open question record lost with a stub restart, or its answer delivered
 * twice, so Q7 measured the stub instead of the loop (PM-WF-12); a continuing task without {@code facts.answers}, so
 * Q4 measured a worker that never saw its answer; an ask link offered beyond the question bound, so Q5 never reached
 * the bound; and a relay that lets its identity come from anywhere but its environment. No network, model or harness
 * is used.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("QuestionStub: the question loop of a worker without a model")
class QuestionStubTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ROLE = "implement";
    private static final String TASK = "T-1";
    private static final String WORKER = "w1";
    private static final String QUESTION = "Q-001";
    private static final String LINKS_EMPTY = "links: []";
    private static final String CONTINUED_LINE = "\"line\":\"continued\"";

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
    void stop() throws IOException, InterruptedException {
        for (var client : clients) {
            client.close();
        }
        if (stub != null) {
            stub.close();
            settle(stub.events());
            settle(stub.records());
        }
    }

    /**
     * Waits until a log of the stub stops growing: a connection thread may still log the end of its connection after
     * the stub closed, and the temporary directory must not be deleted under it.
     */
    private static void settle(Path log) throws IOException, InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        var last = -1L;
        while (System.nanoTime() < deadline) {
            var size = Files.exists(log) ? Files.size(log) : 0;
            if (size == last) {
                return;
            }
            last = size;
            Thread.sleep(200); // NOSONAR java:S2925 - polls the size of a log until it stops growing
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

            var asked = client.ask(validEnvelope());

            assertAll("the asked representation",
                    () -> assertTrue(asked.contains("state: task.asked"), asked),
                    () -> assertTrue(asked.contains(LINKS_EMPTY), asked),
                    () -> assertEquals(QUESTION, stub.operatorDecisions().peek()),
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

            var refused = client.ask(envelope);

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

            var refused = client.ask(envelope);

            assertTrue(refused.contains("question_id: a server-owned field"), refused);
        }

        @Test
        @DisplayName("refuses evidence that is no pm:// URI")
        void shouldRefuseEvidenceWithoutPmUri() throws Exception {
            stub = start(1);
            var client = activeWorker(Form.FALLBACK);
            var envelope = validEnvelope();
            envelope.putArray("evidence").add("solution.py");

            var refused = client.ask(envelope);

            assertTrue(refused.contains("evidence: every item must be a pm:// URI"), refused);
        }

        @Test
        @DisplayName("refuses a link the representation does not offer")
        void shouldRefuseLinkNotOffered() throws Exception {
            stub = start(1);
            var client = activeWorker(Form.FALLBACK);

            var refused = client.link("submit:vote", QuestionStub.JSON.createObjectNode());

            assertTrue(refused.contains("reason: link_not_offered"), refused);
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
            var asked = client.ask(validEnvelope());

            var answered = stub.answer(QUESTION, 1, List.of(answer()));
            var delivered = client.call("pm_wait", QuestionStub.JSON.createObjectNode());
            var acknowledged = client.link("ack", QuestionStub.JSON.createObjectNode());

            assertAll("the held continuation",
                    () -> assertTrue(asked.contains("state: entity.awaiting_answer"), asked),
                    () -> assertEquals(AnswerResult.ANSWERED, answered),
                    () -> assertTrue(delivered.contains("state: task.answer"), delivered),
                    () -> assertTrue(delivered.contains("links[1]{rel,link,form,free}:"), delivered),
                    () -> assertTrue(acknowledged.contains("state: entity.active"), acknowledged),
                    () -> assertTrue(acknowledged.contains(QUESTION + ",null,"), acknowledged),
                    () -> assertEquals(AnswerResult.ALREADY_ANSWERED, stub.answer(QUESTION, 1, List.of(answer()))));
        }

        @Test
        @DisplayName("falls back: the answer reaches a new task of the role in facts.answers")
        void shouldCarryAnswerIntoNewTask() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            var client = activeWorker(Form.FALLBACK);
            client.ask(validEnvelope());

            stub.answer(QUESTION, 1, List.of(answer()));
            var offered = client.call("pm_wait", QuestionStub.JSON.createObjectNode());
            var active = client.link("ack", QuestionStub.JSON.createObjectNode());

            assertAll("the continuing task",
                    () -> assertTrue(offered.contains("state: task.offered"), offered),
                    () -> assertTrue(active.contains(QUESTION + ",null,"), active),
                    () -> assertTrue(active.contains(",description,Follow the description,"), active),
                    () -> assertTrue(stub.taskIds().contains("T-1-Q-001"), stub.taskIds().toString()));
        }

        @Test
        @DisplayName("resumes: the task is suspended at the ask and offered with its answer to the resumed session")
        void shouldOfferAnswerToResumedSession() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            var client = activeWorker(Form.RESUME);
            var asked = client.ask(validEnvelope());
            stub.workerExited(WORKER, 1, true);

            var answered = stub.answer(QUESTION, 1, List.of(answer()));
            var resumable = stub.state(TASK);
            stub.leaseAtSpawn(TASK, WORKER, 2);
            var resumed = connect(2);
            var offered = resumed.state();
            var active = resumed.link("ack", QuestionStub.JSON.createObjectNode());

            assertAll("the resumed continuation",
                    () -> assertTrue(asked.contains("state: task.suspended"), asked),
                    () -> assertEquals(AnswerResult.ANSWERED, answered),
                    () -> assertEquals(Optional.of("task.resumable"), resumable),
                    () -> assertTrue(offered.contains("state: task.offered"), offered),
                    () -> assertTrue(active.contains("state: entity.active"), active),
                    () -> assertTrue(active.contains(QUESTION + ",null,"), active),
                    () -> assertTrue(Files.readString(stub.events()).contains("\"kind\":\"answer\"")));
        }

        @Test
        @DisplayName("refuses an answer to an older revision, an unknown option and an unknown decision")
        void shouldRefuseInvalidAnswers() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            var client = activeWorker(Form.FALLBACK);
            client.ask(validEnvelope());

            var conflict = stub.answer(QUESTION, 2, List.of(answer()));
            var unknownOption = stub.answer(QUESTION, 1, List.of(new PartAnswer(QUESTION, "nope", null, "chosen")));
            var unknownDecision = stub.answer("Q-404", 1, List.of(answer()));

            assertAll("the refusals",
                    () -> assertEquals(AnswerResult.CONFLICT, conflict),
                    () -> assertEquals(AnswerResult.INVALID_ANSWER, unknownOption),
                    () -> assertEquals(AnswerResult.INVALID_ANSWER, unknownDecision),
                    () -> assertEquals(Optional.empty(), stub.decision("Q-404")),
                    () -> assertFalse(stub.decision(QUESTION).orElseThrow().path("answered").asBoolean()));
        }
    }

    @Nested
    @DisplayName("question bound")
    class QuestionBound {

        @Test
        @DisplayName("withdraws the ask link once the question bound is reached")
        void shouldWithdrawAskLinkAtBound() throws Exception {
            stub = start(1);
            var client = activeWorker(Form.FALLBACK);
            var beforeBound = client.state();
            client.ask(validEnvelope());
            stub.answer(QUESTION, 1, List.of(answer()));
            client.call("pm_wait", QuestionStub.JSON.createObjectNode());

            var atBound = client.link("ack", QuestionStub.JSON.createObjectNode());

            assertAll("the bound",
                    () -> assertTrue(beforeBound.contains("submit,ask,submit,"), beforeBound),
                    () -> assertFalse(atBound.contains("submit,ask,submit,"), atBound),
                    () -> assertTrue(atBound.contains("submit,submit,submit,"), atBound));
        }
    }

    @Nested
    @DisplayName("fencing")
    class Fencing {

        @Test
        @DisplayName("a newer generation fences the older one, whose lease is released and offered again")
        void shouldFenceOlderGeneration() throws Exception {
            stub = start(1);
            var older = activeWorker(Form.FALLBACK);

            var newer = connect(2);
            var offered = newer.call("pm_wait", QuestionStub.JSON.createObjectNode());
            var refused = older.state();

            var events = Files.readString(stub.events());
            assertAll("the fence",
                    () -> assertTrue(offered.contains("state: task.offered"), offered),
                    () -> assertTrue(refused.contains("generation_fenced"), refused),
                    () -> assertTrue(events.contains("\"event\":\"fenced\""), events),
                    () -> assertTrue(events.contains("\"reason\":\"fenced\""), events));
        }

        @Test
        @DisplayName("a lost generation with an answer delivered and not acknowledged continues in a new task")
        void shouldContinueAfterLossOfAnsweredHolder() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            var client = activeWorker(Form.HOLD);
            client.ask(validEnvelope());
            stub.answer(QUESTION, 1, List.of(answer()));
            client.call("pm_wait", QuestionStub.JSON.createObjectNode());

            stub.lost(WORKER, 1, "ack_missing");

            assertAll("the continuation",
                    () -> assertEquals(Optional.of("task.lost"), stub.state(TASK)),
                    () -> assertTrue(stub.taskIds().contains("T-1-Q-001"), stub.taskIds().toString()));
        }

        @Test
        @DisplayName("a stub that does not lose a worker at its exit only observes the exit")
        void shouldOnlyObserveExitWithoutLoss() throws Exception {
            stub = QuestionStub.start(settings(1).withLoseOnExit(false));
            activeWorker(Form.HOLD);

            stub.workerExited(WORKER, 1, false);

            assertAll("the observation",
                    () -> assertEquals(Optional.of("entity.active"), stub.state(TASK)),
                    () -> assertTrue(Files.readString(stub.events()).contains(QuestionStub.EXIT_OBSERVED)));
        }
    }

    @Nested
    @DisplayName("replay after a restart")
    class Replay {

        @Test
        @DisplayName("keeps an open record open and delivers its answer exactly once")
        void shouldReplayOpenRecord() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            activeWorker(Form.FALLBACK).ask(validEnvelope());

            restart();
            var requeued = stub.operatorDecisions().peek();
            var answered = stub.answer(QUESTION, 1, List.of(answer()));
            restart();

            assertAll("the open record",
                    () -> assertEquals(QUESTION, requeued),
                    () -> assertEquals(AnswerResult.ANSWERED, answered),
                    () -> assertEquals(1, count(Files.readString(stub.records()), CONTINUED_LINE)),
                    () -> assertEquals(AnswerResult.ALREADY_ANSWERED, stub.answer(QUESTION, 1, List.of(answer()))));
        }

        @Test
        @DisplayName("an answered record is not put before the operator again and continues no second time")
        void shouldReplayAnsweredRecord() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            activeWorker(Form.FALLBACK).ask(validEnvelope());
            stub.answer(QUESTION, 1, List.of(answer()));

            restart();

            assertAll("the answered record",
                    () -> assertTrue(stub.decision(QUESTION).orElseThrow().path("answered").asBoolean()),
                    () -> assertTrue(stub.operatorDecisions().isEmpty()),
                    () -> assertEquals(1, count(Files.readString(stub.records()), CONTINUED_LINE)));
        }

        @Test
        @DisplayName("rebuilds a RESUME task suspended, and resumable once the answer arrives after the restart")
        void shouldReplayResumeRecord() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            activeWorker(Form.RESUME).ask(validEnvelope());
            stub.workerExited(WORKER, 1, true);

            restart();
            var suspended = stub.state(TASK);
            var requeued = stub.operatorDecisions().peek();
            stub.answer(QUESTION, 1, List.of(answer()));

            assertAll("the RESUME record",
                    () -> assertEquals(Optional.of("task.suspended"), suspended),
                    () -> assertEquals(QUESTION, requeued),
                    () -> assertEquals(Optional.of("task.resumable"), stub.state(TASK)));
        }

        private void restart() throws IOException {
            for (var client : clients) {
                client.close();
            }
            clients.clear();
            stub.close();
            stub = start(QuestionStub.QUESTION_BOUND);
        }
    }

    @Nested
    @DisplayName("MCP and TOON")
    class McpAndToon {

        @Test
        @DisplayName("answers initialize and lists the three tools, and refuses an unknown method")
        void shouldServeMcpMethods() throws Exception {
            stub = start(1);
            var client = connect(1);

            var initialize = client.rpc("initialize", "{\"protocolVersion\":\"2025-11-25\"}");
            var tools = client.rpc("tools/list", "{}");
            var unknown = client.rpc("resources/list", "{}");

            assertAll("the MCP methods",
                    () -> assertEquals("2025-11-25", initialize.path("result").path("protocolVersion").asText()),
                    () -> assertEquals(QuestionStub.SERVER,
                            initialize.path("result").path("serverInfo").path("name").asText()),
                    () -> assertEquals(QuestionStub.TOOLS.size(), tools.path("result").path("tools").size()),
                    () -> assertEquals(-32601, unknown.path("error").path("code").asInt()));
        }

        @Test
        @DisplayName("renders scalars inline, uniform objects as a table, an empty array as [] and quotes text")
        void shouldRenderToon() {
            var node = QuestionStub.JSON.createObjectNode();
            node.put("state", "entity.active");
            node.putObject("facts").put("task", "a, b");
            node.putArray("ids").add("a").add("b");
            var rows = node.putArray("links");
            rows.addObject().put("rel", "next").put("link", "ack");
            rows.addObject().put("rel", "alt").put("link", "yield");
            node.putArray("empty");

            var toon = QuestionStub.toon(node);

            assertEquals("""
                    state: entity.active
                    facts:
                      task: "a, b"
                    ids[2]: a,b
                    links[2]{rel,link}:
                      next,ack
                      alt,yield
                    empty: []
                    """, toon);
        }
    }

    @Nested
    @DisplayName("relay")
    class Relay {

        @Test
        @DisplayName("takes its identity from its environment and writes it as the first line")
        void shouldWriteIdentityFromEnvironment() {
            var environment = StubRelay.environment(socket, WORKER, 3, ROLE, "claude", directory.resolve("relay.log"));

            var hello = StubRelay.hello(environment);

            assertAll("the identity",
                    () -> assertEquals(socket.toString(), environment.get(StubRelay.SOCKET)),
                    () -> assertEquals("{\"worker\":\"w1\",\"generation\":3,\"role\":\"implement\","
                            + "\"harness\":\"claude\"}\n", hello));
        }

        @Test
        @DisplayName("forwards every line and logs it with its direction")
        void shouldForwardAndLogEveryLine() {
            var from = new ByteArrayInputStream("{\"a\":1}\n{\"b\":\"x\\ty\"}\n".getBytes(StandardCharsets.UTF_8));
            var to = new ByteArrayOutputStream();
            var log = new StringWriter();

            StubRelay.pump(from, to, log, "from_host");

            assertAll("the forwarding",
                    () -> assertEquals("{\"a\":1}\n{\"b\":\"x\\ty\"}\n", to.toString(StandardCharsets.UTF_8)),
                    () -> assertEquals(2, count(log.toString(), "\"dir\":\"from_host\"")),
                    () -> assertEquals("\"q\\\"\\n\\u0001\"", StubRelay.quote("q\"\n\u0001")));
        }

        @Test
        @DisplayName("as a process, connects the host's stdin and stdout to the stub with the worker's identity")
        void shouldRelayHostToStubAsProcess() throws Exception {
            stub = start(1);
            var log = directory.resolve("relay.log");
            var builder = new ProcessBuilder(StubRelay.command());
            builder.environment().putAll(StubRelay.environment(socket, WORKER, 1, ROLE, "test", log));
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            var process = builder.start();
            try (var toRelay = process.getOutputStream();
                 var fromRelay = new BufferedReader(new InputStreamReader(process.getInputStream(),
                         StandardCharsets.UTF_8))) {

                toRelay.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n".getBytes(StandardCharsets.UTF_8));
                toRelay.flush();
                var response = QuestionStub.JSON.readTree(fromRelay.readLine());

                assertEquals(1, response.path("id").asInt());
            } finally {
                process.destroy();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            var events = Files.readString(stub.events());
            var relayLog = Files.readString(log);
            assertAll("the relay",
                    () -> assertTrue(events.contains("\"event\":\"connected\",\"worker\":\"w1\""), events),
                    () -> assertTrue(relayLog.contains("\"dir\":\"connect\""), relayLog),
                    () -> assertTrue(relayLog.contains("\"dir\":\"to_host\""), relayLog));
        }
    }

    @Nested
    @DisplayName("consultation")
    class Consultation {

        @Test
        @DisplayName("queues a consultation for the consulted role and returns its answer to the asker through pm_wait")
        void shouldDeliverConsultationAnswer() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            var asker = activeWorker(task(Form.HOLD, List.of(ADVISE)));
            var question = QuestionStub.JSON.createObjectNode().put("question", "How is the contract to be read?");

            var consulting = asker.link("consult:" + ADVISE, question);
            var advisor = connect(ADVISOR, 1, ADVISE);
            var offered = advisor.call("pm_wait", QuestionStub.JSON.createObjectNode());
            advisor.link("ack", QuestionStub.JSON.createObjectNode());
            var answered = advisor.link("submit:answer",
                    QuestionStub.JSON.createObjectNode().put("answer", "Read the description first."));
            var delivered = asker.call("pm_wait", QuestionStub.JSON.createObjectNode());

            assertAll("the consultation",
                    () -> assertTrue(consulting.contains("state: consulting"), consulting),
                    () -> assertTrue(consulting.contains("Consultation sent"), consulting),
                    () -> assertTrue(offered.contains("entity: C-001"), offered),
                    () -> assertTrue(answered.contains("state: entity.completed"), answered),
                    () -> assertTrue(delivered.contains("state: entity.active"), delivered),
                    () -> assertTrue(delivered.contains("Read the description first."), delivered),
                    () -> assertTrue(Files.readString(stub.events()).contains("consultation_delivered")));
        }

        @Test
        @DisplayName("requires the delivered consultations in consultation_ids of the asker's submit")
        void shouldRequireConsultationIdsOnSubmit() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            var asker = activeWorker(task(Form.HOLD, List.of(ADVISE)));
            asker.link("consult:" + ADVISE, QuestionStub.JSON.createObjectNode().put("question", "How to read it?"));
            stub.answerConsultation("C-001", "Read the description first.");
            asker.call("pm_wait", QuestionStub.JSON.createObjectNode());

            var refused = asker.link("submit", QuestionStub.JSON.createObjectNode().put("answer", "done"));
            var args = QuestionStub.JSON.createObjectNode().put("answer", "done");
            args.putArray("consultation_ids").add("C-001");
            var submitted = asker.link("submit", args);

            assertAll("the consultation references",
                    () -> assertTrue(refused.contains("consultation_ids: must list exactly"), refused),
                    () -> assertTrue(submitted.contains("state: entity.completed"), submitted),
                    () -> assertTrue(submitted.contains("Task completed."), submitted));
        }

        @Test
        @DisplayName("refuses a consultation without a question, and reports an infeasible consultation to the asker")
        void shouldReportInfeasibleConsultation() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            var asker = activeWorker(task(Form.HOLD, List.of(ADVISE)));
            var refused = asker.link("consult:" + ADVISE, QuestionStub.JSON.createObjectNode());
            asker.link("consult:" + ADVISE, QuestionStub.JSON.createObjectNode().put("question", "How to read it?"));
            var advisor = connect(ADVISOR, 1, ADVISE);
            advisor.call("pm_wait", QuestionStub.JSON.createObjectNode());
            advisor.link("ack", QuestionStub.JSON.createObjectNode());

            var noAnswer = advisor.link("submit:answer", QuestionStub.JSON.createObjectNode());
            var infeasible = advisor.link("infeasible",
                    QuestionStub.JSON.createObjectNode().put("evidence", "The contract is not readable."));
            var delivered = asker.call("pm_wait", QuestionStub.JSON.createObjectNode());

            assertAll("the infeasible consultation",
                    () -> assertTrue(refused.contains("reason: invalid_link_args"), refused),
                    () -> assertTrue(noAnswer.contains("answer: a text of 1 to 4000"), noAnswer),
                    () -> assertTrue(infeasible.contains("state: task.infeasible"), infeasible),
                    () -> assertTrue(infeasible.contains("Task ended infeasible."), infeasible),
                    () -> assertTrue(delivered.contains("infeasible"), delivered));
        }

        @Test
        @DisplayName("an answer to a consultation whose asker has ended is not deliverable")
        void shouldNotDeliverToClosedAsker() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            var asker = activeWorker(task(Form.HOLD, List.of(ADVISE)));
            asker.link("consult:" + ADVISE, QuestionStub.JSON.createObjectNode().put("question", "How to read it?"));
            stub.lost(WORKER, 1, "silence");

            stub.answerConsultation("C-001", "Too late.");
            stub.answerConsultation("C-404", "Unknown.");

            assertTrue(Files.readString(stub.events()).contains("consultation_undeliverable"));
        }
    }

    @Nested
    @DisplayName("submit and infeasible")
    class SubmitAndInfeasible {

        @Test
        @DisplayName("refuses a submit whose free parameters do not hold, then completes the task")
        void shouldValidateSubmit() throws Exception {
            stub = start(1);
            var client = activeWorker(Form.FALLBACK);

            var refused = client.link("submit", QuestionStub.JSON.createObjectNode().put("judgment", 5));
            var submitted = client.link("submit", QuestionStub.JSON.createObjectNode().put("answer", "42"));
            var stale = client.link(QuestionStub.PLAN_ID, TASK, "submit",
                    QuestionStub.JSON.createObjectNode().put("answer", "43"));

            var events = Files.readString(stub.events());
            assertAll("the submit",
                    () -> assertTrue(refused.contains("answer: a text of 1 to 200"), refused),
                    () -> assertTrue(refused.contains("judgment: a text of 0 to 2000"), refused),
                    () -> assertTrue(submitted.contains("state: entity.completed"), submitted),
                    () -> assertTrue(stale.contains("reason: stale_entity"), stale),
                    () -> assertTrue(events.contains("\"event\":\"submit_refused\""), events),
                    () -> assertTrue(events.contains("\"event\":\"submitted\""), events));
        }

        @Test
        @DisplayName("ends a task infeasible with its evidence, and refuses infeasible without evidence")
        void shouldEndInfeasible() throws Exception {
            stub = start(1);
            var client = activeWorker(Form.FALLBACK);

            var refused = client.link("infeasible", QuestionStub.JSON.createObjectNode());
            var ended = client.link("infeasible",
                    QuestionStub.JSON.createObjectNode().put("evidence", "The function cannot exist."));

            assertAll("the infeasible task",
                    () -> assertTrue(refused.contains("evidence: a text of 1 to 2000"), refused),
                    () -> assertTrue(ended.contains("state: task.infeasible"), ended),
                    () -> assertEquals(Optional.of("task.infeasible"), stub.state(TASK)));
        }

        @Test
        @DisplayName("refuses a link with another plan_id, an unknown entity and an unknown tool")
        void shouldRefuseScopeErrors() throws Exception {
            stub = start(1);
            var client = activeWorker(Form.FALLBACK);

            var unknownPlan = client.link("other", "", "submit", QuestionStub.JSON.createObjectNode());
            var unknownEntity = client.state("T-404");
            var unknownTool = client.call("pm_nope", QuestionStub.JSON.createObjectNode());

            assertAll("the refusals",
                    () -> assertTrue(unknownPlan.contains("reason: unknown_plan"), unknownPlan),
                    () -> assertTrue(unknownEntity.contains("job_scope_mismatch"), unknownEntity),
                    () -> assertTrue(unknownTool.contains("unknown_tool"), unknownTool),
                    () -> assertEquals(Optional.empty(), stub.state("T-404")));
        }
    }

    @Nested
    @DisplayName("waiting")
    class Waiting {

        @Test
        @DisplayName("answers wait again after the window, with progress frames for a progress token")
        void shouldWaitAgainWithProgress() throws Exception {
            stub = QuestionStub.start(new Settings(directory.resolve("stub"), socket, Duration.ofMillis(400),
                    Duration.ofMillis(100), 1, Map.of()));
            var client = activeWorker(Form.HOLD);

            var waited = client.waitWithProgress("p-1");

            assertAll("the bounded wait",
                    () -> assertEquals(QuestionStub.WAIT_AGAIN, waited),
                    () -> assertFalse(client.notifications().isEmpty()),
                    () -> assertEquals("p-1",
                            client.notifications().getFirst().path("params").path("progressToken").asText()),
                    () -> assertTrue(Files.readString(stub.events()).contains("\"reason\":\"window\"")));
        }

        @Test
        @DisplayName("answers wait end to an ended generation, and refuses the wait of a fenced one")
        void shouldEndAndFenceWaits() throws Exception {
            stub = start(1);
            var older = activeWorker(Form.HOLD);
            stub.end(WORKER, 1);

            var end = older.call("pm_wait", QuestionStub.JSON.createObjectNode());
            connect(2).state();
            var fenced = older.call("pm_wait", QuestionStub.JSON.createObjectNode());
            var fencedDo = older.link("ack", QuestionStub.JSON.createObjectNode());

            assertAll("the end and the fence",
                    () -> assertEquals(QuestionStub.WAIT_END, end),
                    () -> assertTrue(fenced.contains("generation_fenced"), fenced),
                    () -> assertTrue(fencedDo.contains("generation_fenced"), fencedDo));
        }

        @Test
        @DisplayName("a second wait of a generation supersedes the first, which answers wait again")
        void shouldSupersedeWait() throws Exception {
            stub = start(1);
            var client = activeWorker(Form.HOLD);
            var second = connect(1);

            var first = Thread.ofVirtual().start(() -> {
                try {
                    client.call("pm_wait", QuestionStub.JSON.createObjectNode());
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(100); // NOSONAR java:S2925 - lets the first wait reach the stub, the test checks the outcome
            second.call("pm_wait", QuestionStub.JSON.createObjectNode());
            first.join(5000);

            assertTrue(Files.readString(stub.events()).contains("\"reason\":\"superseded\""));
        }

        @Test
        @DisplayName("refuses a held wait with stub_stopped when the stub stops")
        void shouldRefuseWaitOnStop() throws Exception {
            stub = QuestionStub.start(settings(1).withWindow(Duration.ofSeconds(10)));
            var client = activeWorker(Form.HOLD);
            var answer = new String[1];
            var waiter = Thread.ofVirtual().start(() -> {
                try {
                    answer[0] = client.call("pm_wait", QuestionStub.JSON.createObjectNode());
                } catch (IOException _) {
                    answer[0] = "closed";
                }
            });
            Thread.sleep(200); // NOSONAR java:S2925 - lets the wait reach the stub, the test checks the outcome

            stub.close();
            waiter.join(5000);
            stub = null;

            assertFalse(waiter.isAlive());
        }

        @Test
        @DisplayName("offers a queued task of its role to a worker that waits without a lease")
        void shouldOfferQueuedTaskThroughWait() throws Exception {
            stub = start(1);
            stub.queue(task(Form.FALLBACK));
            var client = connect(1);

            var offered = client.call("pm_wait", QuestionStub.JSON.createObjectNode());
            var events = Files.readString(stub.events());

            assertAll("the offer",
                    () -> assertTrue(offered.contains("state: task.offered"), offered),
                    () -> assertTrue(events.contains("\"at_spawn\":false"), events));
        }
    }

    @Nested
    @DisplayName("quorum")
    class Quorum {

        @Test
        @DisplayName("decides a question both voters agree on and delivers the agreed choice to the holder")
        void shouldDecideOnAgreement() throws Exception {
            stub = QuestionStub.start(settings(QuestionStub.QUESTION_BOUND).withQuorums(QUORUMS));
            var asker = activeWorker(Form.HOLD);
            asker.ask(validEnvelope());

            var firstVote = vote(VOTER_A, "description", 80);
            vote(VOTER_B, "description", 90);
            var delivered = asker.call("pm_wait", QuestionStub.JSON.createObjectNode());

            var records = Files.readString(stub.records());
            assertAll("the agreement",
                    () -> assertTrue(firstVote.contains("state: entity.completed"), firstVote),
                    () -> assertTrue(stub.decision(QUESTION).orElseThrow().path("answered").asBoolean()),
                    () -> assertTrue(stub.operatorDecisions().isEmpty()),
                    () -> assertTrue(records.contains("\"reason\":\"agreement\""), records),
                    () -> assertTrue(delivered.contains("state: task.answer"), delivered));
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("escalates to the operator on disagreement and on a confidence below the score")
        @CsvSource({"disagreement,examples,90", "below_score,description,10"})
        void shouldEscalate(String reason, String secondChoice, int secondConfidence) throws Exception {
            stub = QuestionStub.start(settings(QuestionStub.QUESTION_BOUND).withQuorums(QUORUMS));
            activeWorker(Form.HOLD).ask(validEnvelope());

            vote(VOTER_A, "description", 80);
            vote(VOTER_B, secondChoice, secondConfidence);

            assertAll("the escalation",
                    () -> assertEquals(QUESTION, stub.operatorDecisions().peek()),
                    () -> assertTrue(Files.readString(stub.records()).contains("\"reason\":\"" + reason + "\"")));
        }

        @Test
        @DisplayName("escalates with role_unavailable when a voter is lost, and refuses an invalid vote")
        void shouldEscalateWhenVoterLost() throws Exception {
            stub = QuestionStub.start(settings(QuestionStub.QUESTION_BOUND).withQuorums(QUORUMS));
            activeWorker(Form.HOLD).ask(validEnvelope());
            vote(VOTER_A, "description", 80);
            var voter = connect("v-" + VOTER_B, 1, VOTER_B);
            var blind = voter.call("pm_wait", QuestionStub.JSON.createObjectNode());
            voter.link("ack", QuestionStub.JSON.createObjectNode());
            var refused = voter.link("submit:vote", voteArgs("nope", 200, "short"));

            stub.lost("v-" + VOTER_B, 1, "silence");

            assertAll("the lost voter",
                    () -> assertFalse(blind.contains("recommendation"), blind),
                    () -> assertTrue(refused.contains("answers: unknown choice for Q-001"), refused),
                    () -> assertTrue(refused.contains("confidence 0..100"), refused),
                    () -> assertEquals(QUESTION, stub.operatorDecisions().peek()),
                    () -> assertTrue(Files.readString(stub.records()).contains("role_unavailable")));
        }

        @Test
        @DisplayName("settles the set when a voter reports the vote infeasible, and refuses a vote without answers")
        void shouldSettleOnInfeasibleVote() throws Exception {
            stub = QuestionStub.start(settings(QuestionStub.QUESTION_BOUND).withQuorums(QUORUMS));
            activeWorker(Form.HOLD).ask(validEnvelope());
            vote(VOTER_A, "description", 80);
            var voter = connect("v-" + VOTER_B, 1, VOTER_B);
            voter.call("pm_wait", QuestionStub.JSON.createObjectNode());
            voter.link("ack", QuestionStub.JSON.createObjectNode());

            var noAnswers = voter.link("submit:vote", QuestionStub.JSON.createObjectNode());
            voter.link("infeasible", QuestionStub.JSON.createObjectNode().put("evidence", "I cannot judge it."));

            assertAll("the infeasible vote",
                    () -> assertTrue(noAnswers.contains("answers: a list is required"), noAnswers),
                    () -> assertEquals(QUESTION, stub.operatorDecisions().peek()));
        }

        private String vote(String role, String choice, int confidence) throws IOException {
            var voter = connect("v-" + role, 1, role);
            var offered = voter.call("pm_wait", QuestionStub.JSON.createObjectNode());
            assertTrue(offered.contains("vote-" + QUESTION + "-" + role), offered);
            voter.link("ack", QuestionStub.JSON.createObjectNode());
            return voter.link("submit:vote", voteArgs(choice, confidence, "The description is the normative part."));
        }

        private static ObjectNode voteArgs(String choice, int confidence, String rationale) {
            var args = QuestionStub.JSON.createObjectNode();
            args.putArray("answers").addObject().put("question_id", QUESTION).put("choice", choice)
                    .put("confidence", confidence).put("rationale", rationale);
            return args;
        }
    }

    @Nested
    @DisplayName("envelope")
    class Envelope {

        @Test
        @DisplayName("works its plan tasks in order and completes after the last submit")
        void shouldWorkPlanTasks() throws Exception {
            stub = start(1);
            var client = activeEnvelope(Form.FALLBACK);

            var second = client.link("submit:TASK-001", QuestionStub.JSON.createObjectNode().put("answer", "one"));
            var refused = client.link("submit:TASK-002",
                    QuestionStub.JSON.createObjectNode().put("count", 9).put("mode", "c"));
            var args = QuestionStub.JSON.createObjectNode().put("count", 2).put("mode", "a");
            args.putArray("items").add("x");
            var completed = client.link("submit:TASK-002", args);

            assertAll("the envelope",
                    () -> assertTrue(second.contains("task_id: TASK-002"), second),
                    () -> assertTrue(refused.contains("count: an integer [1..5] is required"), refused),
                    () -> assertTrue(refused.contains("mode: one of [a|b] is required"), refused),
                    () -> assertTrue(refused.contains("items: a list is required"), refused),
                    () -> assertTrue(completed.contains("state: envelope.completed"), completed));
        }

        @Test
        @DisplayName("yields the rest of its plan tasks into the next envelope")
        void shouldYieldIntoNextEnvelope() throws Exception {
            stub = start(1);
            var client = activeEnvelope(Form.FALLBACK);

            var yielded = client.link("yield:envelope-1", QuestionStub.JSON.createObjectNode());

            assertAll("the yield",
                    () -> assertTrue(yielded.contains("state: envelope.completed"), yielded),
                    () -> assertTrue(stub.taskIds().contains("T-1-y1"), stub.taskIds().toString()),
                    () -> assertTrue(Files.readString(stub.events()).contains("\"entity\":\"envelope-2\"")));
        }

        @Test
        @DisplayName("an ask of a plan task ends the envelope, and the answer continues in the next envelope")
        void shouldContinueAskInNextEnvelope() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            var client = activeEnvelope(Form.FALLBACK);
            var args = QuestionStub.JSON.createObjectNode();
            args.set("decision", validEnvelope());

            var asked = client.link("ask:TASK-001", args);
            var answered = stub.answer(QUESTION, 1, List.of(answer()));

            assertAll("the envelope ask",
                    () -> assertTrue(asked.contains("state: task.asked"), asked),
                    () -> assertEquals(AnswerResult.ANSWERED, answered),
                    () -> assertTrue(stub.taskIds().contains("T-1-Q-001"), stub.taskIds().toString()),
                    () -> assertTrue(Files.readString(stub.events()).contains("\"yield_reason\":\"question\"")));
        }

        @Test
        @DisplayName("holds an ask of a plan task in envelope.task.awaiting_answer")
        void shouldHoldEnvelopeAsk() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            var client = activeEnvelope(Form.HOLD);
            var args = QuestionStub.JSON.createObjectNode();
            args.set("decision", validEnvelope());

            var asked = client.link("ask:TASK-001", args);
            stub.answer(QUESTION, 1, List.of(answer()));
            var delivered = client.call("pm_wait", QuestionStub.JSON.createObjectNode());
            var active = client.link("ack", QuestionStub.JSON.createObjectNode());

            assertAll("the held envelope ask",
                    () -> assertTrue(asked.contains("state: envelope.task.awaiting_answer"), asked),
                    () -> assertTrue(delivered.contains("state: task.answer"), delivered),
                    () -> assertTrue(active.contains("state: envelope.task.active"), active));
        }

        private Client activeEnvelope(Form form) throws IOException {
            var facts = QuestionStub.JSON.createObjectNode().put("goal", "Work the plan tasks.");
            stub.queue(TaskSpec.envelope(TASK, ROLE, "Work the envelope", facts,
                    List.of(new QuestionStub.PlanTask("TASK-001", "Do one", "answer:text[1..200]"),
                            new QuestionStub.PlanTask("TASK-002", "Do two",
                                    "count:int[1..5],mode:enum[a|b],items:list<text>")),
                    form));
            stub.leaseAtSpawn(TASK, WORKER, 1);
            var client = connect(1);
            var offered = client.state("envelope-1");
            assertTrue(offered.contains("state: task.offered"), offered);
            var active = client.link("ack", QuestionStub.JSON.createObjectNode());
            assertTrue(active.contains("state: envelope.task.active"), active);
            return client;
        }
    }

    @Nested
    @DisplayName("answers")
    class Answers {

        @Test
        @DisplayName("takes several options, a value, own words and none fits, labelled per part")
        void shouldTakeEveryAnswerForm() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            var client = activeWorker(Form.FALLBACK);
            client.ask(multiPartEnvelope());

            var answered = stub.answer(QUESTION, 1, List.of(
                    new PartAnswer("Q-001", "description+examples", null, "chosen"),
                    new PartAnswer("Q-002", "value", "42", "interpreted"),
                    new PartAnswer("Q-003", "own", "Neither, use the tests.", "own_option"),
                    new PartAnswer("Q-004", NONE_FITS_BRANCH, null, "none_fits")));

            var items = stub.decision(QUESTION).orElseThrow().path("items");
            assertAll("the answer forms",
                    () -> assertEquals(AnswerResult.ANSWERED, answered),
                    () -> assertEquals("Follow the description + Follow the examples",
                            items.get(0).path("label").asText()),
                    () -> assertEquals("42", items.get(1).path("text").asText()),
                    () -> assertEquals("own", items.get(2).path("label").asText()),
                    () -> assertEquals("none fits", items.get(3).path("label").asText()));
        }

        @Test
        @DisplayName("refuses an answer that leaves out or repeats a part, or gives own words without text")
        void shouldRefuseIncompleteAnswers() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            activeWorker(Form.FALLBACK).ask(multiPartEnvelope());
            var first = new PartAnswer("Q-001", "description", null, "chosen");

            var missing = stub.answer(QUESTION, 1, List.of(first));
            var repeated = stub.answer(QUESTION, 1, List.of(first, first));
            var emptyOwn = stub.answer(QUESTION, 1, List.of(first,
                    new PartAnswer("Q-002", "value", " ", "interpreted"),
                    new PartAnswer("Q-003", "own", null, "own_option"),
                    new PartAnswer("Q-004", NONE_FITS_BRANCH, null, "none_fits")));

            assertAll("the refusals",
                    () -> assertEquals(AnswerResult.INVALID_ANSWER, missing),
                    () -> assertEquals(AnswerResult.INVALID_ANSWER, repeated),
                    () -> assertEquals(AnswerResult.INVALID_ANSWER, emptyOwn));
        }

        @Test
        @DisplayName("a lost RESUME holder whose answer was delivered with a stored session becomes resumable")
        void shouldResumeAfterLossOfDeliveredResume() throws Exception {
            stub = start(QuestionStub.QUESTION_BOUND);
            var client = activeWorker(Form.RESUME);
            client.ask(validEnvelope());
            stub.workerExited(WORKER, 1, true);
            stub.answer(QUESTION, 1, List.of(answer()));
            stub.leaseAtSpawn(TASK, WORKER, 2);
            var resumed = connect(2);
            resumed.state();

            stub.lost(WORKER, 2, "silence");

            assertEquals(Optional.of("task.resumable"), stub.state(TASK));
        }

        @Test
        @DisplayName("the exit of a suspended worker in a stub that only observes exits records its stored session")
        void shouldObserveSuspendedExit() throws Exception {
            stub = QuestionStub.start(settings(QuestionStub.QUESTION_BOUND).withLoseOnExit(false));
            activeWorker(Form.RESUME).ask(validEnvelope());

            stub.workerExited(WORKER, 1, true);

            var events = Files.readString(stub.events());
            assertAll("the observed exit",
                    () -> assertEquals(Optional.of("task.suspended"), stub.state(TASK)),
                    () -> assertTrue(events.contains("\"event\":\"session_stored\""), events));
        }

        @Test
        @DisplayName("a released task without an answer is queued again")
        void shouldRequeueReleasedTask() throws Exception {
            stub = start(1);
            activeWorker(Form.FALLBACK);

            stub.lost(WORKER, 1, "silence");

            assertEquals(Optional.of("task.queued"), stub.state(TASK));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @ParameterizedTest(name = "{1}")
        @DisplayName("names each violation of a decision envelope")
        @MethodSource("de.planmarshall.e2e.question.stub.QuestionStubTest#invalidDecisions")
        void shouldNameDecisionViolation(String json, String expected) throws Exception {
            var errors = new ArrayList<String>();

            QuestionStub.validateDecision(json == null ? null : QuestionStub.JSON.readTree(json), errors);

            assertTrue(errors.stream().anyMatch(error -> error.contains(expected)), errors.toString());
        }

        @Test
        @DisplayName("accepts the valid envelope and a value part without options")
        void shouldAcceptValidDecisions() throws Exception {
            var errors = new ArrayList<String>();

            QuestionStub.validateDecision(validEnvelope(), errors);
            QuestionStub.validateDecision(multiPartEnvelope(), errors);

            assertTrue(errors.isEmpty(), errors.toString());
        }

        @Test
        @DisplayName("checks text, int, enum, list and schema parameters, and ignores what it cannot parse")
        void shouldValidateFreeParameters() throws Exception {
            var errors = new ArrayList<String>();
            var args = QuestionStub.JSON.readTree("{\"t\":\"ok\",\"n\":\"x\",\"e\":\"z\",\"l\":{},\"s\":null}");

            QuestionStub.validateFree("t:text,o:text[0..5],n:int,e:enum[a|b],l:list<text>,s:schema[x],?bad", args,
                    errors);
            QuestionStub.validateFree(" ", args, errors);

            assertEquals(List.of("n: an integer  is required", "e: one of [a|b] is required",
                    "l: a list is required", "s: a schema is required"), errors);
        }

        @Test
        @DisplayName("renders a nested list of objects as items and quotes keywords, numbers and leading dashes")
        void shouldRenderItemsAndQuote() {
            var node = QuestionStub.JSON.createObjectNode();
            var items = node.putArray("items");
            items.addObject().put("a", "x").putObject("nested").put("b", "y");
            items.add("plain");
            node.put("keyword", "true");
            node.put("number", "12.5");
            node.put("dash", "-x");
            node.put("escaped", "a\\b\r\tc");
            node.putNull("nothing");
            node.put("count", 3);

            var toon = QuestionStub.toon(node);

            assertEquals("""
                    items[2]:
                      - a: x
                        nested:
                          b: y
                      - plain
                    keyword: "true"
                    number: "12.5"
                    dash: "-x"
                    escaped: "a\\\\b\\r\\tc"
                    nothing: null
                    count: 3
                    """, toon);
        }
    }

    static List<Arguments> invalidDecisions() {
        var part = "\"question_text\":\"Which reading applies here?\",\"decision_kind\":\"interpretation\","
                + "\"own_words\":true,\"note\":false,\"delegation\":false";
        var options = "\"options\":[{\"branch_key\":\"aa\",\"label\":\"A a\",\"description\":\"Do a thing.\","
                + "\"consequences\":\"It is done.\"},{\"branch_key\":\"bb\",\"label\":\"B b\","
                + "\"description\":\"Do b thing.\",\"consequences\":\"It is done.\"}]";
        var head = "\"title\":\"A title\",\"header\":\"A header\",\"description\":\"A long description.\","
                + "\"evidence\":[],\"blocks\":\"task\",\"reversibility\":\"reversible\",";
        return List.of(
                Arguments.of(null, "decision: an object is required"),
                Arguments.of("{" + head + "\"extra\":1,\"parts\":[]}", "extra: not a field of the schema"),
                Arguments.of("{" + head.replace("\"evidence\":[]", "\"evidence\":\"x\"") + "\"parts\":[]}",
                        "evidence: an array of at most 20"),
                Arguments.of("{" + head + "\"parts\":[]}", "parts: one to six parts are required"),
                Arguments.of("{" + head + "\"parts\":[1]}", "parts[0].: an object is required"),
                Arguments.of("{" + head + "\"parts\":[{" + part + ",\"answer_type\":\"one\"}]}",
                        "options: an array is required"),
                Arguments.of("{" + head + "\"parts\":[{" + part + ",\"answer_type\":\"value\"," + options
                        + ",\"recommendation\":{}}]}", "options: none for value"),
                Arguments.of("{" + head + "\"parts\":[{" + part + ",\"answer_type\":\"value\",\"options\":[],"
                        + "\"recommendation\":{}}]}", "recommendation: null for value"),
                Arguments.of("{" + head + "\"parts\":[{" + part + ",\"answer_type\":\"one\","
                        + options.replace("\"bb\"", "\"aa\"") + ",\"recommendation\":{\"branch_key\":\"aa\","
                        + "\"confidence\":50,\"rationale\":\"Because it is so.\"}}]}",
                        "branch_key: a distinct key"),
                Arguments.of("{" + head + "\"parts\":[{" + part + ",\"answer_type\":\"one\"," + options
                        + ",\"recommendation\":{\"branch_key\":\"cc\",\"confidence\":500,"
                        + "\"rationale\":\"Because it is so.\"}}]}", "recommendation.branch_key: must name one"),
                Arguments.of("{" + head + "\"parts\":[{" + part + ",\"answer_type\":\"one\"," + options
                        + ",\"recommendation\":{\"branch_key\":\"aa\",\"confidence\":500,"
                        + "\"rationale\":\"Because it is so.\"}}]}", "recommendation.confidence: an integer"));
    }

    // --- helpers ---

    private static final String ADVISE = "advise";
    private static final String ADVISOR = "a1";
    private static final String VOTER_A = "voter_a";
    private static final String VOTER_B = "voter_b";
    private static final String NONE_FITS_BRANCH = "none_fits";
    private static final Map<String, QuestionStub.Quorum> QUORUMS = Map.of("interpretation",
            new QuestionStub.Quorum(List.of(VOTER_A, VOTER_B), 60));

    private static TaskSpec task(Form form, List<String> consult) {
        var facts = QuestionStub.JSON.createObjectNode();
        facts.put("task", "Implement the function as its contract says.");
        return TaskSpec.entity(TASK, ROLE, "Work the task in facts.task", facts,
                "answer:text[1..200],judgment:text[0..2000]", form, consult);
    }

    private Client activeWorker(TaskSpec spec) throws IOException {
        stub.queue(spec);
        stub.leaseAtSpawn(TASK, WORKER, 1);
        var client = connect(1);
        client.state();
        var active = client.link("ack", QuestionStub.JSON.createObjectNode());
        assertTrue(active.contains("state: entity.active"), active);
        return client;
    }

    private Client connect(String worker, int generation, String role) throws IOException {
        var client = Client.connect(socket, worker, generation, role);
        clients.add(client);
        return client;
    }

    /** Four parts: several options, a value, own words allowed, and one that none of its options fits. */
    private static ObjectNode multiPartEnvelope() throws IOException {
        var envelope = validEnvelope();
        var parts = (ArrayNode) envelope.get("parts");
        var several = (ObjectNode) parts.get(0);
        several.put("answer_type", "several");
        var value = parts.addObject();
        value.put("question_text", "Which limit applies to the input?");
        value.put("decision_kind", "interpretation");
        value.put("answer_type", "value");
        value.put("own_words", true).put("note", false).put("delegation", false);
        value.putArray("options");
        value.putNull("recommendation");
        parts.add(several.deepCopy().put("answer_type", "one"));
        parts.add(several.deepCopy().put("answer_type", "one"));
        return envelope;
    }

    private Settings settings(int questionBound) {
        return Settings.of(directory.resolve("stub"), socket).withWindow(Duration.ofMillis(300))
                .withQuestionBound(questionBound).withQuorums(Map.of());
    }

    private QuestionStub start(int questionBound) throws IOException {
        return QuestionStub.start(settings(questionBound));
    }

    private static TaskSpec task(Form form) {
        var facts = QuestionStub.JSON.createObjectNode();
        facts.put("task", "Implement the function as its contract says.");
        return TaskSpec.entity(TASK, ROLE, "Work the task in facts.task", facts,
                "answer:text[1..200],judgment:text[0..2000]", form, List.of());
    }

    private Client connect(int generation) throws IOException {
        var client = Client.connect(socket, WORKER, generation);
        clients.add(client);
        return client;
    }

    /** A worker leased the task at spawn, which it acknowledged. */
    private Client activeWorker(Form form) throws IOException {
        stub.queue(task(form));
        stub.leaseAtSpawn(TASK, WORKER, 1);
        var client = connect(1);
        var offered = client.state();
        assertTrue(offered.contains("state: task.offered"), offered);
        var active = client.link("ack", QuestionStub.JSON.createObjectNode());
        assertTrue(active.contains("state: entity.active"), active);
        return client;
    }

    /** A decision envelope the stub accepts: one part, two options, one recommended, one pm:// evidence item. */
    private static ObjectNode validEnvelope() throws IOException {
        return (ObjectNode) QuestionStub.JSON.readTree("""
                {"title":"Which reading applies","header":"The contract leaves the reading open",
                "description":"The task's contract can be read two ways; the answer decides the implementation.",
                "evidence":["pm://qlv/T-1"],"blocks":"task","reversibility":"reversible",
                "parts":[{"question_text":"Which reading of the contract applies?","decision_kind":"interpretation",
                "answer_type":"one","own_words":true,"note":false,"delegation":false,
                "options":[{"branch_key":"description","label":"Follow the description",
                "description":"Implement what the description says.","consequences":"The tests of the description pass."},
                {"branch_key":"examples","label":"Follow the examples",
                "description":"Implement what the examples show.","consequences":"The examples pass as written."}],
                "recommendation":{"branch_key":"description","confidence":70,
                "rationale":"The description is the normative part of the contract."}}]}
                """);
    }

    private static PartAnswer answer() {
        return new PartAnswer(QUESTION, "description", "The description is right.", "chosen");
    }

    private static int count(String text, String needle) {
        var count = 0;
        for (var index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    /** A worker's connection as its relay opens it: the identity first, then newline-delimited JSON-RPC. */
    private static final class Client implements AutoCloseable {

        private final SocketChannel channel;
        private final BufferedReader reader;
        private final OutputStream out;
        private final List<JsonNode> notifications = new ArrayList<>();
        private int nextId;

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

        String state() throws IOException {
            return state(TASK);
        }

        String state(String entity) throws IOException {
            var arguments = QuestionStub.JSON.createObjectNode();
            arguments.put("entity", entity);
            return call("pm_state", arguments);
        }

        String link(String link, ObjectNode args) throws IOException {
            return link(QuestionStub.PLAN_ID, "", link, args);
        }

        String link(String planId, String entity, String link, ObjectNode args) throws IOException {
            var arguments = QuestionStub.JSON.createObjectNode();
            arguments.put("plan_id", planId);
            if (!entity.isEmpty()) {
                arguments.put("entity", entity);
            }
            arguments.put("link", link);
            arguments.set("args", args);
            return call("pm_do", arguments);
        }

        /** A {@code pm_wait} with a progress token, as a host sends it that shows progress. */
        String waitWithProgress(String token) throws IOException {
            var params = QuestionStub.JSON.createObjectNode();
            params.put("name", "pm_wait");
            params.putObject("arguments");
            params.putObject("_meta").put("progressToken", token);
            return rpc("tools/call", QuestionStub.JSON.writeValueAsString(params)).path("result").path("content")
                    .get(0).path("text").asText();
        }

        /** @return the messages without an id the stub sent: its progress notifications */
        List<JsonNode> notifications() {
            return List.copyOf(notifications);
        }

        String ask(ObjectNode envelope) throws IOException {
            var args = QuestionStub.JSON.createObjectNode();
            args.set("decision", envelope);
            return link("ask", args);
        }

        String call(String tool, ObjectNode arguments) throws IOException {
            var params = QuestionStub.JSON.createObjectNode();
            params.put("name", tool);
            params.set("arguments", arguments);
            return rpc("tools/call", QuestionStub.JSON.writeValueAsString(params)).path("result").path("content")
                    .get(0).path("text").asText();
        }

        JsonNode rpc(String method, String params) throws IOException {
            var id = ++nextId;
            send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}");
            for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                var message = QuestionStub.JSON.readTree(line);
                if (message.path("id").asInt(-1) == id) {
                    return message;
                }
                if (!message.has("id")) {
                    notifications.add(message);
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
