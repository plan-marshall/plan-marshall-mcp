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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


import de.cuioss.tools.logging.CuiLogger;
import de.planmarshall.mcp.server.runtime.RuntimeContext;
import de.planmarshall.runtime.log.RuntimeLogMessages;
import de.planmarshall.runtime.web.WebListenerConflictException;
import de.planmarshall.runtime.web.WebState;
import de.planmarshall.runtime.web.WebTls;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.vertx.http.runtime.VertxHttpRecorder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * The web listener: a second Vert.x HTTP server on TCP beside the Unix-socket listener, opened and closed while
 * the runtime runs (plan-marshall-documentation: doc/specification/cli-and-security/03-web-server.adoc).
 * <p>
 * Loopback mode serves plain HTTP on {@code 127.0.0.1:<port>}; LAN mode serves HTTPS on all interfaces with
 * the runtime's self-signed certificate ({@link WebTls}). The listener knows its connections, so the
 * authentication mechanism tells a web request from a socket request by the connection it arrived on.
 * Closing the server ends its connections, browser streams included; the socket listener is untouched.
 * <p>
 * The listener binds without address reuse, so a port that another socket holds on any local address is a
 * conflict: with address reuse macOS lets the wildcard bind succeed beside a listener on {@code 127.0.0.1},
 * and loopback clients then reach that listener instead of this one. Port {@code 0} asks for any free port:
 * the listener takes candidates from the kernel, binds each explicitly, which is the conflict check, and
 * gives up after {@value #PORT_CANDIDATES} of them; the kernel's own choice for a bind to port {@code 0}
 * does not make that check.
 *
 * @since 0.1
 */
@ApplicationScoped
public class WebListener {

    private static final CuiLogger LOGGER = new CuiLogger(WebListener.class);
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String CAUSE_SHUTDOWN = "runtime shutdown";
    private static final String LOOPBACK = "127.0.0.1";
    private static final String ALL_INTERFACES = "0.0.0.0";

    /** The number of candidates tried for port {@code 0} before the open fails. */
    static final int PORT_CANDIDATES = 8;

    /** The source of port candidates for port {@code 0}. */
    @FunctionalInterface
    interface PortCandidates {

        /**
         * @param host the address the listener binds
         * @return a port to try
         * @throws IOException if no candidate can be named
         */
        int next(String host) throws IOException;
    }

    private final Vertx vertx;
    private final RuntimeContext context;
    private final PortCandidates candidates;
    private final Duration wait;
    private final Set<HttpConnection> connections = ConcurrentHashMap.newKeySet();
    private HttpServer server;
    private WebState state = WebState.disabled();

    /**
     * @param vertx   the runtime's Vert.x instance
     * @param context the runtime identity, for the TLS directory
     */
    @Inject
    public WebListener(Vertx vertx, RuntimeContext context) {
        this(vertx, context, WebListener::kernelCandidate, WAIT);
    }

    WebListener(Vertx vertx, RuntimeContext context, PortCandidates candidates, Duration wait) {
        this.vertx = vertx;
        this.context = context;
        this.candidates = candidates;
        this.wait = wait;
    }

    /**
     * @param connection the connection of a request
     * @return {@code true} if the request arrived on the web listener
     */
    public boolean serves(HttpConnection connection) {
        return connection != null && connections.contains(connection);
    }

    /** @return the current setting and live state */
    public synchronized WebState state() {
        return state;
    }

    /**
     * Opens or closes the listener; returns once it is open or closed.
     *
     * @param enabled whether web access is to be enabled
     * @param lan     the exposure: all interfaces over TLS instead of loopback
     * @param port    the port, {@code 0} for any free port
     * @param cause   what asked for it, for the log
     * @return the new state, with the port the listener bound
     * @throws WebListenerConflictException if the port is occupied on a local address, no free port was found
     *                                      for port {@code 0}, or the listener is open with the other exposure
     */
    public synchronized WebState apply(boolean enabled, boolean lan, int port, String cause)
            throws WebListenerConflictException {
        if (!enabled) {
            close(cause);
            state = new WebState(false, state.lan(), port, false);
            return state;
        }
        LOGGER.info(RuntimeLogMessages.INFO.WEB_LISTENER_OPEN_REQUESTED, lan ? ALL_INTERFACES : LOOPBACK, port, lan,
                cause);
        if (server != null) {
            if (state.lan() != lan || port != 0 && state.port() != port) {
                throw new WebListenerConflictException("web access is enabled with lan=%s on port %s; disable it first"
                        .formatted(state.lan(), state.port()), null);
            }
            return state;
        }
        state = new WebState(true, lan, open(lan, port), true);
        return state;
    }

    void onShutdown(@Observes ShutdownEvent event) {
        synchronized (this) {
            close(CAUSE_SHUTDOWN);
        }
    }

    private int open(boolean lan, int port) throws WebListenerConflictException {
        var host = lan ? ALL_INTERFACES : LOOPBACK;
        var hostNames = new HashSet<>(Set.of(LOOPBACK, "localhost"));
        PemKeyCertOptions keyCert = null;
        if (lan) {
            hostNames.add("::1");
            try {
                var tls = WebTls.loadOrCreate(context.paths().base().resolve("web").resolve("tls"));
                hostNames.addAll(tls.names());
                keyCert = new PemKeyCertOptions()
                        .setCertValue(Buffer.buffer(tls.certificatePem()))
                        .setKeyValue(Buffer.buffer(tls.privateKeyPem()));
            } catch (IOException | GeneralSecurityException e) {
                throw new WebListenerConflictException("TLS material unavailable: " + e.getMessage(), e);
            }
        }
        server = port != 0 ? listenOn(host, port, hostNames, keyCert) : listenOnFreePort(host, hostNames, keyCert);
        var bound = server.actualPort();
        LOGGER.info(RuntimeLogMessages.INFO.WEB_LISTENER_OPENED, host, bound, lan);
        return bound;
    }

    private HttpServer listenOn(String host, int port, Set<String> hostNames, PemKeyCertOptions keyCert)
            throws WebListenerConflictException {
        try {
            return listen(host, port, hostNames, keyCert);
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn(RuntimeLogMessages.WARN.WEB_LISTENER_FAILED, port, e.getMessage());
            throw new WebListenerConflictException("port %s cannot be bound".formatted(port), e);
        }
    }

    private HttpServer listenOnFreePort(String host, Set<String> hostNames, PemKeyCertOptions keyCert)
            throws WebListenerConflictException {
        for (int attempt = 0; attempt < PORT_CANDIDATES; attempt++) {
            int candidate;
            try {
                candidate = candidates.next(host);
            } catch (IOException e) {
                throw new WebListenerConflictException("no port candidate: " + e.getMessage(), e);
            }
            try {
                return listen(host, candidate, hostNames, keyCert);
            } catch (ExecutionException e) {
                // the candidate is occupied on a local address: rejected, the next one is tried
                LOGGER.warn(RuntimeLogMessages.WARN.WEB_LISTENER_FAILED, candidate, e.getMessage());
            } catch (TimeoutException e) {
                // no answer of the bind is no conflict of this candidate: no further candidate is tried
                LOGGER.warn(RuntimeLogMessages.WARN.WEB_LISTENER_FAILED, candidate, e.getMessage());
                throw new WebListenerConflictException("port %s cannot be bound".formatted(candidate), e);
            }
        }
        throw new WebListenerConflictException("no free port among %s candidates".formatted(PORT_CANDIDATES), null);
    }

    /**
     * Binds one server. A bind that does not answer in time is given up: the server is closed, which takes
     * effect when the bind completes, so no listener is left running outside {@link #server}.
     */
    private HttpServer listen(String host, int port, Set<String> hostNames, PemKeyCertOptions keyCert)
            throws ExecutionException, TimeoutException {
        var options = new HttpServerOptions().setPort(port).setHost(host).setReuseAddress(false);
        if (keyCert != null) {
            options.setSsl(true).setKeyCertOptions(keyCert);
        }
        var scheme = keyCert != null ? "https://" : "http://";
        var hosts = new HashSet<String>();
        var origins = new HashSet<String>();
        for (var name : hostNames) {
            var authority = WebRequestHandler.authority((name.contains(":") ? "[" + name + "]" : name) + ":" + port);
            hosts.add(authority);
            origins.add(scheme + authority);
        }
        var created = vertx.createHttpServer(options)
                .connectionHandler(connection -> {
                    connections.add(connection);
                    connection.closeHandler(_ -> connections.remove(connection));
                })
                .exceptionHandler(failure -> LOGGER.warn(RuntimeLogMessages.WARN.WEB_LISTENER_CONNECTION_FAILED, port,
                        describe(failure)))
                .requestHandler(new WebRequestHandler(hosts, origins, VertxHttpRecorder.getRootHandler()));
        try {
            await(created.listen());
        } catch (TimeoutException e) {
            created.close();
            throw e;
        }
        return created;
    }

    /** The failure as one line: a client can cause it at will, so its stack trace is not logged. */
    private static String describe(Throwable failure) {
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    /** A port the kernel names as free for the address; whether it is free on every address shows at the bind. */
    static int kernelCandidate(String host) throws IOException {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(host, 0));
            return socket.getLocalPort();
        }
    }

    private void close(String cause) {
        if (server == null) {
            return;
        }
        LOGGER.info(RuntimeLogMessages.INFO.WEB_LISTENER_CLOSING, state.port(), cause);
        try {
            await(server.close());
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn(RuntimeLogMessages.WARN.WEB_LISTENER_FAILED, state.port(), e.getMessage());
        }
        server = null;
        connections.clear();
        LOGGER.info(RuntimeLogMessages.INFO.WEB_LISTENER_CLOSED, state.port());
    }

    private <T> T await(Future<T> future) throws ExecutionException, TimeoutException {
        try {
            return future.toCompletionStage().toCompletableFuture().get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionException(e);
        }
    }
}
