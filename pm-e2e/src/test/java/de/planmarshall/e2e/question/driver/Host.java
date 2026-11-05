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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import de.planmarshall.e2e.question.stub.QuestionStub;

/**
 * The hosts of the measurement with the per-host values of their harness profiles (PM-CLIENT-2): the launch arguments
 * of a fresh, a resumed and a model-bound launch, the field of the structured output that carries the session id, the
 * deadline of the answer acknowledgement or the silence limit, the limit at which the host cuts a silent call, and
 * where the stub's MCP configuration is placed below the worker directory, each as the branch
 * {@code verification/question-loop-experiment} verified it. OpenCode and Antigravity are kept maintained beside
 * Claude Code for the other hosts' measurement.
 */
public enum Host {

    /** Claude Code. */
    CLAUDE("claude", "AskUserQuestion", 15, false, 240, 30, "session_id",
        List.of(".claude", ".claude.json", ".cache/claude", ".local/state/claude", ".local/share/claude",
                ".config/claude")),
    /**
     * OpenCode 2.x, on the data home {@link QuestionDriver.OpenCodeDataHome} selects. A {@code pm_wait} held by the
     * stub for 150 s returned uncut, and one held for 330 s was still uncut after about 260 s, when the probe itself was
     * ended (verified 2026-10-21); what OpenCode does with a call held beyond that is not verified.
     */
    OPENCODE("opencode", "question", 270, true, 240, 30, "sessionID",
            List.of(".local/state/opencode", ".cache/opencode", ".config/opencode")),
    /**
     * Antigravity, a smoke run: it is not worker-eligible, and it runs with all host tools. A {@code pm_wait} held by
     * the stub for the bounded wait of 150 s returned uncut (verified 2026-10-21).
     */
    ANTIGRAVITY("agy", "ask_question", 0, false, 150, 30, "conversation_id", List.of(".gemini", ".antigravity"));

    /**
     * The verified headless launch of Antigravity ({@code agy}, verified 2026-10-21 against the stub): the placeholders
     * {@code {prompt}}, {@code {model}} and {@code {session_id}} are replaced, and a placeholder without a value is
     * dropped together with the flag before it. {@value #AGY_ARGV_PROPERTY} (a JSON array, which may also name
     * {@code {mcp_config}}) overrides it.
     */
    public static final List<String> AGY_ARGV = List.of("agy", "--print", "{prompt}", "--output-format", "json",
            "--dangerously-skip-permissions", "--model", "{model}", "--conversation", "{session_id}");

    /** The property that overrides {@link #AGY_ARGV}. */
    public static final String AGY_ARGV_PROPERTY = "pm.experiment.agy.argv";

    /**
     * Where Antigravity reads the stub's MCP server, below the worker directory: a workspace plugin
     * ({@code .agents/plugins/<name>/} with {@code plugin.json} and {@code mcp_config.json}), verified 2026-10-21; its
     * name differs from the operator's globally installed {@code plan-marshall} plugin. {@value #AGY_CONFIG_PROPERTY}
     * overrides it.
     */
    public static final String AGY_CONFIG = ".agents/plugins/pm-question-stub/mcp_config.json";

    /** The property that overrides {@link #AGY_CONFIG}. */
    public static final String AGY_CONFIG_PROPERTY = "pm.experiment.agy.config";

    /** The agent of {@code opencode.json} that the worker runs as: the stub's tools and the work tools only. */
    public static final String OPENCODE_AGENT = "pull-worker";

    /**
     * The headless launch of OpenCode 2.1.2, verified 2026-10-21 against the stub: the placeholders {@code {prompt}},
     * {@code {model}} and {@code {session_id}} are replaced, and a placeholder without a value is dropped together with
     * the flag before it. {@code --standalone} runs a private server ({@code opencode serve --stdio}) that honours the
     * worker's {@code XDG_DATA_HOME}; without it the run goes to the operator's background service, which keeps the
     * session in its own data directory. No {@code --auto}: the agent's permissions allow what the worker needs.
     * {@value #OPENCODE_ARGV_PROPERTY} (a JSON array) overrides it.
     */
    public static final List<String> OPENCODE_ARGV = List.of("opencode", "run", "--standalone", "--format", "json",
            "--agent", OPENCODE_AGENT, "--model", "{model}", "--session", "{session_id}", "{prompt}");

