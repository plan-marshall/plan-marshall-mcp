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

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.regex.Pattern;


import de.planmarshall.mcp.server.test.DaemonProcess;
import de.planmarshall.mcp.server.test.TestRuntime;
import de.planmarshall.mcp.server.test.UdsHttp;
import de.planmarshall.mcp.server.test.VerificationResults;
import de.planmarshall.runtime.test.TestBases;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The packaged {@code pm-web-app} served by the packaged daemon (JVM runner or native binary): the real app
 * shell at {@code /} and on a client route, an immutable asset of the served index, the missing asset, the API
 * and MCP boundary, the {@code Host} guard, and the security headers
 * (plan-marshall-documentation: doc/specification/cli-and-security/03-web-server.adoc, Web App).
 * <p>
 * The test fails on the placeholder of a build without the resource JAR: it asserts the app's own marker and a
 * fetched asset, so it passes only when the dependency is on the server classpath.
 */
@DisplayName("Packaged web app served by the packaged daemon")
class WebAppIT {

    private static final String PLACEHOLDER = "The web app is not part of this build";
    private static final Pattern IMMUTABLE_SCRIPT = Pattern.compile("\"(/_app/immutable/[^\"]+\\.js)\"");

    private static Path base;
    private static DaemonProcess daemon;

    @BeforeAll
    static void start() throws IOException {
        base = TestBases.create("pmwa");
        daemon = DaemonProcess.startReady(base);
    }

    @AfterAll
    static void stop() throws IOException {
        daemon.close();
        TestBases.delete(base);
    }

    /** The daemon's own output, which names the reason when it refuses to open the listener. */
    private static String daemonLog() {
        return "daemon log:\n" + daemon.output();
    }

