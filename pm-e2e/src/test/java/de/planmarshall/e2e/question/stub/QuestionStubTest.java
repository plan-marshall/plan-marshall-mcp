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

    // --- helpers ---

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
