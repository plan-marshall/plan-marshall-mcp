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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("StubRelay: the worker's MCP server between the harness and the stub")
class StubRelayTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * @param prefix the name's prefix
     * @return a socket path short enough for {@code sun_path}, which a temporary directory may not be
     */
    private static Path shortSocket(String prefix) {
        var bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return Path.of("/tmp", prefix + HexFormat.of().formatHex(bytes) + ".sock");
    }

    /**
     * The relay as its harness runs it, against a stub socket of the test: the harness side is a pair of pipes, and
     * the halt records its status instead of ending the JVM.
     */
    @Nested
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("run")
    class Run {

        @TempDir
        Path directory;

        private final BlockingQueue<Integer> halts = new LinkedBlockingQueue<>();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final PipedOutputStream host = new PipedOutputStream();
        private final PipedInputStream atHost = new PipedInputStream();
        private Path socket;
        private Path log;
        private ServerSocketChannel server;
        private Map<String, String> environment;

        @BeforeEach
        void listen() throws IOException {
            socket = shortSocket("pmq-relay-");
            log = directory.resolve("relay.jsonl");
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            environment = StubRelay.environment(socket, "w1", 2, "implement", "claude", log);
        }

        @AfterEach
        void close() throws IOException, InterruptedException {
            host.close();
            server.close();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            Files.deleteIfExists(socket);
        }

        private Future<Void> start() throws IOException {
            var fromHost = new PipedInputStream(host);
            var toHost = new PipedOutputStream(atHost);
            return executor.submit(() -> {
                StubRelay.run(environment, fromHost, toHost, halts::add);
                return null;
            });
        }

        private List<Integer> awaitHalts(int count) throws InterruptedException {
            var statuses = new ArrayList<Integer>();
            while (statuses.size() < count) {
                var status = halts.poll(10, TimeUnit.SECONDS);
                assertNotNull(status, "the relay did not halt");
                statuses.add(status);
            }
            return statuses;
        }

        @Test
        @DisplayName("writes the identity first, forwards lines both ways and ends with 0 when the host closes")
        void shouldForwardUntilHostCloses() throws Exception {
            var relay = start();
            String hello;
            String toHost;
            String toStub;
            try (var stub = server.accept()) {
                var fromRelay = new BufferedReader(new InputStreamReader(Channels.newInputStream(stub),
                        StandardCharsets.UTF_8));
                hello = fromRelay.readLine();
                Channels.newOutputStream(stub).write("pong\n".getBytes(StandardCharsets.UTF_8));
                toHost = new BufferedReader(new InputStreamReader(atHost, StandardCharsets.UTF_8)).readLine();
                host.write("ping\n".getBytes(StandardCharsets.UTF_8));
                host.flush();
                toStub = fromRelay.readLine();
                host.close();
                relay.get(10, TimeUnit.SECONDS);
            }
            var statuses = awaitHalts(2);
            var written = Files.readString(log);

            assertAll(() -> assertEquals(StubRelay.hello(environment).strip(), hello),
                    () -> assertEquals("pong", toHost),
                    () -> assertEquals("ping", toStub),
                    () -> assertTrue(statuses.contains(0), statuses::toString),
                    () -> assertTrue(written.contains("\"dir\":\"connect\""), written),
                    () -> assertTrue(written.contains("\"dir\":\"to_host\",\"line\":\"pong\""), written),
                    () -> assertTrue(written.contains("\"dir\":\"from_host\",\"line\":\"ping\""), written),
                    () -> assertTrue(written.contains("\"dir\":\"host_closed\""), written));
        }

        @Test
        @DisplayName("ends with 3 when the stub closes, before the host has closed")
        void shouldEndWhenStubCloses() throws Exception {
            var relay = start();
            try (var stub = server.accept()) {
                new BufferedReader(new InputStreamReader(Channels.newInputStream(stub), StandardCharsets.UTF_8))
                        .readLine();
            }
            var first = awaitHalts(1);
            var stubClosed = Files.readString(log);
            host.close();
            relay.get(10, TimeUnit.SECONDS);
            var second = awaitHalts(1);

            assertAll(() -> assertEquals(List.of(3), first),
                    () -> assertTrue(stubClosed.contains("\"dir\":\"stub_closed\""), stubClosed),
                    () -> assertEquals(List.of(0), second));
        }

        @Test
        @DisplayName("fails when the stub's socket cannot be reached, without halting")
        void shouldFailWithoutStub() {
            var unreachable = StubRelay.environment(shortSocket("pmq-none-"), "w1", 2, "implement", "claude", log);

            assertThrows(IOException.class, () -> StubRelay.run(unreachable, InputStream.nullInputStream(),
                    OutputStream.nullOutputStream(), halts::add));
            assertTrue(halts.isEmpty());
        }
    }

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
