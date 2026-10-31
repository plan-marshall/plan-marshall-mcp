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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("StubRelay: the worker's MCP server between the harness and the stub")
class StubRelayTest {

    @Test
    @DisplayName("passes the worker's identity in its environment and writes it as the first line")
    void identity() {
        var environment = StubRelay.environment(Path.of("/tmp/s.sock"), "w1", 2, "implement", "claude",
                Path.of("/tmp/relay.jsonl"));

        assertAll(() -> assertEquals("/tmp/s.sock", environment.get(StubRelay.SOCKET)),
                () -> assertEquals("2", environment.get(StubRelay.GENERATION)),
                () -> assertEquals("/tmp/relay.jsonl", environment.get(StubRelay.RELAY_LOG)),
                () -> assertEquals("{\"worker\":\"w1\",\"generation\":2,\"role\":\"implement\",\"harness\":\"claude\"}"
                        + "\n", StubRelay.hello(environment)));
    }

    @Test
    @DisplayName("runs itself in the JVM of the run with the test classes")
    void command() {
        var command = StubRelay.command();

        assertAll(() -> assertEquals(4, command.size()),
                () -> assertTrue(command.getFirst().endsWith("java")),
                () -> assertEquals(StubRelay.class.getName(), command.getLast()));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"plain|\"plain\"", "a\"b|\"a\\\"b\"", "a\\b|\"a\\\\b\""})
    @DisplayName("quotes a string as a JSON literal")
    void quotes(String value, String expected) {
        assertEquals(expected, StubRelay.quote(value));
    }

    @Test
    @DisplayName("escapes control characters")
    void escapesControlCharacters() {
        assertEquals("\"\\n\\r\\t\\u0001\"", StubRelay.quote("\n\r\t\u0001"));
    }

    @Test
    @DisplayName("forwards every line and logs it with its direction")
    void pumps() {
        var from = new ByteArrayInputStream("one\ntwo\n".getBytes(StandardCharsets.UTF_8));
        var to = new ByteArrayOutputStream();
        var log = new StringWriter();

        StubRelay.pump(from, to, log, "from_host");

        assertAll(() -> assertEquals("one\ntwo\n", to.toString(StandardCharsets.UTF_8)),
                () -> assertEquals(2, log.toString().lines().count()),
                () -> assertTrue(log.toString().contains("\"dir\":\"from_host\",\"line\":\"two\"")));
    }

    @Test
    @DisplayName("logs a side that fails instead of throwing")
    void logsFailure() {
        var failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("gone");
            }
        };
        var log = new StringWriter();

        StubRelay.pump(failing, new ByteArrayOutputStream(), log, "to_host");

        assertTrue(log.toString().contains("\"dir\":\"to_host_failed\",\"line\":\"gone\""));
    }

    @Test
    @DisplayName("fails loudly when its log cannot be written")
    void unwritableLog() {
        var log = new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) throws IOException {
                throw new IOException("full");
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to close
            }
        };

        assertThrows(IllegalStateException.class, () -> StubRelay.logLine(log, "connect", "x"));
    }
}
