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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import de.planmarshall.e2e.question.driver.Host.Invocation;
import de.planmarshall.e2e.question.stub.QuestionStub;
import de.planmarshall.e2e.question.stub.QuestionStub.Form;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The per-host launch values: the argv of a fresh, a resumed and a model-bound launch on each host, the session id
 * read from each host's output form, the deadlines and cut limits, the placement of the MCP configuration, and the
 * faults of R3 with their placement rules. No harness is run.
 */
@DisplayName("Host: launch arguments, session ids and deadlines per host")
class HostTest {

    private static final Path CONFIG = Path.of("/w/worker/config.json");
    private static final List<String> TOOLS = List.of("mcp__plan-marshall__pm_wait", "Read");

    @Nested
    @DisplayName("Claude Code argv")
    class ClaudeArgv {

        @Test
        @DisplayName("a fresh launch keeps no session, allows the stub's tools and disallows the question tool")
        void fresh() throws IOException {
            var argv = Host.CLAUDE.argv(invocation("/bin/claude", null, null, false));

            assertEquals(List.of("/bin/claude", "-p", "do it", "--mcp-config", CONFIG.toString(),
                    "--strict-mcp-config", "--allowedTools", "mcp__plan-marshall__pm_wait,Read", "--disallowedTools",
                    "AskUserQuestion", "--output-format", "stream-json", "--verbose", "--no-session-persistence"),
                    argv);
        }

        @Test
        @DisplayName("a resumed launch keeps its session and resumes the given one")
        void resumed() throws IOException {
            var argv = Host.CLAUDE.argv(invocation("/bin/claude", null, "s-1", true));

            assertAll(
                    () -> assertFalse(argv.contains("--no-session-persistence")),
                    () -> assertEquals(List.of("--resume", "s-1"), argv.subList(argv.size() - 2, argv.size())));
        }

        @Test
        @DisplayName("a named model is passed with --model")
        void model() throws IOException {
            var argv = Host.CLAUDE.argv(invocation("/bin/claude", "sonnet", null, true));

            assertEquals(List.of("--model", "sonnet"), argv.subList(argv.size() - 2, argv.size()));
        }
    }

    @Nested
    @DisplayName("OpenCode argv")
    class OpenCodeArgv {

        @Test
        @DisplayName("a fresh launch on the default model drops --model and --session")
        void fresh() throws IOException {
            var argv = Host.OPENCODE.argv(invocation("/opt/opencode", null, null, true), null);

            assertEquals(List.of("/opt/opencode", "run", "--standalone", "--format", "json", "--agent",
                    Host.OPENCODE_AGENT, "do it"), argv);
        }

        @Test
        @DisplayName("a resumed, model-bound launch names both")
        void resumedWithModel() throws IOException {
            var argv = Host.OPENCODE.argv(invocation("/opt/opencode", "opencode/m", "ses_1", true), null);

            assertEquals(List.of("/opt/opencode", "run", "--standalone", "--format", "json", "--agent",
                    Host.OPENCODE_AGENT, "--model", "opencode/m", "--session", "ses_1", "do it"), argv);
        }

        @Test
        @DisplayName("a template from the property replaces the default")
        void template() throws IOException {
            var argv = Host.OPENCODE.argv(invocation("/opt/opencode", "m", null, true),
                    "[\"opencode\",\"run\",\"-m\",\"{model}\",\"{prompt}\"]");

            assertEquals(List.of("/opt/opencode", "run", "-m", "m", "do it"), argv);
        }
    }

    @Nested
    @DisplayName("Antigravity argv")
    class AntigravityArgv {

        @Test
        @DisplayName("a fresh launch on the default model drops --model and --conversation")
        void fresh() throws IOException {
            var argv = Host.ANTIGRAVITY.argv(invocation("/home/u/.local/bin/agy", null, null, true), null);

            assertEquals(List.of("/home/u/.local/bin/agy", "--print", "do it", "--output-format", "json",
                    "--dangerously-skip-permissions"), argv);
        }

        @Test
        @DisplayName("a resumed, model-bound launch names the model and the conversation")
        void resumedWithModel() throws IOException {
            var argv = Host.ANTIGRAVITY.argv(invocation("/bin/agy", "gemini", "c-1", true), null);

            assertEquals(List.of("/bin/agy", "--print", "do it", "--output-format", "json",
                    "--dangerously-skip-permissions", "--model", "gemini", "--conversation", "c-1"), argv);
        }

        @Test
        @DisplayName("a template may name the MCP configuration")
        void templateWithConfig() throws IOException {
            var argv = Host.ANTIGRAVITY.argv(invocation("/bin/agy", null, null, true),
                    "[\"agy\",\"--mcp\",\"{mcp_config}\",\"{prompt}\"]");

            assertEquals(List.of("/bin/agy", "--mcp", CONFIG.toString(), "do it"), argv);
        }
    }