    /** The property that overrides {@link #OPENCODE_ARGV}. */
    public static final String OPENCODE_ARGV_PROPERTY = "pm.experiment.opencode.argv";

    /**
     * The prefix of the property that names a host's executable by its path ({@code pm.experiment.exe.claude},
     * {@code pm.experiment.exe.opencode}, {@code pm.experiment.exe.agy}); {@code pm-exec} runs a program by its
     * absolute path only.
     */
    public static final String EXECUTABLE_PROPERTY = "pm.experiment.exe.";

    /**
     * The margin by which the operator's hold of a {@link Fault#CALL_CUT} run outlasts the host's cut, and the stub's
     * window outlasts that hold.
     */
    public static final Duration CALL_CUT_MARGIN = Duration.ofSeconds(30);

    /** The tool timeout configured for a {@link Fault#CALL_CUT} run on Claude Code and OpenCode. */
    public static final Duration CALL_CUT_TOOL_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Antigravity's limit on every tool call, progress or not (evaluation, Part A: "Antigravity ends every call after
     * 180 s, progress has no effect"); it has no tool timeout to configure.
     */
    public static final Duration ANTIGRAVITY_CALL_LIMIT = Duration.ofSeconds(180);

    /** {@link CutSetting#means()} where a tool timeout is configured below the hold. */
    public static final String CONFIGURED_TOOL_TIMEOUT = "configured_tool_timeout";

    /** {@link CutSetting#means()} where the host's own fixed call limit cuts the held call. */
    public static final String HOST_CALL_LIMIT = "host_call_limit";

    /**
     * The field of Claude Code's {@code system} {@code init} line of {@code --output-format stream-json} that names the
     * client version; the other hosts' output names none, and their version comes from {@link #versionCommand(String)}.
     */
    static final String CLAUDE_VERSION_FIELD = "claude_code_version";

    private final String executable;
    private final String questionTool;
    private final int ackDeadlineSeconds;
    private final boolean ackInformational;
    private final int boundedWaitSeconds;
    private final int silenceGraceSeconds;
    private final String sessionField;
    private final List<String> stateDirs;

    Host(String executable, String questionTool, int ackDeadlineSeconds, boolean ackInformational,
            int boundedWaitSeconds, int silenceGraceSeconds, String sessionField, List<String> stateDirs) {
        this.executable = executable;
        this.questionTool = questionTool;
        this.ackDeadlineSeconds = ackDeadlineSeconds;
        this.ackInformational = ackInformational;
        this.boundedWaitSeconds = boundedWaitSeconds;
        this.silenceGraceSeconds = silenceGraceSeconds;
        this.sessionField = sessionField;
        this.stateDirs = stateDirs;
    }

    /**
     * How a {@link Fault#CALL_CUT} run makes the host cut its held {@code pm_wait}: the held call gets no progress
     * frame (Claude Code and OpenCode reset their timeout on progress; the stub otherwise sends a frame every 5 s), and
     * the operator answers {@link #CALL_CUT_MARGIN} after the host's cut, so the cut comes while the call is held.
     *
     * @param toolTimeout the tool timeout configured at the launch ({@code MCP_TOOL_TIMEOUT} for Claude Code, the MCP
     *                    server's {@code timeout} in {@code opencode.json} for OpenCode), {@code null} where the host
     *                    has none to configure (Antigravity)
     * @param hostCut     when the host is expected to cut a silent held call
     * @param means       {@value #CONFIGURED_TOOL_TIMEOUT} or {@value #HOST_CALL_LIMIT}
     */
    public record CutSetting(Duration toolTimeout, Duration hostCut, String means) {

        /** @return the operator's hold: the host's cut plus {@link #CALL_CUT_MARGIN} */
        public Duration hold() {
            return hostCut.plus(CALL_CUT_MARGIN);
        }

