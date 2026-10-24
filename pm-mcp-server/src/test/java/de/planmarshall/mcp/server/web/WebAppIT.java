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
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;


import de.planmarshall.mcp.server.test.DaemonProcess;
import de.planmarshall.mcp.server.test.TestRuntime;
import de.planmarshall.mcp.server.test.UdsHttp;
import de.planmarshall.mcp.server.test.VerificationResults;
import de.planmarshall.runtime.test.TestBases;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
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
        WebRequestHandler.SECURITY_HEADERS.forEach((name, value) -> assertEquals(value, header(response, name), name));
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
}
