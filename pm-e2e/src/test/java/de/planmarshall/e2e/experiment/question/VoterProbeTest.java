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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
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
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.experiment.question.QuestionDriver.Host;
import de.planmarshall.e2e.experiment.question.QuestionDriver.OpenCodeDataHome;
import de.planmarshall.e2e.experiment.question.QuestionStub.Form;
import de.planmarshall.e2e.experiment.question.QuestionStub.Quorum;
import de.planmarshall.e2e.experiment.question.QuestionStub.TaskSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The probe of OpenCode as a voter of Q6 (experiment only, real model calls): per candidate model and run, a stub with
 * the quorum of the measurement receives an ask from a scripted asker over its socket, which queues the vote tasks, and
 * the driver launches OpenCode through {@code pm-exec} as the voter of {@code quorum-b} with the launch of the
 * measurement ({@link QuestionLoopExperiment#voterLaunch(String, String)}), on the operator's global data home. Once
 * its vote task is closed the voter's next {@code pm_wait} returns {@code wait: end}; a voter still running at the run
 * limit is ended. The probe reports, from the stub's event log, whether the voter connected and called
 * {@code pm_wait} and {@code pm_do} (with the links it followed and whether its vote was submitted, refused or lost),
 * its exit, and the first line of its {@code stderr} that is not {@code pm-exec}'s own; one row per run is appended to
 * {@code voter-probe.txt} of the output directory. The runs are serial.
 * <p>
 * Disabled unless {@code -Dpm.experiment.voter.probe} names the models (comma-separated, {@code candidates} for
 * {@link #CANDIDATES}). Properties: {@code pm.experiment.voter.probe.runs} (1 to 3, default 3, per model),
 * {@code pm.experiment.voter.probe.first-run} (the index of the first run, for the labels, default 1),
 * {@code pm.experiment.voter.probe.limit} (the run limit, default {@code PT6M}), {@code pm.experiment.voter.probe.out}
 * (the output directory, default below the temporary directory, outside the repository).
 */
@DisplayName("Voter probe: OpenCode as the voter of Q6 with -Dpm.experiment.voter.probe=<models>")
@EnabledIfSystemProperty(named = "pm.experiment.voter.probe", matches = ".+")
class VoterProbeTest {

    /** The candidate voter models: the package's default voter and the model of the OpenCode runs. */
    static final List<String> CANDIDATES = List.of("opencode-go/muse-spark-1.3-contributor", "opencode/gpt-5.4-nano");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String VOTER_ROLE = "quorum-b";
    private static final String VOTE_TASK = QuestionStub.VOTE_PREFIX + "Q-001-" + VOTER_ROLE;
    private static final String ASKER = "w1";
    private static final String ROLE = "implement";
    private static final Duration POLL = Duration.ofSeconds(1);

    @Test
    @DisplayName("launches OpenCode as a voter per model and run and reports its calls and exit")
    void probe() throws Exception {
        var value = System.getProperty("pm.experiment.voter.probe").strip();
        var models = "candidates".equals(value) ? CANDIDATES : List.of(value.split(",")).stream().map(String::strip)
                .toList();
        var runs = Integer.getInteger("pm.experiment.voter.probe.runs", 3);
        if (runs < 1 || runs > 3) {
            throw new IllegalArgumentException("pm.experiment.voter.probe.runs must be 1 to 3: " + runs);
        }
        var firstRun = Integer.getInteger("pm.experiment.voter.probe.first-run", 1);
        var limit = Duration.parse(System.getProperty("pm.experiment.voter.probe.limit", "PT6M"));
        var stamp = Instant.now().toString().replace(":", "").replace(".", "");
        var out = Path.of(System.getProperty("pm.experiment.voter.probe.out",
                Path.of(System.getProperty("java.io.tmpdir"), "pm-voter-probe").toString()));
        Files.createDirectories(out);
        var exec = QuestionDriver.resolveExec();
        var report = out.resolve("voter-probe.txt");
        for (var model : models) {
            for (var index = firstRun; index < firstRun + runs; index++) {
                var row = run(model, index, limit, out.resolve("ws-" + stamp), exec);
                Files.writeString(report, row + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        }
        assertTrue(Files.isRegularFile(report), "no report written");
    }

    /** One run of one model: the row of the table. */
    private static String run(String model, int index, Duration limit, Path workspace, List<String> exec)
            throws IOException, InterruptedException {
        var bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        var suffix = HexFormat.of().formatHex(bytes);
        var base = workspace.resolve(model.replace('/', '_') + "-" + index + "-" + suffix);
        var stubSettings = QuestionStub.Settings.of(base.resolve("stub"), Path.of("/tmp", "pmq-vp-" + suffix
                + ".sock")).withWindow(Host.OPENCODE.boundedWait()).withQuorums(Map.of("interpretation",
                        new Quorum(List.of("quorum-a", VOTER_ROLE), 90)));
        var settings = new QuestionDriver.Settings(Host.OPENCODE, model, base.resolve("workers"), base.resolve("run"),
                exec, OpenCodeDataHome.GLOBAL);
        try (var driver = QuestionDriver.start(stubSettings, settings)) {
            ask(driver.stub(), stubSettings.socket());
            var started = System.currentTimeMillis();
            var voter = driver.startLeased(VOTE_TASK, VOTE_TASK, QuestionLoopExperiment.voterLaunch(VOTE_TASK,
                    VOTER_ROLE));
            var deadline = started + limit.toMillis();
            var ended = false;
            while (voter.process().isAlive() && System.currentTimeMillis() < deadline) {
                if (!ended && driver.stub().state(VOTE_TASK).filter(state -> !state.startsWith("task.offered")
                        && (state.endsWith(".completed") || "task.lost".equals(state)
                        || "task.infeasible".equals(state))).isPresent()) {
                    driver.stub().end(VOTE_TASK, 1);
                    ended = true;
                }
                Thread.sleep(POLL);
            }
            var exit = voter.process().isAlive() ? "none (ended at " + limit + ")"
                    : Integer.toString(voter.process().exitValue());
            if (voter.process().isAlive()) {
                driver.terminate(voter);
            }
            var seconds = (System.currentTimeMillis() - started) / 1000;
            return row(model, index, driver, voter, exit, seconds, base);
        }
    }

    private static String row(String model, int index, QuestionDriver driver, QuestionDriver.Worker voter,
            String exit, long seconds, Path base) throws IOException {
        var connected = false;
        var tools = new TreeSet<String>();
        var links = new ArrayList<String>();
        var outcome = "none";
        var refused = 0;
        for (var event : QuestionResults.readJsonLines(driver.stub().events())) {
            if (!VOTE_TASK.equals(event.path("worker").asText()) && !VOTE_TASK.equals(event.path("task").asText())) {
                continue;
            }
            switch (event.path("event").asText()) {
                case QuestionDriver.CONNECTED -> connected = true;
                case QuestionDriver.CALL -> {
                    tools.add(event.path("tool").asText());
                    if (!event.path("link").asText().isEmpty()) {
                        links.add(event.path("link").asText());
                    }
                }
                case "vote_refused" -> refused++;
                case "closed" -> outcome = event.path("outcome").asText();
                default -> {
                    // not a figure of the probe
                }
            }
        }
        var stderr = driver.stderr(voter).lines().filter(line -> !line.isBlank() && !QuestionDriver.pmExecLine(line))
                .findFirst().orElse("");
        return String.join(" | ", model, "run " + index, "connected " + connected,
                "pm_wait " + tools.contains("pm_wait"), "pm_do " + tools.contains("pm_do"),
                "pm_state " + tools.contains("pm_state"), "links " + links, "vote " + outcome,
                "vote_refused " + refused, "exit " + exit, seconds + " s", "stderr: " + stderr, "dir " + base);
    }

    /**
     * The asker of the probe, a scripted worker over the stub's socket: it takes the task, acknowledges it and asks the
     * example envelope of the contract (an interpretation), which queues the vote tasks of the quorum.
     */
    private static void ask(QuestionStub stub, Path socket) throws IOException {
        var facts = QuestionStub.JSON.createObjectNode();
        facts.put("task", "Implement the function as its contract says.");
        stub.queue(TaskSpec.entity("T-1", ROLE, "Work the task in facts.task", facts,
                "answer:text[1..200],judgment:text[0..2000]", Form.FALLBACK, List.of()));
        stub.leaseAtSpawn("T-1", ASKER, 1);
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            var reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel),
                    StandardCharsets.UTF_8));
            var out = Channels.newOutputStream(channel);
            send(out, "{\"worker\":\"" + ASKER + "\",\"generation\":1,\"role\":\"" + ROLE + "\",\"harness\":\"probe\"}");
            call(out, reader, 1, "pm_do", link("ack", QuestionStub.JSON.createObjectNode()));
            var args = QuestionStub.JSON.createObjectNode();
            args.set("decision", QuestionStub.JSON.readTree(QuestionDriver.ENVELOPE_EXAMPLE));
            var asked = call(out, reader, 2, "pm_do", link("ask", args));
            if (!asked.contains("state: task.asked")) {
                throw new IllegalStateException("the scripted ask was not recorded: " + asked);
            }
        }
    }

    private static ObjectNode link(String link, JsonNode args) {
        var arguments = QuestionStub.JSON.createObjectNode();
        arguments.put("plan_id", QuestionStub.PLAN_ID);
        arguments.put("link", link);
        arguments.set("args", args);
        return arguments;
    }

    private static String call(OutputStream out, BufferedReader reader, int id, String tool, ObjectNode arguments)
            throws IOException {
        var request = QuestionStub.JSON.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", "tools/call");
        var params = request.putObject("params");
        params.put("name", tool);
        params.set("arguments", arguments);
        send(out, QuestionStub.JSON.writeValueAsString(request));
        for (var line = reader.readLine(); line != null; line = reader.readLine()) {
            var message = QuestionStub.JSON.readTree(line);
            if (message.path("id").asInt(-1) == id) {
                return message.path("result").path("content").get(0).path("text").asText();
            }
        }
        throw new IOException("the stub closed the connection before response " + id);
    }

    private static void send(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