        /**
         * @param host the host
         * @return the stub's bounded wait for the run: the host's own, or the hold plus {@link #CALL_CUT_MARGIN} where
         *         that is longer, so the stub does not end the held call before the host can cut it
         */
        public Duration window(Host host) {
            var needed = hold().plus(CALL_CUT_MARGIN);
            return needed.compareTo(host.boundedWait()) > 0 ? needed : host.boundedWait();
        }

        /**
         * @param window the stub's bounded wait
         * @return the stub's progress interval for the run: beyond the window, so a held call gets no progress frame
         */
        public static Duration noProgress(Duration window) {
            return window.plus(Duration.ofSeconds(1));
        }
    }

    /**
     * One launch of the host's harness, as {@link #argv(Invocation)} composes it.
     *
     * @param executable   the absolute path of the harness's executable
     * @param prompt       the launch prompt
     * @param model        the model, {@code null} for the harness default
     * @param sessionId    the session to resume, {@code null} for a new session
     * @param resumable    whether the session is kept (Claude Code omits {@code --no-session-persistence})
     * @param mcpConfig    the stub's MCP configuration written for the launch ({@link #mcpConfig(Path)})
     * @param allowedTools the tools the worker may use, by the host's names (Claude Code's {@code --allowedTools})
     */
    public record Invocation(String executable, String prompt, String model, String sessionId, boolean resumable,
    Path mcpConfig, List<String> allowedTools) {
    }

    /** @return the executable name */
    public String executable() {
        return executable;
    }

    /** @return the name of the host's own question tool */
    public String questionTool() {
        return questionTool;
    }

    /**
     * @return the deadline of the answer acknowledgement: the profile's {@code ack_deadline_seconds}, for a host
     *         without a measured value its silence limit
     */
    public Duration ackDeadline() {
        return ackDeadlineSeconds > 0 ? Duration.ofSeconds(ackDeadlineSeconds) : silenceLimit();
    }

    /** @return whether the acknowledgement is informational, silence alone deciding */
    public boolean ackInformational() {
        return ackInformational;
    }

    /** @return the bounded wait */
    public Duration boundedWait() {
        return Duration.ofSeconds(boundedWaitSeconds);
    }

    /** @return the silence limit: bounded wait plus silence grace */
    public Duration silenceLimit() {
        return Duration.ofSeconds((long) boundedWaitSeconds + silenceGraceSeconds);
    }

    /**
     * @return the field of the harness's structured result that carries its session id: {@code session_id} (Claude
     *         Code), {@code sessionID} (OpenCode), {@code conversation_id} (Antigravity, verified 2026-10-21)
     */
    public String sessionField() {
        return sessionField;
    }

    /** @return the directories below the home directory the harness keeps its state in */
    List<String> stateDirs() {
        return stateDirs;
    }

    /**
     * How the host is made to cut a held call ({@link Fault#CALL_CUT}). Claude Code: {@code MCP_TOOL_TIMEOUT} of
     * {@link #CALL_CUT_TOOL_TIMEOUT}; Part A measured only its default (a silent call ended after 1800 s), so whether the
     * variable cuts a held call is what the runs show. OpenCode: the MCP server's {@code timeout} of
     * {@link #CALL_CUT_TOOL_TIMEOUT} in {@code opencode.json}, equal to the 60 s after which Part A saw it end a silent
     * call. Antigravity: no tool timeout to configure; its fixed limit of {@link #ANTIGRAVITY_CALL_LIMIT} on every
     * call, which the stub's window is raised above.
     *
     * @return the host's cut setting
     */
    public CutSetting cutSetting() {
        return switch (this) {
            case CLAUDE, OPENCODE -> new CutSetting(CALL_CUT_TOOL_TIMEOUT, CALL_CUT_TOOL_TIMEOUT,
                    CONFIGURED_TOOL_TIMEOUT);
            case ANTIGRAVITY -> new CutSetting(null, ANTIGRAVITY_CALL_LIMIT, HOST_CALL_LIMIT);
        };
    }

