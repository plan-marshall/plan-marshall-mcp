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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;


import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The probe of the Antigravity CLI (experiment only) the launch of {@link QuestionDriver#AGY_ARGV} rests on: runs the
 * steps named by {@code -Dpm.experiment.agy.probe} (comma-separated: {@code mcp-home}, {@code workspace-config},
 * {@code home-login}, {@code stub-run}) and writes what agy did to {@code -Dpm.experiment.agy.probe.out}, a directory
 * outside the repository. {@code stub-run} starts agy against the stub ({@code pm.experiment.agy.probe.model},
 * {@code .window}, {@code .phases} of {@code new,resume,missing}, {@code .plugin}, {@code .conversation}).
 */
@DisplayName("Antigravity probe: one step with -Dpm.experiment.agy.probe=<step>")
@EnabledIfSystemProperty(named = "pm.experiment.agy.probe", matches = ".+")
class AgyProbeTest {

    private static final String AGY = System.getProperty("pm.experiment.agy.executable",
            Path.of(System.getProperty("user.home"), ".local/bin/agy").toString());

    record Result(int exit, String out, String err, long millis) {
    }

    @Test
    @DisplayName("runs the probe step")
    void probe() throws Exception {
        var out = Path.of(System.getProperty("pm.experiment.agy.probe.out"));
        Files.createDirectories(out);
        for (var step : System.getProperty("pm.experiment.agy.probe").split(",")) {
            step(step.strip(), out);
        }
    }

    private static void step(String step, Path out) throws IOException, InterruptedException {
        var report = new StringBuilder();
        switch (step) {
            case "mcp-home" -> mcpHome(out, report);
            case "workspace-config" -> workspaceConfig(out, report);
            case "home-login" -> homeLogin(out, report);
            case "stub-run" -> stubRun(out, report);
            default -> throw new IllegalArgumentException(step);
        }
        var file = out.resolve(step + ".txt");
        Files.writeString(file, report.toString(), StandardCharsets.UTF_8);
        assertTrue(Files.isRegularFile(file));
    }

    /** Where {@code agy mcp add} writes its configuration, with HOME redirected to an empty directory. */
    private static void mcpHome(Path out, StringBuilder report) throws IOException, InterruptedException {
        var home = Files.createDirectories(out.resolve("home-" + System.currentTimeMillis()));
        var env = environment(home);
        var add = run(List.of(AGY, "mcp", "add", "-e", "PM_Q_PROBE=1", "plan-marshall", "/bin/cat", "probe-arg"),
                home, env, Duration.ofSeconds(60));
        report.append("== mcp add\n").append(add).append('\n');
        var list = run(List.of(AGY, "mcp", "list"), home, env, Duration.ofSeconds(60));
        report.append("== mcp list\n").append(list).append('\n');
        report.append("== files below HOME\n").append(tree(home));
    }

    /** Whether agy reads an MCP configuration below its working directory (real HOME). */
    private static void workspaceConfig(Path out, StringBuilder report) throws IOException, InterruptedException {
        var dir = Files.createDirectories(out.resolve("ws-" + System.currentTimeMillis()));
        var candidates = List.of(".gemini/config/mcp_config.json", ".gemini/mcp_config.json",
                ".agents/mcp_config.json", ".antigravity/mcp_config.json", "mcp_config.json", ".mcp.json",
                ".gemini/settings.json");
        var index = 0;
        for (var candidate : candidates) {
            var file = dir.resolve(candidate);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"mcpServers\":{\"cand" + index++ + "\":{\"command\":\"/bin/cat\",\"args\":[\""
                    + candidate + "\"]}}}");
        }
        report.append("candidates: ").append(candidates).append('\n');
        var list = run(List.of(AGY, "mcp", "list"), dir, environment(null), Duration.ofSeconds(60));
        report.append("== mcp list (cwd = workspace)\n").append(list).append('\n');
    }

    /** Whether agy keeps its login with HOME redirected to an empty directory. */
    private static void homeLogin(Path out, StringBuilder report) throws IOException, InterruptedException {
        var home = Files.createDirectories(out.resolve("login-" + System.currentTimeMillis()));
        var models = run(List.of(AGY, "models"), home, environment(home), Duration.ofSeconds(90));
        report.append("== models (empty HOME)\n").append(models).append('\n');
        report.append("== files below HOME\n").append(tree(home));
    }

    /**
     * A real run against the stub: the MCP server as a workspace plugin of the worker directory, a new conversation
     * that reads the leased task and holds one {@code pm_wait}, its resume by {@code --conversation}, and a resume of
     * an id that does not exist.
     */
    private static void stubRun(Path out, StringBuilder report) throws IOException, InterruptedException {
        var stamp = Long.toString(System.currentTimeMillis());
        var model = System.getProperty("pm.experiment.agy.probe.model", "gemini-3.8-flash-low");
        var window = Duration.ofSeconds(Long.getLong("pm.experiment.agy.probe.window", 150));
        var phases = List.of(System.getProperty("pm.experiment.agy.probe.phases", "new,resume,missing").split(","));
        var workDir = Files.createDirectories(out.resolve("wk-" + stamp));
        var stubDir = out.resolve("stub-" + stamp);
        var socket = Path.of("/tmp", "pmq-agy-" + stamp.substring(stamp.length() - 6) + ".sock");
        var settings = QuestionStub.Settings.of(stubDir, socket).withWindow(window);
        try (var stub = QuestionStub.start(settings)) {
            var fixture = QuestionFixtures.probe();
            QuestionFixtures.prepare(fixture, workDir);
            stub.queue(QuestionFixtures.entityTask(fixture, "T-1", "implement", QuestionStub.Form.HOLD));
            stub.leaseAtSpawn("T-1", "w1", 1);
            var env = environment(null);
            env.putAll(QuestionStub.relayEnvironment(socket, "w1", 1, "implement", "agy",
                    out.resolve("relay-" + stamp + ".jsonl")));
            var pluginName = System.getProperty("pm.experiment.agy.probe.plugin", "plan-marshall");
            var plugin = Files.createDirectories(workDir.resolve(".agents/plugins/" + pluginName));
            Files.writeString(plugin.resolve("plugin.json"), "{\"name\":\"" + pluginName + "\"}");
            Files.writeString(plugin.resolve("mcp_config.json"), mcpConfig(env));
            report.append("model=").append(model).append(" window=").append(window).append(" workDir=")
                    .append(workDir).append('\n');
            var plugins = run(List.of(AGY, "plugin", "list"), workDir, env, Duration.ofSeconds(60));
            report.append("== plugin list (cwd = worker directory)\n").append(plugins).append('\n');
            String conversation = System.getProperty("pm.experiment.agy.probe.conversation");
            if (phases.contains("new")) {
                var prompt = "Use the tools of the MCP server plan-marshall. First call pm_state with entity \"T-1\". "
                        + "Then call pm_wait once, without arguments, and wait for its result. Then end your turn "
                        + "with exactly the first line of the pm_wait result.";
                var command = list(AGY, "--print", prompt, "--output-format", "json",
                        "--dangerously-skip-permissions", "--print-timeout", "600s");
                if (!"none".equals(model)) {
                    command.addAll(List.of("--model", model));
                }
                var first = run(command, workDir, env, Duration.ofMinutes(12));
                report.append("== new conversation\n").append(first).append('\n');
                conversation = conversationId(first.out());
                report.append("conversation id found: ").append(conversation).append('\n');
            }
            if (phases.contains("resume") && conversation != null) {
                var resumed = run(List.of(AGY, "--print", "Which entity did you read with pm_state? Answer with the "
                                + "entity only.", "--conversation", conversation, "--output-format", "json", "--model", model,
                                "--dangerously-skip-permissions", "--print-timeout", "300s"), workDir, env,
                        Duration.ofMinutes(6));
                report.append("== resume ").append(conversation).append('\n').append(resumed).append('\n');
            }
            if (phases.contains("missing")) {
                var missing = run(List.of(AGY, "--print", "Continue.", "--conversation",
                                UUID.randomUUID().toString(), "--output-format", "json", "--model", model,
                                "--dangerously-skip-permissions", "--print-timeout", "120s"), workDir, env,
                        Duration.ofMinutes(4));
                report.append("== resume of a missing id\n").append(missing).append('\n');
            }
            report.append("== stub events\n").append(Files.readString(stub.events())).append('\n');
        }
    }

    private static String mcpConfig(Map<String, String> env) throws IOException {
        var root = QuestionStub.JSON.createObjectNode();
        var server = root.putObject("mcpServers").putObject(QuestionStub.SERVER);
        var relay = QuestionStub.relayCommand();
        server.put("command", relay.getFirst());
        var args = server.putArray("args");
        relay.subList(1, relay.size()).forEach(args::add);
        var serverEnv = server.putObject("env");
        env.forEach((name, value) -> {
            if (name.startsWith("PM_Q_")) {
                serverEnv.put(name, value);
            }
        });
        return QuestionStub.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private static String conversationId(String output) {
        for (var line : output.split("\n")) {
            try {
                var node = QuestionStub.JSON.readTree(line);
                for (var name : List.of("conversation_id", "conversationId", "session_id", "sessionId")) {
                    var value = node == null ? null : node.findValue(name);
                    if (value != null && value.isTextual()) {
                        return value.asText();
                    }
                }
            } catch (JsonProcessingException _) {
                // not a JSON line
            }
        }
        try {
            var node = QuestionStub.JSON.readTree(output);
            for (var name : List.of("conversation_id", "conversationId", "session_id", "sessionId")) {
                var value = node == null ? null : node.findValue(name);
                if (value != null && value.isTextual()) {
                    return value.asText();
                }
            }
        } catch (JsonProcessingException _) {
            // not JSON
        }
        return null;
    }

    static Map<String, String> environment(Path home) {
        var env = new LinkedHashMap<String, String>();
        System.getenv().forEach((name, value) -> {
            if (QuestionDriver.HOST_SESSION_PREFIXES.stream().noneMatch(name::startsWith)) {
                env.put(name, value);
            }
        });
        if (home != null) {
            env.put("HOME", home.toString());
        }
        return env;
    }

    static Result run(List<String> command, Path cwd, Map<String, String> env, Duration timeout)
            throws IOException, InterruptedException {
        var stdout = Files.createTempFile("agy-out", ".txt");
        var stderr = Files.createTempFile("agy-err", ".txt");
        var builder = new ProcessBuilder(command).directory(cwd.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().clear();
        builder.environment().putAll(env);
        var start = System.currentTimeMillis();
        var process = builder.start();
        var exit = -1;
        if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            exit = process.exitValue();
        } else {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        return new Result(exit, Files.readString(stdout), Files.readString(stderr),
                System.currentTimeMillis() - start);
    }

    static String tree(Path root) throws IOException {
        var text = new StringBuilder();
        try (Stream<Path> walk = Files.walk(root)) {
            for (var path : walk.sorted().toList()) {
                text.append(root.relativize(path)).append(Files.isDirectory(path) ? "/" : "").append('\n');
                if (Files.isRegularFile(path) && Files.size(path) < 20_000) {
                    text.append("---\n").append(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                            .append("\n---\n");
                }
            }
        }
        return text.toString();
    }

    static List<String> list(String... values) {
        return new ArrayList<>(List.of(values));
    }
}
