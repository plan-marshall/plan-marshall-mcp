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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;


import de.planmarshall.mcp.server.test.UdsHttp;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The handler over a real Vert.x listener: the static app outside {@code /api/}, the untouched API and MCP
 * boundary, the SPA fallback, the missing asset, the confinement, the security headers, and the placeholder of
 * a build without the resource JAR.
 */
@DisplayName("Web request handler serving the packaged app")
class WebRequestHandlerTest {

    private static final String RESOURCES = "META-INF/resources";
    private static final String INDEX_BODY = "<!doctype html><title>fixture app</title>";
    private static final String SCRIPT_BODY = "console.log('fixture');";
    private static final int DEADLINE_MILLIS = 10_000;

    private static final Handler<HttpServerRequest> API = request -> request.response()
            .putHeader("Content-Type", "application/json").end("{\"api\":true}");

    @TempDir
    Path root;

    private Vertx vertx;
    private URLClassLoader loader;
    private StaticWebApp app;
    private WebServer server;

    @BeforeEach
    void start() throws Exception {
        var resources = root.resolve(RESOURCES);
        write(resources, "index.html", INDEX_BODY);
        write(resources, "_app/immutable/app.js", SCRIPT_BODY);
        write(resources, "styles.css", "body { color: black; }");
        loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, null);
        app = new StaticWebApp(loader);
        vertx = Vertx.vertx(new VertxOptions().setPreferNativeTransport(true).setEventLoopPoolSize(1));
        server = serve(app);
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
        loader.close();
    }

    private static void write(Path resources, String name, String body) throws IOException {
        var file = resources.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    /** A listener on loopback with the handler wired as the web listener wires it. */
    private WebServer serve(StaticWebApp staticApp) throws Exception {
        var port = WebListener.kernelCandidate("127.0.0.1");
        var created = vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(port)
                .setReuseAddress(false))
                .requestHandler(new WebRequestHandler(Set.of("127.0.0.1:" + port),
                        Set.of("http://127.0.0.1:" + port), API, staticApp));
        await(created.listen());
        return new WebServer(created, created.actualPort());
    }

    private static void await(Future<HttpServer> future) throws Exception {
        future.toCompletionStage().toCompletableFuture().get(DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
    }

    private UdsHttp.Response request(String method, String path, Map<String, String> extra) throws IOException {
        var headers = new HashMap<>(Map.of("Host", "127.0.0.1:" + server.port()));
        headers.putAll(extra);
        return UdsHttp.request(new InetSocketAddress("127.0.0.1", server.port()), method, path, headers,
                "POST".equals(method) || "PUT".equals(method) ? "{}" : null);
    }

    private UdsHttp.Response get(String path) throws IOException {
        return request("GET", path, Map.of());
    }

    @Test
    @DisplayName("serves the entry document at the root with text/html, no caching and the security headers")
    void shouldServeIndexAtRoot() throws Exception {
        var response = get("/");

        assertEquals(200, response.status());
        assertEquals(INDEX_BODY, response.body());
        assertTrue(response.headers().get("content-type").startsWith("text/html"));
        assertEquals(StaticWebApp.CACHE_NO_CACHE, response.headers().get("cache-control"));
        WebRequestHandler.SECURITY_HEADERS.forEach((name, value) -> assertEquals(value,
                response.headers().get(name.toLowerCase(Locale.ROOT)), name));
    }

    @Test
    @DisplayName("serves a content-hashed asset with its content type, its body and immutable caching")
    void shouldServeImmutableAsset() throws Exception {
        var response = get("/_app/immutable/app.js");

        assertEquals(200, response.status());
        assertEquals(SCRIPT_BODY, response.body());
        assertTrue(response.headers().get("content-type").contains("javascript"));
        assertEquals(StaticWebApp.CACHE_IMMUTABLE, response.headers().get("cache-control"));
        WebRequestHandler.SECURITY_HEADERS.forEach((name, value) -> assertEquals(value,
                response.headers().get(name.toLowerCase(Locale.ROOT)), name));
    }

    @Test
    @DisplayName("answers a client route with the app shell")
    void shouldServeShellForNavigation() throws Exception {
        var response = get("/designer");

        assertEquals(200, response.status());
        assertEquals(INDEX_BODY, response.body());
        assertTrue(response.headers().get("content-type").startsWith("text/html"));
    }

    @Test
    @DisplayName("falls back to the shell only for a request that accepts text/html")
    void shouldFallBackOnlyForHtmlAccept() throws Exception {
        assertEquals(404, get("/missing.js").status());
        var navigation = request("GET", "/missing.js", Map.of("Accept", "text/html,application/xhtml+xml"));
        assertEquals(200, navigation.status());
        assertEquals(INDEX_BODY, navigation.body());
    }

    @Test
    @DisplayName("keeps a missing asset a 404 and does not answer it with the shell")
    void shouldKeepMissingAssetMissing() throws Exception {
        var response = get("/_app/immutable/missing.js");

        assertEquals(404, response.status());
        assertEquals("", response.body());
    }

    @Test
    @DisplayName("hands /api/v1/ to the runtime and keeps every other route a 404")
    void shouldKeepApiAndMcpBoundary() throws Exception {
        var api = get("/api/v1/status");
        assertEquals(200, api.status());
        assertEquals("{\"api\":true}", api.body());

        assertEquals(404, get("/mcp").status());
        assertEquals(404, request("POST", "/mcp", Map.of()).status());
        assertEquals(404, get("/api/unknown").status());
        assertEquals(404, request("POST", "/designer", Map.of()).status());
    }

    @Test
    @DisplayName("refuses a path that could name a file outside the resource root with 400")
    void shouldRefuseUnsafePath() throws Exception {
        assertEquals(400, get("/api/v1/../status").status());
        assertEquals(400, get("/%2e%2e/secret").status());
        assertEquals(400, get("/a//b").status());
        assertEquals(400, get("/a%5cb").status());
    }

    @Test
    @DisplayName("serves a HEAD request without a body and with the headers of the file")
    void shouldServeHeadWithoutBody() throws Exception {
        var response = request("HEAD", "/_app/immutable/app.js", Map.of());

        assertEquals(200, response.status());
        assertEquals("", response.body());
        assertTrue(response.headers().get("content-type").contains("javascript"));
    }

    @Test
    @DisplayName("keeps the placeholder when the resource JAR is absent")
    void shouldKeepPlaceholderWithoutResourceJar() throws Exception {
        try (var empty = new URLClassLoader(new URL[0], null)) {
            try (var fallback = serve(new StaticWebApp(empty))) {
                var index = UdsHttp.request(new InetSocketAddress("127.0.0.1", fallback.port()), "GET", "/",
                        Map.of("Host", "127.0.0.1:" + fallback.port()), null);

                assertEquals(200, index.status());
                assertTrue(index.body().contains("The web app is not part of this build"));
                assertFalse(index.body().contains(INDEX_BODY));
            }
        }
    }

    @Test
    @DisplayName("refuses a mismatching Host and an OPTIONS request on the static path too")
    void shouldKeepHostAndMethodGuard() throws Exception {
        assertEquals(403, request("GET", "/", Map.of("Host", "rebound.example:" + server.port())).status());
        assertEquals(403, request("OPTIONS", "/", Map.of()).status());
    }

    @Test
    @DisplayName("includes inline script hash in Content-Security-Policy script-src when present")
    void shouldIncludeInlineScriptHashInCsp() throws Exception {
        var inlineScript = "console.log('bootstrap');";
        var customHtml = "<!doctype html><title>hashed app</title><script>" + inlineScript + "</script>";
        var appDir = root.resolve("hashed-app");
        write(appDir.resolve(RESOURCES), "index.html", customHtml);
        try (var customLoader = new URLClassLoader(new URL[]{appDir.toUri().toURL()}, null)) {
            var customApp = new StaticWebApp(customLoader);
            try (var customServer = serve(customApp)) {
                var response = UdsHttp.request(new InetSocketAddress("127.0.0.1", customServer.port()), "GET", "/",
                        Map.of("Host", "127.0.0.1:" + customServer.port()), null);

                assertEquals(200, response.status());
                var csp = response.headers().get("content-security-policy");
                assertNotNull(csp);
                var expectedHash = StaticWebApp.sha256Hash(inlineScript);
                assertTrue(csp.contains("script-src 'self' " + expectedHash + ";"), "CSP must include hash: " + csp);
                assertFalse(csp.contains("'unsafe-inline'"));
            }
        }
    }

    /** A listener of the fixture; closing it closes the listener. */
    private record WebServer(HttpServer server, int port) implements AutoCloseable {

        @Override
        public void close() throws Exception {
            server.close().toCompletionStage().toCompletableFuture().get(DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }
}