    /**
     * Where the stub's MCP configuration of a launch is placed: {@code mcp-config.json} in the worker directory for
     * Claude Code ({@code --mcp-config}), {@code opencode.json} there for OpenCode, which reads it from the project
     * directory, and {@link #AGY_CONFIG} (or {@value #AGY_CONFIG_PROPERTY}) below it for Antigravity.
     *
     * @param directory the worker directory
     * @return the configuration file
     */
    public Path mcpConfig(Path directory) {
        return switch (this) {
            case CLAUDE -> directory.resolve("mcp-config.json");
            case OPENCODE -> directory.resolve("opencode.json");
            case ANTIGRAVITY -> directory.resolve(System.getProperty(AGY_CONFIG_PROPERTY, AGY_CONFIG));
        };
    }

    /**
     * The command line of a launch, the template of a host that has one taken from its property when set
     * ({@value #OPENCODE_ARGV_PROPERTY}, {@value #AGY_ARGV_PROPERTY}).
     *
     * @param invocation the launch
     * @return the command line, the executable by its absolute path first
     * @throws JsonProcessingException if the property is no JSON array
     */
    public List<String> argv(Invocation invocation) throws JsonProcessingException {
        return switch (this) {
            case CLAUDE -> argv(invocation, null);
            case OPENCODE -> argv(invocation, System.getProperty(OPENCODE_ARGV_PROPERTY));
            case ANTIGRAVITY -> argv(invocation, System.getProperty(AGY_ARGV_PROPERTY));
        };
    }

    /**
     * The command line of a launch. Claude Code: {@code -p}, the MCP configuration with {@code --strict-mcp-config},
     * the allowed tools, its question tool disallowed, {@code stream-json} output, {@code --no-session-persistence}
     * unless the session is kept, {@code --resume} with the session to resume and {@code --model} with a named model.
     * OpenCode and Antigravity: their template ({@link #OPENCODE_ARGV}, {@link #AGY_ARGV}) with its placeholders
     * replaced ({@link #expand(List, Map)}), the executable's name replaced by its absolute path.
     *
     * @param invocation the launch
     * @param template   the template as a JSON array, {@code null} for the host's own; ignored for Claude Code
     * @return the command line
     * @throws JsonProcessingException if the template is no JSON array
     */
    List<String> argv(Invocation invocation, String template) throws JsonProcessingException {
        if (this == CLAUDE) {
            var command = new ArrayList<>(List.of(invocation.executable(), "-p", invocation.prompt(), "--mcp-config",
                    invocation.mcpConfig().toString(), "--strict-mcp-config", "--allowedTools",
                    String.join(",", invocation.allowedTools()), "--disallowedTools", questionTool,
                    "--output-format", "stream-json", "--verbose"));
            if (!invocation.resumable()) {
                command.add("--no-session-persistence");
            }
            if (invocation.sessionId() != null) {
                command.addAll(List.of("--resume", invocation.sessionId()));
            }
            if (invocation.model() != null) {
                command.addAll(List.of("--model", invocation.model()));
            }
            return command;
        }
        var argv = new ArrayList<String>();
        if (template == null) {
            argv.addAll(this == OPENCODE ? OPENCODE_ARGV : AGY_ARGV);
        } else {
            QuestionStub.JSON.readTree(template).forEach(item -> argv.add(item.asText()));
        }
        var values = new LinkedHashMap<String, String>();
        values.put("{prompt}", invocation.prompt());
        values.put("{model}", invocation.model());
        values.put("{session_id}", invocation.sessionId());
        values.put("{mcp_config}", invocation.mcpConfig().toString());
        var command = expand(argv, values);
        if (!command.isEmpty() && executable.equals(command.getFirst())) {
            command.set(0, invocation.executable());
        }
        return command;
    }

    /**
     * Replaces the placeholders of a launch template; a placeholder without a value is dropped together with the flag
     * before it.
     *
     * @param argv   the template
     * @param values the value per placeholder, {@code null} for none
     * @return the command
     */
    static List<String> expand(List<String> argv, Map<String, String> values) {
        var command = new ArrayList<String>();
        for (var item : argv) {
            var value = item;
            var missing = false;
            for (var entry : values.entrySet()) {
                if (value.contains(entry.getKey())) {
                    if (entry.getValue() == null) {
                        missing = true;
                    } else {
                        value = value.replace(entry.getKey(), entry.getValue());
                    }
                }
            }
            if (missing) {
                if (!command.isEmpty() && command.getLast().startsWith("-")) {
                    command.removeLast();
                }
            } else if (!value.isEmpty()) {
                command.add(value);
            }
        }
        return command;
    }