    /** An open loopback listener on the port it bound; closing it closes the listener again. */
    private record Listener(int port) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            assertEquals(200, putWeb("{\"enabled\":false,\"port\":" + port + "}").status());
        }
    }

    private static Listener enabled() throws IOException {
        var opened = putWeb("{\"enabled\":true,\"lan\":false,\"port\":0}");
        assertEquals(200, opened.status(), WebAppIT::daemonLog);
        return new Listener(new JsonObject(opened.body()).getInteger("port"));
    }

    private static UdsHttp.Response putWeb(String body) throws IOException {
        var headers = TestRuntime.bearer(daemon.token());
        headers.put("Content-Type", "application/json");
        return UdsHttp.request(daemon.paths().socket(), "PUT", "/api/v1/web", headers, body);
    }

    private static UdsHttp.Response web(int port, String method, String path, Map<String, String> extra)
            throws IOException {
        var headers = new HashMap<>(Map.of("Host", "127.0.0.1:" + port));
        headers.putAll(extra);
        return UdsHttp.request(new InetSocketAddress("127.0.0.1", port), method, path, headers, null);
    }

    private static String header(UdsHttp.Response response, String name) {
        return response.headers().get(name.toLowerCase(Locale.ROOT));
    }

    private static void assertSecurityHeaders(UdsHttp.Response response) {
        assertEquals("DENY", header(response, "X-Frame-Options"));
        assertEquals("nosniff", header(response, "X-Content-Type-Options"));
        assertEquals("no-referrer", header(response, "Referrer-Policy"));
        var csp = header(response, "Content-Security-Policy");
        assertNotNull(csp, "Content-Security-Policy");
        assertTrue(csp.startsWith("default-src 'self'; script-src 'self' 'sha256-"),
                "CSP script-src must carry sha256 hash: " + csp);
        assertFalse(csp.contains("'unsafe-inline'"), "CSP must never contain 'unsafe-inline'");
        assertTrue(csp.contains("connect-src 'self'"), "CSP connect-src");
        assertTrue(csp.contains("object-src 'none'"), "CSP object-src");
        assertTrue(csp.contains("base-uri 'none'"), "CSP base-uri");
        assertTrue(csp.contains("frame-ancestors 'none'"), "CSP frame-ancestors");
        assertTrue(csp.contains("require-trusted-types-for 'script'"), "CSP Trusted Types");
    }

    @Test
    @DisplayName("serves the real app, its assets and its client route, and keeps the API and MCP boundary")
    void shouldServePackagedApp() throws Exception {
        try (var listener = enabled()) {
            var port = listener.port();
            var index = web(port, "GET", "/", Map.of());
            assertEquals(200, index.status(), WebAppIT::daemonLog);
            assertTrue(header(index, "Content-Type").startsWith("text/html"), header(index, "Content-Type"));
            assertTrue(index.body().contains("plan-marshall"), "the served index names the app");
            assertTrue(index.body().contains("_app/"), "the served index references its asset tree");
            assertFalse(index.body().contains(PLACEHOLDER), "the daemon serves the app, not the placeholder");
            assertEquals(StaticWebApp.CACHE_NO_CACHE, header(index, "Cache-Control"));
            assertSecurityHeaders(index);

            var matcher = IMMUTABLE_SCRIPT.matcher(index.body());
            assertTrue(matcher.find(), "the served index references an immutable script");
            var assetPath = matcher.group(1);
            var asset = web(port, "GET", assetPath, Map.of());
            assertEquals(200, asset.status(), "asset " + assetPath);
            assertTrue(header(asset, "Content-Type").contains("javascript"), header(asset, "Content-Type"));
            assertFalse(asset.body().isEmpty(), "asset " + assetPath + " has content");
            assertEquals(StaticWebApp.CACHE_IMMUTABLE, header(asset, "Cache-Control"));
            assertSecurityHeaders(asset);

            var designer = web(port, "GET", "/designer?project=pm-mcp-clients", Map.of());
            assertEquals(200, designer.status());
            assertTrue(header(designer, "Content-Type").startsWith("text/html"));
            assertTrue(designer.body().contains("plan-marshall"));
            assertFalse(designer.body().contains(PLACEHOLDER));
            assertSecurityHeaders(designer);

            var missing = web(port, "GET", "/missing.js", Map.of());
            assertEquals(404, missing.status(), "a missing asset keeps its 404");
            assertFalse(missing.body().contains(PLACEHOLDER));

            assertEquals(401, web(port, "GET", "/api/v1/status", Map.of()).status(),
                    "the API keeps its authentication");
            assertEquals(404, web(port, "GET", "/mcp", Map.of()).status());
            assertEquals(404, web(port, "GET", "/api/unknown", Map.of()).status());
            assertEquals(403, web(port, "GET", "/", Map.of("Host", "rebound.example:" + port)).status());
            assertEquals(400, web(port, "GET", "/%2e%2e/secret", Map.of()).status());

            var values = new LinkedHashMap<String, Object>();
            values.put("index_status", index.status());
            values.put("index_content_type", header(index, "Content-Type"));
            values.put("asset_path", assetPath);
            values.put("asset_status", asset.status());
            values.put("asset_content_type", header(asset, "Content-Type"));
            values.put("asset_bytes", asset.body().length());
            values.put("designer_status", designer.status());
            values.put("missing_asset_status", missing.status());
            values.put("placeholder_absent", !index.body().contains(PLACEHOLDER));
            values.put("security_headers", String.join(",", WebRequestHandler.SECURITY_HEADERS.keySet()));
            VerificationResults.write("web-app-static-export", values, true);
        }
    }

    @Test
    @DisplayName("serves the app shell to a HEAD request without a body")
    void shouldServeHeadWithoutBody() throws Exception {
        try (var listener = enabled()) {
            var port = listener.port();
            var response = web(port, "HEAD", "/", Map.of());

            assertEquals(200, response.status(), WebAppIT::daemonLog);
            assertEquals("", response.body());
            assertTrue(header(response, "Content-Type").startsWith("text/html"));
            assertNotNull(header(response, "Content-Security-Policy"));
        }
    }

    @Test
    @DisplayName("boots the packaged web app in a headless browser under its own CSP")
    void shouldBootInHeadlessBrowserUnderCsp() throws Exception {
        var chrome = findChromeBinary();
        Assumptions.assumeTrue(chrome != null,
                "Chromium or Google Chrome not found on host; skipping browser smoke");
        try (var listener = enabled()) {
            var port = listener.port();
            var result = smokeInBrowser(chrome, port);

            assertEquals(List.of(), result.cspViolations(),
                    "Browser console must have no CSP violations: " + result.cspViolations());
            assertEquals("plan-marshall", result.title());
            assertTrue(result.hasVisibleElement(), "App must render a known visible element");
            assertEquals(401, result.apiStatus(), "In-page fetch to /api/v1/status must succeed without CSP violation");

            var values = new LinkedHashMap<String, Object>();
            values.put("browser_tested", true);
            values.put("browser_title", result.title());
            values.put("browser_element_rendered", result.hasVisibleElement());
            values.put("browser_api_status", result.apiStatus());
            values.put("browser_csp_clean", result.cspViolations().isEmpty());
            VerificationResults.write("web-app-browser-proof", values, true);
        }
    }

    record BrowserResult(String title, boolean hasVisibleElement, int apiStatus, List<String> cspViolations) {
    }

    private static Path findChromeBinary() {
        var env = System.getenv("CHROME_BIN");
        if (env != null && Files.isExecutable(Path.of(env))) {
            return Path.of(env);
        }
        var candidates = List.of(
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Users/oliver/Library/Caches/ms-playwright/chromium-1248/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing",
                "/Applications/Chromium.app/Contents/MacOS/Chromium",
                "/usr/bin/google-chrome",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser");
        for (var candidate : candidates) {
            var path = Path.of(candidate);
            if (Files.isExecutable(path)) {
                return path;
            }
        }
        var pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (var dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
                for (var name : List.of("google-chrome", "chromium", "chromium-browser", "chrome")) {
                    var candidate = Path.of(dir, name);
                    if (Files.isExecutable(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static BrowserResult smokeInBrowser(Path chrome, int port) throws Exception {
        var tempProfile = Files.createTempDirectory("chrome-smoke-");
        int cdpPort;
        try (var s = new ServerSocket(0)) {
            cdpPort = s.getLocalPort();
        }
        var pb = new ProcessBuilder(
                chrome.toString(),
                "--headless=new",
                "--remote-debugging-port=" + cdpPort,
                "--user-data-dir=" + tempProfile.toAbsolutePath(),
                "--no-sandbox",
                "--disable-gpu");
        var process = pb.start();
        try {
            var http = HttpClient.newHttpClient();
            var deadline = System.currentTimeMillis() + 10_000;
            String wsUrl = null;
            while (System.currentTimeMillis() < deadline) {
                try {
                    var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + cdpPort + "/json/new?http://127.0.0.1:" + port + "/"))
                            .PUT(HttpRequest.BodyPublishers.noBody())
                            .build();
                    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        var json = new JsonObject(resp.body());
                        wsUrl = json.getString("webSocketDebuggerUrl");
                        break;
                    }
                } catch (IOException _) {
                    Thread.sleep(100);
                }
            }
            if (wsUrl == null) {
                throw new IllegalStateException("Failed to connect to Chrome DevTools port " + cdpPort + " within 10s");
            }

            var cspViolations = new CopyOnWriteArrayList<String>();
            var messageQueue = new ConcurrentHashMap<Integer, CompletableFuture<JsonObject>>();
            var nextId = new AtomicInteger(1);

            var ws = http.newWebSocketBuilder().buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                private final StringBuilder buffer = new StringBuilder();

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    buffer.append(data);
                    if (last) {
                        var text = buffer.toString();
                        buffer.setLength(0);
                        try {
                            var msg = new JsonObject(text);
                            var id = msg.getInteger("id");
                            if (id != null) {
                                var fut = messageQueue.remove(id);
                                if (fut != null) {
                                    fut.complete(msg);
                                }
                            }
                            var method = msg.getString("method");
                            if ("Runtime.consoleAPICalled".equals(method)) {
                                var params = msg.getJsonObject("params");
                                var type = params.getString("type");
                                var args = params.getJsonArray("args");
                                var line = type + ": " + (args != null ? args.encode() : "");
                                if (isCspViolation(line)) {
                                    cspViolations.add(line);
                                }
                            } else if ("Log.entryAdded".equals(method)) {
                                var entry = msg.getJsonObject("params").getJsonObject("entry");
                                var textEntry = entry.getString("text", "");
                                if (isCspViolation(textEntry)) {
                                    cspViolations.add(textEntry);
                                }
                            } else if ("Runtime.exceptionThrown".equals(method)) {
                                var details = msg.getJsonObject("params").getJsonObject("exceptionDetails");
                                var exception = details != null ? details.getJsonObject("exception") : null;
                                var exceptionText = details != null ? details.getString("text", "") : "";
                                if (exception != null) {
                                    exceptionText += " " + exception.getString("description", "");
                                }
                                if (isCspViolation(exceptionText)) {
                                    cspViolations.add(exceptionText);
                                }
                            }
                        }
                        /*TODO: Catch specific not Exception. Suppress: // cui-rewrite:disable InvalidExceptionUsageRecipe*/
                        catch (Exception _) {
                        }
                    }
                    webSocket.request(1);
                    return null;
                }
            }).get(5, TimeUnit.SECONDS);

            BiFunction<String, JsonObject, CompletableFuture<JsonObject>> send = (method, params) -> {
                int id = nextId.getAndIncrement();
                var fut = new CompletableFuture<JsonObject>();
                messageQueue.put(id, fut);
                var req = new JsonObject().put("id", id).put("method", method);
                if (params != null) {
                    req.put("params", params);
                }
                ws.sendText(req.encode(), true);
                return fut;
            };

            send.apply("Runtime.enable", null).get(5, TimeUnit.SECONDS);
            send.apply("Log.enable", null).get(5, TimeUnit.SECONDS);

            Thread.sleep(2000);

            var titleRes = send.apply("Runtime.evaluate", new JsonObject().put("expression", "document.title"))
                    .get(5, TimeUnit.SECONDS);
            var title = titleRes.getJsonObject("result", new JsonObject())
                    .getJsonObject("result", new JsonObject())
                    .getString("value", "");

            var elemRes = send.apply("Runtime.evaluate", new JsonObject().put("expression",
                    "document.querySelector('.app-loading, .app-header, [data-testid=\"brand-inert\"], .app-body') != null"))
                    .get(5, TimeUnit.SECONDS);
            var hasElement = Boolean.TRUE.equals(elemRes.getJsonObject("result", new JsonObject())
                    .getJsonObject("result", new JsonObject())
                    .getBoolean("value"));

            var apiRes = send.apply("Runtime.evaluate", new JsonObject().put("expression",
                    "fetch('/api/v1/status').then(r => r.status)").put("awaitPromise", true))
                    .get(5, TimeUnit.SECONDS);
            var apiStatus = apiRes.getJsonObject("result", new JsonObject())
                    .getJsonObject("result", new JsonObject())
                    .getInteger("value", 0);

            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS);
            }
            /*TODO: Catch specific not Exception. Suppress: // cui-rewrite:disable InvalidExceptionUsageRecipe*/
            catch (Exception _) {
            }

            return new BrowserResult(title, hasElement, apiStatus, List.copyOf(cspViolations));
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            try (var s = Files.walk(tempProfile)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    }
                    /*TODO: Catch specific not Exception. Suppress: // cui-rewrite:disable InvalidExceptionUsageRecipe*/
                    catch (Exception _) {
                    }
                });
            }
            /*TODO: Catch specific not Exception. Suppress: // cui-rewrite:disable InvalidExceptionUsageRecipe*/
            catch (Exception _) {
            }
        }
    }

    private static boolean isCspViolation(String text) {
        if (text == null) {
            return false;
        }
        var lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("content security policy")
                || lower.contains("refused to execute")
                || lower.contains("refused to apply")
                || lower.contains("trustedscript")
                || lower.contains("trustedhtml")
                || lower.contains("trusted type");
    }
}
