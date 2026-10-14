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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import de.planmarshall.mcp.server.runtime.RuntimeContext;
import de.planmarshall.runtime.web.WebListenerConflictException;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@EnableTestLogger
@DisplayName("Web listener on port 0: bounded selection among candidates")
class WebListenerPortSelectionTest {

    private Vertx vertx;

    @BeforeEach
    void start() {
        vertx = Vertx.vertx(new VertxOptions().setPreferNativeTransport(true));
    }

    @AfterEach
    void stop() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private WebListener listener(WebListener.PortCandidates candidates) {
        return new WebListener(vertx, new RuntimeContext(Optional.of("/nonexistent"), "test"), candidates,
                Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("rejects a candidate another listener holds, logs it, and binds the next one")
    void shouldSkipOccupiedCandidate() throws Exception {
        try (var foreign = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var occupied = foreign.getLocalPort();
            var asked = new AtomicInteger();
            var listener = listener(host -> asked.getAndIncrement() == 0 ? occupied : WebListener.kernelCandidate(host));

            var state = listener.apply(true, false, 0, "test");

            try {
                assertTrue(state.open());
                assertNotEquals(occupied, state.port());
                assertNotEquals(0, state.port());
                assertEquals(2, asked.get());
                LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, "PM_RUNTIME-102");
                LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, String.valueOf(occupied));
            } finally {
                listener.apply(false, false, 0, "test");
            }
        }
    }

    @Test
    @DisplayName("fails with web_listener_conflict once the bound on candidates is reached")
    void shouldGiveUpAtTheBound() throws Exception {
        try (var foreign = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var asked = new AtomicInteger();
            var listener = listener(_ -> {
                asked.incrementAndGet();
                return foreign.getLocalPort();
            });

            assertThrows(WebListenerConflictException.class, () -> listener.apply(true, false, 0, "test"));

            assertEquals(WebListener.PORT_CANDIDATES, asked.get());
            assertFalse(listener.state().open());
        }
    }
}
