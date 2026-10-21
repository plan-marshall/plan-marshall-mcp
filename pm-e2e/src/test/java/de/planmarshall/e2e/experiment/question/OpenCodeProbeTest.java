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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The probe of the OpenCode CLI (experiment only) the launch of {@link QuestionDriver#OPENCODE_ARGV} rests on: runs the
 * steps named by {@code -Dpm.experiment.opencode.probe} (comma-separated: {@code config}, {@code stub-run}) and writes
 * what OpenCode did to {@code -Dpm.experiment.opencode.probe.out}, a directory outside the repository. {@code config}
 * lists the paths, configuration sources, agents and MCP servers OpenCode sees in a worker directory with the driver's
 * {@code opencode.json} and data home; {@code stub-run} starts {@code opencode run} against the stub
 * ({@code pm.experiment.opencode.probe.model}, {@code .window}, {@code .phases} of
 * {@code new,resume,missing,unreadable}, {@code .flags}: further flags after {@code run}, comma-separated).
 */
@DisplayName("OpenCode probe: one step with -Dpm.experiment.opencode.probe=<step>")
@EnabledIfSystemProperty(named = "pm.experiment.opencode.probe", matches = ".+")
class OpenCodeProbeTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static String opencodeExecutable;

    @Test
    @DisplayName("runs the probe step")
    void probe() throws Exception {
        opencodeExecutable = QuestionDriver.executable(QuestionDriver.Host.OPENCODE, AgyProbeTest.environment(null));
        var out = Path.of(System.getProperty("pm.experiment.opencode.probe.out"));
        Files.createDirectories(out);
        for (var step : System.getProperty("pm.experiment.opencode.probe").split(",")) {
            var report = new StringBuilder();
            switch (step.strip()) {
                case "config" -> config(out, report);
                case "stub-run" -> stubRun(out, report);
                default -> throw new IllegalArgumentException(step);
            }
            var file = out.resolve(step.strip() + "-" + System.currentTimeMillis() + ".txt");
            Files.writeString(file, report.toString(), StandardCharsets.UTF_8);
            assertTrue(Files.isRegularFile(file));
        }
    }

    /** What OpenCode reads in a worker directory with the driver's configuration and data home. */
    private static void config(Path out, StringBuilder report) throws IOException, InterruptedException {
        var workDir = Files.createDirectories(out.resolve("cfg-" + System.currentTimeMillis()));
        var env = AgyProbeTest.environment(null);
        env.putAll(QuestionStub.relayEnvironment(Path.of("/tmp/pmq-none.sock"), "w1", 1, "implement", "opencode",
                out.resolve("relay-none.jsonl")));
        writeConfig(workDir, env);
        env.put("XDG_DATA_HOME", QuestionDriver.opencodeDataHome(workDir).toString());
        report.append("workDir=").append(workDir).append('\n');
        env.put("PWD", workDir.toString());
        for (var command : List.of(List.of("debug", "paths"), List.of("debug", "config"), List.of("debug", "agents"),
                List.of("mcp", "list"), List.of("service", "status"), List.of("models"),
                List.of("models", "--standalone"))) {
            var argv = new ArrayList<String>();
            argv.add(opencodeExecutable);
            argv.addAll(command);
            report.append("== ").append(String.join(" ", command)).append('\n')
                    .append(AgyProbeTest.run(argv, workDir, env, Duration.ofSeconds(90))).append('\n');
        }
    }

    /**
     * Real runs against the stub: a new session that reads the leased task and holds one {@code pm_wait} for the
     * window, its resume by {@code --session}, and resumes of an id that does not exist and of an unreadable one.
     */
    private static void stubRun(Path out, StringBuilder report) throws IOException, InterruptedException {
        var stamp = Long.toString(System.currentTimeMillis());
        var model = System.getProperty("pm.experiment.opencode.probe.model");
        var window = Duration.ofSeconds(Long.getLong("pm.experiment.opencode.probe.window", 150));
        var phases = List.of(System.getProperty("pm.experiment.opencode.probe.phases",
                "new,resume,missing,unreadable").split(","));
        var flags = System.getProperty("pm.experiment.opencode.probe.flags", "");
        var workDir = Files.createDirectories(out.resolve("wk-" + stamp));
        var stubDir = out.resolve("stub-" + stamp);
        var socket = Path.of("/tmp", "pmq-oc-" + stamp.substring(stamp.length() - 6) + ".sock");
        var settings = QuestionStub.Settings.of(stubDir, socket).withWindow(window);
        try (var stub = QuestionStub.start(settings)) {
            var fixture = QuestionFixtures.probe();
            QuestionFixtures.prepare(fixture, workDir);
            stub.queue(QuestionFixtures.entityTask(fixture, "T-1", "implement", QuestionStub.Form.HOLD));
            stub.leaseAtSpawn("T-1", "w1", 1);
            var env = AgyProbeTest.environment(null);
            env.putAll(QuestionStub.relayEnvironment(socket, "w1", 1, "implement", "opencode",
                    out.resolve("relay-" + stamp + ".jsonl")));
            writeConfig(workDir, env);
            if (!Boolean.getBoolean("pm.experiment.opencode.probe.global-data")) {
                env.put("XDG_DATA_HOME", QuestionDriver.opencodeDataHome(workDir).toString());
            }
            if (!Boolean.getBoolean("pm.experiment.opencode.probe.keep-pwd")) {
                env.put("PWD", workDir.toString());
            }
            report.append("PWD=").append(env.get("PWD")).append('\n');
            report.append("model=").append(model).append(" window=").append(window).append(" flags=").append(flags)
                    .append(" workDir=").append(workDir).append('\n');
            String session = System.getProperty("pm.experiment.opencode.probe.session");
            if (phases.contains("new")) {
                var first = opencode(flags, model, null, "Use the tools of the MCP server plan-marshall. First call "
                        + "pm_state with entity \"T-1\". Then call pm_wait once, without arguments, and wait for its "
                        + "result. Then end your turn with exactly the first line of the pm_wait result.", workDir, env,
                        window.plus(Duration.ofMinutes(6)));
                report.append("== new session\n").append(first).append('\n');
                session = sessionId(first.out());
                report.append("session id found: ").append(session).append('\n');
                report.append("== stub events after new\n").append(Files.readString(stub.events())).append('\n');
            }
            if (phases.contains("resume") && session != null) {
                var resumed = opencode(flags, model, session, "Which entity did you read with pm_state? Answer with "
                        + "the entity only.", workDir, env, Duration.ofMinutes(6));
                report.append("== resume ").append(session).append('\n').append(resumed).append('\n')
                        .append("session id found: ").append(sessionId(resumed.out())).append('\n');
            }
            if (phases.contains("missing")) {
                var bytes = new byte[13];
                RANDOM.nextBytes(bytes);
                var missingId = "ses_" + HexFormat.of().formatHex(bytes);
                var missing = opencode(flags, model, missingId, "Answer with the word ok only.", workDir, env,
                        Duration.ofMinutes(4));
                report.append("== resume of a missing id ").append(missingId).append('\n').append(missing).append('\n')
                        .append("session id found: ").append(sessionId(missing.out())).append('\n');
            }
            if (phases.contains("unreadable")) {
                var unreadable = opencode(flags, model, "not a session id!", "Answer with the word ok only.", workDir,
                        env, Duration.ofMinutes(4));
                report.append("== resume of an unreadable id\n").append(unreadable).append('\n')
                        .append("session id found: ").append(sessionId(unreadable.out())).append('\n');
            }
            report.append("== session list (worker environment)\n")
                    .append(AgyProbeTest.run(List.of(opencodeExecutable, "session", "list"), workDir, env,
                            Duration.ofSeconds(60))).append('\n');
            report.append("== stub events\n").append(Files.readString(stub.events())).append('\n');
            report.append("== files below the worker data home\n").append(names(workDir.resolve("data")));
        }
    }

    private static AgyProbeTest.Result opencode(String flags, String model, String session, String prompt,
            Path workDir, Map<String, String> env, Duration timeout) throws IOException, InterruptedException {
        var argv = new ArrayList<>(QuestionDriver.OPENCODE_ARGV);
        var extra = new ArrayList<String>();
        for (var flag : flags.split(",")) {
            if (!flag.isBlank()) {
                extra.add(flag.strip());
            }
        }
        argv.addAll(2, extra);
        var agent = System.getProperty("pm.experiment.opencode.probe.agent");
        if (agent != null) {
            argv.set(argv.indexOf(QuestionDriver.OPENCODE_AGENT), agent);
        }
        var values = new LinkedHashMap<String, String>();
        values.put("{prompt}", prompt);
        values.put("{model}", model);
        values.put("{session_id}", session);
        var command = QuestionDriver.expand(argv, values);
        command.set(0, opencodeExecutable);
        return AgyProbeTest.run(command, workDir, env, timeout);
    }

    private static void writeConfig(Path workDir, Map<String, String> env) throws IOException {
        var launch = new QuestionDriver.Launch("w1", 1, "implement", "", true, null,
                List.of("read", "write", "edit", "glob", "grep"), null);
        Files.writeString(workDir.resolve("opencode.json"),
                QuestionDriver.opencodeConfig(QuestionStub.relayCommand(), launch, env));
    }

    private static String sessionId(String output) {
        for (var line : output.split("\n")) {
            try {
                var node = QuestionStub.JSON.readTree(line);
                var value = node == null ? null : node.findValue("sessionID");
                if (value != null && value.isTextual()) {
                    return value.asText();
                }
            } catch (IOException _) {
                // not a JSON line
            }
        }
        return null;
    }

    private static String names(Path root) throws IOException {
        if (!Files.exists(root)) {
            return "(none)\n";
        }
        var text = new StringBuilder();
        try (Stream<Path> walk = Files.walk(root)) {
            for (var path : walk.sorted().toList()) {
                text.append(root.relativize(path)).append(Files.isDirectory(path) ? "/" : " " + Files.size(path))
                        .append('\n');
            }
        }
        return text.toString();
    }
}