    @Test
    @DisplayName("a placeholder without a value is dropped with the flag before it, a bare one alone")
    void expand() {
        var values = new LinkedHashMap<String, String>();
        values.put("{a}", "x");
        values.put("{b}", null);

        assertAll(
                () -> assertEquals(List.of("run", "x"), Host.expand(List.of("run", "--b", "{b}", "{a}"), values)),
                () -> assertEquals(List.of("run", "x"), Host.expand(List.of("run", "{b}", "{a}"), values)),
                () -> assertEquals(List.of("--a=x"), Host.expand(List.of("--a={a}", ""), values)));
    }

    @Nested
    @DisplayName("session id")
    class SessionId {

        @ParameterizedTest(name = "{0}")
        @DisplayName("each host reads its session id from its own output form, the last one it names")
        @CsvSource(delimiter = '|', value = {
                "CLAUDE|{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"a\"}|{\"type\":\"result\",\"session_id\":\"b\"}|b",
                "OPENCODE|{\"type\":\"step_start\",\"sessionID\":\"ses_a\"}|{\"type\":\"text\",\"part\":{\"sessionID\":\"ses_b\"}}|ses_b",
                "ANTIGRAVITY|{\"status\":\"RUNNING\"}|{\"conversation_id\":\"c-1\",\"response\":\"done\"}|c-1"})
        void perHost(Host host, String first, String second, String expected) throws IOException {
            var outputs = List.of(json(first), json(second));

            assertEquals(Optional.of(expected), host.sessionId(outputs));
        }

        @Test
        @DisplayName("another host's field, a non-textual value or no field gives no session id")
        void none() throws IOException {
            var outputs = List.of(json("{\"sessionID\":\"ses_a\"}"), json("{\"session_id\":7}"));

            assertAll(
                    () -> assertEquals(Optional.empty(), Host.CLAUDE.sessionId(outputs)),
                    () -> assertEquals(Optional.empty(), Host.ANTIGRAVITY.sessionId(outputs)),
                    () -> assertEquals(Optional.of("ses_a"), Host.OPENCODE.sessionId(outputs)));
        }
    }

    @Test
    @DisplayName("the client version comes from Claude Code's init line; the other hosts are probed")
    void version() throws IOException {
        var outputs = List.of(json("{\"type\":\"system\",\"subtype\":\"init\",\"claude_code_version\":\"2.1.0\"}"));

        assertAll(
                () -> assertEquals(Optional.of("2.1.0"), Host.CLAUDE.versionFromOutput(outputs)),
                () -> assertEquals(Optional.empty(), Host.OPENCODE.versionFromOutput(outputs)),
                () -> assertEquals(Optional.empty(), Host.CLAUDE.versionFromOutput(List.of())),
                () -> assertEquals(List.of("/bin/agy", "--version"), Host.ANTIGRAVITY.versionCommand("/bin/agy")));
    }

    @Nested
    @DisplayName("deadlines and limits")
    class Deadlines {

        @Test
        @DisplayName("Claude Code: acknowledgement within 15 s, silence limit 270 s, cut by a 60 s tool timeout")
        void claude() {
            var cut = Host.CLAUDE.cutSetting();

            assertAll(
                    () -> assertEquals(Duration.ofSeconds(15), Host.CLAUDE.ackDeadline()),
                    () -> assertFalse(Host.CLAUDE.ackInformational()),
                    () -> assertEquals(Duration.ofSeconds(240), Host.CLAUDE.boundedWait()),
                    () -> assertEquals(Duration.ofSeconds(270), Host.CLAUDE.silenceLimit()),
                    () -> assertEquals(Duration.ofSeconds(60), cut.toolTimeout()),
                    () -> assertEquals(Host.CONFIGURED_TOOL_TIMEOUT, cut.means()),
                    () -> assertEquals(Duration.ofSeconds(90), cut.hold()),
                    () -> assertEquals(Duration.ofSeconds(240), cut.window(Host.CLAUDE)));
        }

        @Test
        @DisplayName("OpenCode: an informational acknowledgement within 270 s, cut by a 60 s tool timeout")
        void opencode() {
            assertAll(
                    () -> assertEquals(Duration.ofSeconds(270), Host.OPENCODE.ackDeadline()),
                    () -> assertTrue(Host.OPENCODE.ackInformational()),
                    () -> assertEquals(Duration.ofSeconds(270), Host.OPENCODE.silenceLimit()),
                    () -> assertEquals(Host.CONFIGURED_TOOL_TIMEOUT, Host.OPENCODE.cutSetting().means()));
        }

