/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;


import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import de.planmarshall.mcp.server.runtime.RuntimeContext;
import de.planmarshall.runtime.test.TestBases;
import de.planmarshall.runtime.web.WebListenerConflictException;
import de.planmarshall.runtime.web.WebTls;
import de.planmarshall.runtime.web.WebTlsFixture;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnableTestLogger
@DisplayName("Web listener without the runtime around it")
class WebListenerBehaviourTest {

    private static final int DEADLINE_MILLIS = 10_000;
    /** An address of the documentation prefix: the certificate names it, no interface needs to hold it. */
    private static final String IPV6_ADDRESS = "2001:db8::1";

    private Vertx vertx;
    private Path base;
    private WebTls tls;

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx(new VertxOptions().setPreferNativeTransport(true).setEventLoopPoolSize(1));
        base = TestBases.create("pmb");
        tls = WebTlsFixture.write(base.resolve("web").resolve("tls"), List.of(InetAddress.getByName(IPV6_ADDRESS)));
    }

    @AfterEach
    void stop() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
        TestBases.delete(base);
    }

    private WebListener listener(WebListener.PortCandidates candidates, Duration wait) {
        return new WebListener(vertx, new RuntimeContext(Optional.of(base.toString()), "test"), candidates, wait);
    }

    private WebListener listener() {
        return listener(WebListener::kernelCandidate, Duration.ofMillis(DEADLINE_MILLIS));
    }

    /** One TLS request to the listener on loopback; the answer's status line. */
    private String statusOverTls(int port, String request) throws Exception {
        var trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("pm-mcpd", tls.certificate());
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        var context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        try (var socket = (SSLSocket) context.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), DEADLINE_MILLIS);
            socket.setSoTimeout(DEADLINE_MILLIS);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var answer = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            return answer.lines().findFirst().orElse("");
        }
    }

    private static boolean binds(int port) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException _) {
            return false;
        }
    }

    @Nested
    @DisplayName("Host and Origin validation in LAN mode")
    class HostValidation {

        @ParameterizedTest(name = "Host: {0}")
        @ValueSource(strings = {"[2001:db8::1]", "[2001:DB8::1]", "[2001:db8:0:0:0:0:0:1]",
                "[2001:0db8:0000:0000:0000:0000:0000:0001]", "[::1]", "127.0.0.1", "localhost", "fixture.local"})
        @DisplayName("accepts every form of a name or address the certificate holds")
        void shouldAcceptEveryFormOfACertificateAddress(String host) throws Exception {
            var listener = listener();
            var port = listener.apply(true, true, 0, "test").port();
            try {
                var status = statusOverTls(port, "GET /page HTTP/1.1\r\nHost: " + host + ":" + port
                        + "\r\nConnection: close\r\n\r\n");

                assertEquals("HTTP/1.1 200 OK", status);
            } finally {
                listener.apply(false, true, 0, "test");
            }
        }

        @ParameterizedTest(name = "Host: {0}")
        @ValueSource(strings = {"[2001:db8::2]", "[2001:db8::1%en0]", "[2001:db8::1", "2001:db8::1", "[]",
                "evil.example", "[::ffff:127.0.0.1]"})
        @DisplayName("refuses an address the certificate does not hold, and a malformed one")
        void shouldRefuseOtherAddresses(String host) throws Exception {
            var listener = listener();
            var port = listener.apply(true, true, 0, "test").port();
            try {
                var status = statusOverTls(port, "GET /page HTTP/1.1\r\nHost: " + host + ":" + port
                        + "\r\nConnection: close\r\n\r\n");

                assertEquals("HTTP/1.1 403 Forbidden", status);
            } finally {
                listener.apply(false, true, 0, "test");
            }
        }

        @Test
        @DisplayName("accepts the listener's own origin in the form a browser sends for an IPv6 address")
        void shouldCompareTheOriginByAddress() throws Exception {
            var listener = listener();
            var port = listener.apply(true, true, 0, "test").port();
            try {
                var own = statusOverTls(port, "DELETE /page HTTP/1.1\r\nHost: [2001:db8::1]:" + port
                        + "\r\nOrigin: https://[2001:db8::1]:" + port + "\r\nConnection: close\r\n\r\n");
                var foreign = statusOverTls(port, "DELETE /page HTTP/1.1\r\nHost: [2001:db8::1]:" + port
                        + "\r\nOrigin: https://[2001:db8::2]:" + port + "\r\nConnection: close\r\n\r\n");

                assertEquals("HTTP/1.1 404 Not Found", own);
                assertEquals("HTTP/1.1 403 Forbidden", foreign);
            } finally {
                listener.apply(false, true, 0, "test");
            }
        }
    }

    @Nested
    @DisplayName("Log of the listener's life")
    class Logging {

        @Test
        @DisplayName("logs every open request and the close with their cause")
        void shouldLogOpenAndCloseWithCause() throws Exception {
            var listener = listener();

            var port = listener.apply(true, false, 0, "the first request").port();
            listener.apply(true, false, port, "the second request");
            listener.apply(false, false, port, "the closing request");

            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                    "PM_RUNTIME-7: Web listener open requested on 127.0.0.1:0 (lan: false), cause: the first request");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                    "PM_RUNTIME-7: Web listener open requested on 127.0.0.1:" + port
                            + " (lan: false), cause: the second request");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                    "PM_RUNTIME-8: Web listener on port " + port + " closing, cause: the closing request");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "PM_RUNTIME-4");
        }

        @Test
        @DisplayName("warns about a connection that fails its TLS handshake")
        void shouldWarnAboutAFailedHandshake() throws Exception {
            var listener = listener();
            var port = listener.apply(true, true, 0, "test").port();
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), DEADLINE_MILLIS);
                socket.setSoTimeout(DEADLINE_MILLIS);
                socket.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                assertEquals(-1, socket.getInputStream().read(), "the listener ends a connection that is no TLS");
            } finally {
                listener.apply(false, true, 0, "test");
            }

            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, "PM_RUNTIME-103: Web listener on port " + port
                    + ": connection failed before a request (TLS handshake or connection setup): "
                    + "NotSslRecordException");
        }
    }

    @Nested
    @DisplayName("A bind that does not answer within its wait")
    class BindWait {

        /** Keeps the only event loop busy, so a bind cannot complete, until the returned latch is counted down. */
        private CountDownLatch blockEventLoop() throws InterruptedException {
            var release = new CountDownLatch(1);
            var blocked = new CountDownLatch(1);
            vertx.runOnContext(_ -> {
                blocked.countDown();
                try {
                    release.await(DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blocked.await(DEADLINE_MILLIS, TimeUnit.MILLISECONDS));
            return release;
        }

        private static void assertEventuallyFree(int port) {
            var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS);
            while (!binds(port)) {
                assertTrue(System.nanoTime() < deadline, "port " + port + " is still bound: a listener was left open");
                Thread.onSpinWait();
            }
        }

        @Test
        @DisplayName("is a conflict, tries no further candidate, and leaves no listener behind")
        void shouldGiveUpAndCloseOnPortZero() throws Exception {
            var asked = new AtomicInteger();
            var candidate = new AtomicInteger();
            var listener = listener(host -> {
                asked.incrementAndGet();
                candidate.set(WebListener.kernelCandidate(host));
                return candidate.get();
            }, Duration.ofMillis(300));
            var release = blockEventLoop();
            try {
                assertThrows(WebListenerConflictException.class, () -> listener.apply(true, false, 0, "test"));
            } finally {
                release.countDown();
            }

            assertEquals(1, asked.get());
            assertFalse(listener.state().open());
            assertEventuallyFree(candidate.get());
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, "PM_RUNTIME-102");
        }

        @Test
        @DisplayName("is a conflict for a named port and leaves no listener behind")
        void shouldGiveUpAndCloseOnANamedPort() throws Exception {
            var port = WebListener.kernelCandidate("127.0.0.1");
            var listener = listener(WebListener::kernelCandidate, Duration.ofMillis(300));
            var release = blockEventLoop();
            try {
                assertThrows(WebListenerConflictException.class, () -> listener.apply(true, false, port, "test"));
            } finally {
                release.countDown();
            }

            assertFalse(listener.state().open());
            assertEventuallyFree(port);
        }
    }
}
