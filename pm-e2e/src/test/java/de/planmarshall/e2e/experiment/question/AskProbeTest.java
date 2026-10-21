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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.experiment.question.QuestionDriver.Host;
import de.planmarshall.e2e.experiment.question.QuestionFixtures.Fixture;
import de.planmarshall.e2e.experiment.question.QuestionFixtures.Reply;
import de.planmarshall.e2e.experiment.question.QuestionStub.Form;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The probe of the ask (experiment only, not a measured item): runs {@code -Dpm.experiment.ask.probe=<n>} Claude Code
 * runs (at most {@value #MAX_RUNS} per invocation) on one forced apply-answer fixture, alternately as an entity task
 * and as an envelope task, and reports per run the asks, the {@code ask_refused} events with their detail, the
 * submissions and whether the first submission came before any ask. It shows whether the forced fixtures make the
 * worker ask; it never runs with {@code -Dpm.experiment.question} and never writes {@code runs.jsonl}.
 * <p>
 * Each run is fresh, in the continuation form hold, so that the answer reaches the same worker and its submissions
 * follow: the operator answers the recommended option with the fixture's reply. Properties:
 * {@code pm.experiment.ask.probe.first} (the index of the first run, {@code 0}: an even index is an entity run, an odd
 * one an envelope run), {@code pm.experiment.ask.probe.fixture} (the index of the apply-answer fixture, {@code 0}),
 * {@code pm.experiment.ask.probe.timeout} ({@code PT4M} per run), {@code pm.experiment.model.claude}
 * ({@value QuestionLoopExperiment#CLAUDE_MODEL}), {@code pm.experiment.corpus-cache}, and
 * {@code pm.experiment.workspace} (below the temporary directory, outside the repository), where the worker
 * directories, the run logs and the report {@code ask-probe-<stamp>.json} go.
 */
@DisplayName("Ask probe: Claude Code runs on forced fixtures with -Dpm.experiment.ask.probe=<runs>")
@EnabledIfSystemProperty(named = "pm.experiment.ask.probe", matches = "\\d+")
class AskProbeTest {

    /** The most runs one invocation may start. */
    static final int MAX_RUNS = 6;

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ROLE = "implement";
    private static final String TASK_ID = "T-1";
    private static final String EVENT = "event";
    private static final Duration POLL = Duration.ofSeconds(2);

    @Test
    @DisplayName("runs the forced fixtures on Claude Code and reports asks, refusals and submissions per run")
    void probe() throws Exception {
        var runs = Integer.parseInt(System.getProperty("pm.experiment.ask.probe"));
        if (runs < 1 || runs > MAX_RUNS) {
            throw new IllegalArgumentException("-Dpm.experiment.ask.probe=" + runs + ": 1 to " + MAX_RUNS);
        }
        var first = Integer.getInteger("pm.experiment.ask.probe.first", 0);
        var cache = Path.of(System.getProperty("pm.experiment.corpus-cache",
                ROOT.resolve("pm-mcp-server/target/corpus-cache").toString()));
        var fixture = QuestionFixtures.applyAnswer(cache).get(Integer.getInteger("pm.experiment.ask.probe.fixture",
                0));
        var stamp = Instant.now().toString().replace(":", "").replace(".", "");
        var workspace = Path.of(System.getProperty("pm.experiment.workspace",
                Path.of(System.getProperty("java.io.tmpdir"), "pm-qlv-ask-probe-" + stamp).toString()));
        var timeout = Duration.parse(System.getProperty("pm.experiment.ask.probe.timeout", "PT4M"));
        var model = System.getProperty("pm.experiment.model.claude", QuestionLoopExperiment.CLAUDE_MODEL);
        var report = QuestionStub.JSON.createObjectNode();
        report.put("fixture", fixture.id());
        report.put("model", model);
        report.put("workspace", workspace.toString());
        var array = report.putArray("runs");
        for (var index = first; index < first + runs; index++) {
            array.add(run(fixture, index % 2 == 0 ? "entity" : "envelope", index, workspace, model, timeout));
        }
        Files.createDirectories(workspace);
        var file = workspace.resolve("ask-probe-" + stamp + ".json");
        Files.writeString(file, QuestionStub.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8);

        assertTrue(Files.isRegularFile(file), "no report written");
    }

    private static ObjectNode run(Fixture fixture, String kind, int index, Path workspace, String model,
            Duration timeout) throws IOException, InterruptedException {
        var bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        var suffix = HexFormat.of().formatHex(bytes);
        var id = "probe-" + kind + "-" + index + "-" + suffix;
        var base = workspace.resolve(id);
        var stubSettings = QuestionStub.Settings.of(base.resolve("stub"), Path.of("/tmp", "pmq-" + suffix + ".sock"))
                .withWindow(Host.CLAUDE.boundedWait());
        var settings = new QuestionDriver.Settings(Host.CLAUDE, model, base.resolve("workers"), base.resolve("run"),
                QuestionDriver.resolveExec());
        var result = QuestionStub.JSON.createObjectNode();
        result.put("run", index);
        result.put("kind", kind);
        try (var driver = QuestionDriver.start(stubSettings, settings)) {
            QuestionFixtures.prepare(fixture, settings.workspace().resolve("w1"));
            var spec = "envelope".equals(kind) ? QuestionFixtures.envelopeTask(fixture, TASK_ID, ROLE, Form.HOLD)
                    : QuestionFixtures.entityTask(fixture, TASK_ID, ROLE, Form.HOLD);
            var worker = driver.startFresh(spec, QuestionLoopExperiment.workerLaunch("w1", 1, ROLE, Host.CLAUDE,
                    false, null));
            var deadline = System.currentTimeMillis() + timeout.toMillis();
            while (worker.process().isAlive() && System.currentTimeMillis() < deadline
                    && driver.launchFailure().isEmpty()) {
                driver.answerNext(POLL, Duration.ZERO, decision -> QuestionFixtures.answer(fixture, decision,
                        Reply.OPTION));
            }
            result.put("timed_out", worker.process().isAlive());
            driver.terminate(worker);
            driver.launchFailure().ifPresent(failure -> result.put("launch_failed", failure.stderr()));
            summarize(QuestionResults.readJsonLines(driver.stub().events()), result);
            result.put("final_text", QuestionResults.finalText(QuestionResults.readJsonLines(worker.output())));
            result.put("stub_dir", stubSettings.directory().toString());
        }
        return result;
    }

    private static void summarize(Iterable<JsonNode> events, ObjectNode result) {
        var asks = 0;
        var submissions = 0;
        var submittedBeforeAsk = false;
        var refusals = result.putArray("ask_refused");
        var submitted = result.putArray("submitted");
        var refusedLinks = result.putArray("link_or_submit_refused");
        for (var event : events) {
            switch (event.path(EVENT).asText()) {
                case "asked" -> asks++;
                case "ask_refused" -> refusals.add(event.path("detail").asText());
                case "submitted" -> {
                    submittedBeforeAsk |= asks == 0;
                    submissions++;
                    var entry = submitted.addObject();
                    entry.put("link", event.path("link").asText());
                    entry.set("args", event.path("args"));
                }
                case "link_refused", "submit_refused" -> refusedLinks.add(event.path(EVENT).asText() + " "
                        + event.path("link").asText() + " " + event.path("detail").asText(""));
                default -> {
                    // not reported
                }
            }
        }
        result.put("asks", asks);
        result.put("ask_refused_count", refusals.size());
        result.put("submissions", submissions);
        result.put("submitted_before_ask", submittedBeforeAsk);
    }
}