        @Test
        @DisplayName("Antigravity: no measured deadline, so its silence limit of 180 s; cut by its 180 s call limit")
        void antigravity() {
            var cut = Host.ANTIGRAVITY.cutSetting();

            assertAll(
                    () -> assertEquals(Duration.ofSeconds(180), Host.ANTIGRAVITY.ackDeadline()),
                    () -> assertEquals(Duration.ofSeconds(150), Host.ANTIGRAVITY.boundedWait()),
                    () -> assertNull(cut.toolTimeout()),
                    () -> assertEquals(Host.HOST_CALL_LIMIT, cut.means()),
                    () -> assertEquals(Duration.ofSeconds(210), cut.hold()),
                    () -> assertEquals(Duration.ofSeconds(240), cut.window(Host.ANTIGRAVITY)),
                    () -> assertEquals(Duration.ofSeconds(241), Host.CutSetting.noProgress(Duration.ofSeconds(240))));
        }
    }

    @Test
    @DisplayName("the MCP configuration goes where each host reads it")
    void mcpConfig() {
        var directory = Path.of("/w/worker");

        assertAll(
                () -> assertEquals(directory.resolve("mcp-config.json"), Host.CLAUDE.mcpConfig(directory)),
                () -> assertEquals(directory.resolve("opencode.json"), Host.OPENCODE.mcpConfig(directory)),
                () -> assertEquals(directory.resolve(Host.AGY_CONFIG), Host.ANTIGRAVITY.mcpConfig(directory)));
    }

    @Test
    @DisplayName("a host is named by its executable or its name, in any case")
    void of() {
        assertAll(
                () -> assertEquals(Host.ANTIGRAVITY, Host.of("agy")),
                () -> assertEquals(Host.ANTIGRAVITY, Host.of(" Antigravity ")),
                () -> assertEquals(Host.OPENCODE, Host.of("OPENCODE")),
                () -> assertThrows(IllegalArgumentException.class, () -> Host.of("codex")));
    }

    @Nested
    @DisplayName("faults of R3")
    class Faults {

        @Test
        @DisplayName("without the property every fault is selected, in the order of the enum")
        void all() {
            assertEquals(List.of(Fault.values()), Fault.select(null));
        }

        @Test
        @DisplayName("the named faults are selected once each, in the order of the enum, in any case")
        void named() {
            assertEquals(List.of(Fault.CALL_CUT, Fault.KILL), Fault.select(" kill,Call_Cut,,KILL "));
        }

        @Test
        @DisplayName("an unknown name or a list that names none is refused with the valid names")
        void refused() {
            var unknown = assertThrows(IllegalArgumentException.class, () -> Fault.select("kill,hang"));

            assertAll(
                    () -> assertTrue(unknown.getMessage().contains("STOP_BEFORE_ANSWER"), unknown.getMessage()),
                    () -> assertThrows(IllegalArgumentException.class, () -> Fault.select(" , ")));
        }

        @Test
        @DisplayName("a resumed exit continues by resume, every other fault by hold; a call cut holds past the cut")
        void placement() {
            assertAll(
                    () -> assertEquals(Form.RESUME, Fault.RESUMED_EXIT.form()),
                    () -> assertEquals(Form.HOLD, Fault.KILL.form()),
                    () -> assertEquals(Fault.HOLD, Fault.KILL.hold(Host.CLAUDE)),
                    () -> assertEquals(Duration.ofSeconds(90), Fault.CALL_CUT.hold(Host.CLAUDE)),
                    () -> assertEquals(Duration.ofSeconds(210), Fault.CALL_CUT.hold(Host.ANTIGRAVITY)),
                    () -> assertTrue(Fault.STOP_BEFORE_ANSWER.stopsTree() && Fault.MODEL_HANG.stopsTree()),
                    () -> assertTrue(Fault.KILL.killsTree() && Fault.RESUMED_EXIT.killsTree()),
                    () -> assertFalse(Fault.CALL_CUT.stopsTree() || Fault.CALL_CUT.killsTree()));
        }
    }

    private static Invocation invocation(String executable, String model, String sessionId, boolean resumable) {
        return new Invocation(executable, "do it", model, sessionId, resumable, CONFIG, TOOLS);
    }

    private static JsonNode json(String text) throws IOException {
        return QuestionStub.JSON.readTree(text);
    }

    @Test
    @DisplayName("the version probe of a missing executable gives unknown")
    void probeMissing() {
        assertEquals("unknown", QuestionDriver.probeVersion(List.of("/nonexistent/claude", "--version")));
    }

    @Test
    @DisplayName("the version probe takes the first line the executable prints")
    void probe() {
        assertEquals("hello", QuestionDriver.probeVersion(List.of("printf", "\\nhello\\nworld\\n")));
    }
}
