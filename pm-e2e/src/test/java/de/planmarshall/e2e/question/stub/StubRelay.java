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

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.URISyntaxException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The worker's MCP server as its harness starts it, standing in for {@code pm-mcp serve --job}: forwards
 * newline-delimited JSON-RPC between the harness ({@code stdin}/{@code stdout}) and the stub's Unix socket, after a
 * first line with the worker's identity, role, generation and harness, taken from its environment, never from the
 * model; logs every line host-side with its arrival time. It ends when either side closes, so a stopped stub ends the
 * worker's server.
 * <p>
 * Its {@code stdout} is the MCP channel of the harness, so it writes nothing else there; its own record is the relay
 * log.
 */
public final class StubRelay {

    static final String SOCKET = "PM_Q_SOCKET";
    static final String WORKER = "PM_Q_WORKER";
    static final String GENERATION = "PM_Q_GENERATION";
    static final String ROLE = "PM_Q_ROLE";
    static final String HARNESS = "PM_Q_HARNESS";
    static final String RELAY_LOG = "PM_Q_RELAY_LOG";

    private StubRelay() {
    }

    /**
     * @param socket     the stub's socket
     * @param worker     the worker
     * @param generation its generation
     * @param role       its role
     * @param harness    its harness
     * @param log        the relay's host-side log
     * @return the environment the relay reads its identity from, which the harness passes on
     */
    public static Map<String, String> environment(Path socket, String worker, int generation, String role,
            String harness, Path log) {
        var environment = new LinkedHashMap<String, String>();
        environment.put(SOCKET, socket.toString());
        environment.put(WORKER, worker);
        environment.put(GENERATION, Integer.toString(generation));
        environment.put(ROLE, role);
        environment.put(HARNESS, harness);
        environment.put(RELAY_LOG, log.toString());
        return environment;
    }

    /**
     * @return the relay command line: the JVM of this run with the test classes only (the relay needs the JDK alone)
     */
    public static List<String> command() {
        try {
            var classes = Path.of(StubRelay.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            return List.of(java, "-cp", classes.toString(), StubRelay.class.getName());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot locate the test classes", e);
        }
    }

    /**
     * @param environment the relay's environment
     * @return the identity line it writes to the stub first
     */
    static String hello(Map<String, String> environment) {
        return "{\"worker\":" + quote(environment.get(WORKER)) + ",\"generation\":"
                + Integer.parseInt(environment.get(GENERATION)) + ",\"role\":" + quote(environment.get(ROLE))
                + ",\"harness\":" + quote(environment.get(HARNESS)) + "}\n";
    }

    /**
     * @param args unused; the relay reads its environment
     * @throws IOException if the socket cannot be reached or the log not written
     */
    public static void main(String[] args) throws IOException {
        run(System.getenv(), System.in, new FileOutputStream(FileDescriptor.out), Runtime.getRuntime()::halt);
    }

    /**
     * Connects to the stub, writes the identity line, then forwards lines both ways until a side closes: the host
     * closing ends the relay with {@code 0}, the stub closing with {@code 3}.
     *
     * @param environment the relay's environment
     * @param fromHost    the harness side read
     * @param toHost      the harness side written
     * @param halt        ends the relay with a status, without returning when it is the process's own halt
     * @throws IOException if the socket cannot be reached or the log not opened
     */
    static void run(Map<String, String> environment, InputStream fromHost, OutputStream toHost, IntConsumer halt)
            throws IOException {
        var socket = Path.of(environment.get(SOCKET));
        var log = Files.newBufferedWriter(Path.of(environment.get(RELAY_LOG)), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(socket));
        var toStub = Channels.newOutputStream(channel);
        var hello = hello(environment);
        toStub.write(hello.getBytes(StandardCharsets.UTF_8));
        toStub.flush();
        logLine(log, "connect", hello.strip());
        Thread.ofPlatform().start(() -> {
            pump(Channels.newInputStream(channel), toHost, log, "to_host");
            logLine(log, "stub_closed", "");
            halt.accept(3);
        });
        pump(fromHost, toStub, log, "from_host");
        logLine(log, "host_closed", "");
        channel.close();
        halt.accept(0);
    }

    /**
     * Forwards every line from one side to the other and logs it with its direction.
     *
     * @param from      the side read
     * @param to        the side written
     * @param log       the relay log
     * @param direction the direction logged
     */
    static void pump(InputStream from, OutputStream to, Writer log, String direction) {
        try {
            var reader = new BufferedReader(new InputStreamReader(from, StandardCharsets.UTF_8));
            for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                logLine(log, direction, line);
                to.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                to.flush();
            }
        } catch (IOException e) {
            logLine(log, direction + "_failed", String.valueOf(e.getMessage()));
        }
    }

    static void logLine(Writer log, String direction, String line) {
        synchronized (log) {
            try {
                log.write("{\"at\":" + System.currentTimeMillis() + ",\"dir\":" + quote(direction) + ",\"line\":"
                        + quote(line) + "}\n");
                log.flush();
            } catch (IOException e) {
                throw new IllegalStateException("relay log not writable", e);
            }
        }
    }

    /**
     * @param value a string
     * @return it as a JSON string literal
     */
    static String quote(String value) {
        var quoted = new StringBuilder("\"");
        for (var c : value.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 0x20) {
                        quoted.append("\\u%04x".formatted((int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