    /**
     * @param outputs the harness's structured output lines, in order
     * @return the last session id they name in {@link #sessionField()}, empty when none
     */
    public Optional<String> sessionId(List<JsonNode> outputs) {
        String found = null;
        for (var line : outputs) {
            var value = line.findValue(sessionField);
            if (value != null && value.isTextual()) {
                found = value.asText();
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * @param outputs the harness's structured output lines, in order
     * @return the client version the output names ({@value #CLAUDE_VERSION_FIELD} on Claude Code), empty when it names
     *         none, as OpenCode's and Antigravity's output do
     */
    public Optional<String> versionFromOutput(List<JsonNode> outputs) {
        if (this != CLAUDE) {
            return Optional.empty();
        }
        return outputs.stream().map(line -> line.path(CLAUDE_VERSION_FIELD)).filter(JsonNode::isTextual)
                .map(JsonNode::asText).findFirst();
    }

    /**
     * @param executablePath the absolute path of the harness's executable
     * @return the version probe: the executable with {@code --version}
     */
    public List<String> versionCommand(String executablePath) {
        return List.of(executablePath, "--version");
    }

    /**
     * The executable of the host by its absolute path, as {@code pm-exec} requires it: {@code -Dpm.experiment.exe.<host>},
     * else the first match on the worker's {@code PATH}, else the host's known location ({@link #knownLocations()}).
     *
     * @param environment the worker's environment, whose {@code PATH} is searched
     * @return the absolute path
     * @throws IllegalStateException if the property names no executable file, or none is found
     */
    public String resolveExecutable(Map<String, String> environment) {
        return resolveExecutable(System.getProperty(EXECUTABLE_PROPERTY + executable),
                environment.getOrDefault("PATH", ""), knownLocations());
    }

    /**
     * @param override       the path the property names, {@code null} for none
     * @param searchPath     the {@code PATH} to search
     * @param knownLocations the directories searched after it
     * @return the absolute path of the host's executable
     * @throws IllegalStateException if the override names no executable file, or none is found
     */
    String resolveExecutable(String override, String searchPath, List<Path> knownLocations) {
        var property = "-D" + EXECUTABLE_PROPERTY + executable;
        if (override != null) {
            var path = Path.of(override).toAbsolutePath();
            if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
                throw new IllegalStateException(property + " names no executable file: " + path);
            }
            return path.toString();
        }
        var directories = new ArrayList<Path>();
        for (var entry : searchPath.split(File.pathSeparator)) {
            if (!entry.isEmpty()) {
                directories.add(Path.of(entry));
            }
        }
        directories.addAll(knownLocations);
        for (var directory : directories) {
            var candidate = directory.resolve(executable).toAbsolutePath();
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        throw new IllegalStateException(executable + " not found on the PATH or in " + knownLocations + ": set "
                + property + " to its absolute path");
    }

    /**
     * @return where the host installs itself: {@code ~/.local/bin} for Claude Code and Antigravity,
     *         {@code /opt/homebrew/bin} for OpenCode
     */
    List<Path> knownLocations() {
        return switch (this) {
            case CLAUDE, ANTIGRAVITY -> List.of(Path.of(System.getProperty("user.home"), ".local", "bin"));
            case OPENCODE -> List.of(Path.of("/opt/homebrew/bin"));
        };
    }

    /**
     * @param name a host name ({@code claude}, {@code opencode}, {@code antigravity} or {@code agy})
     * @return the host
     * @throws IllegalArgumentException for any other name
     */
    public static Host of(String name) {
        var key = name.strip().toLowerCase(Locale.ROOT);
        for (var host : values()) {
            if (host.executable.equals(key) || host.name().toLowerCase(Locale.ROOT).equals(key)) {
                return host;
            }
        }
        throw new IllegalArgumentException("unknown host " + name);
    }
}
