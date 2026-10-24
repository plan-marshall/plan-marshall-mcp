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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;


import de.planmarshall.mcp.server.test.TestRuntime;
import de.planmarshall.mcp.server.test.TestSecrets;
import de.planmarshall.mcp.server.test.UdsHttp;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
@DisplayName("Web listener beside the Unix socket (gate 15)")
class WebListenerTest {

    private static final String NON_LOOPBACK = "non-loopback";

    /** An open LAN listener on the port it bound; closing it closes the listener again. */
    private record LanListener(int port) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            assertEquals(200, putWeb("{\"enabled\":false}").status());
        }
    }

    /** Opens the LAN listener on a port of its own choice (port 0). */
    private static LanListener lanEnabled() throws IOException {
        var opened = putWeb("{\"enabled\":true,\"lan\":true,\"port\":0}");
        assertEquals(200, opened.status(), opened.body());
        return new LanListener(new JsonObject(opened.body()).getInteger("port"));
    }

    /** An address of this machine that is neither loopback nor link-local, if it has one. */
    private static Optional<InetAddress> nonLoopbackAddress() throws IOException {
        for (var nic : NetworkInterface.networkInterfaces().toList()) {
            if (nic.isUp() && !nic.isLoopback()) {
                var address = nic.inetAddresses().filter(candidate -> !candidate.isLinkLocalAddress()
                        && !candidate.isLoopbackAddress()).findFirst();
                if (address.isPresent()) {
                    return Optional.of(InetAddress.getByAddress(address.get().getAddress()));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean binds(InetAddress address, int port, boolean reuseAddress) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(reuseAddress);
            socket.bind(new InetSocketAddress(address, port));
            return true;
        } catch (IOException _) {
            return false;
        }
    }

    private static UdsHttp.Response putWeb(String body) throws IOException {
        var headers = TestRuntime.bearer(TestRuntime.token());
        headers.put("Content-Type", "application/json");
        return UdsHttp.request(TestRuntime.paths().socket(), "PUT", "/api/v1/web", headers, body);
    }

    private static UdsHttp.Response web(int port, String method, String path, Map<String, String> extra)
            throws IOException {
        var headers = new HashMap<>(Map.of("Host", "127.0.0.1:" + port));
        headers.putAll(extra);
        return UdsHttp.request(new InetSocketAddress("127.0.0.1", port), method, path, headers,
                "PUT".equals(method) || "POST".equals(method) ? "{}" : null);
    }

    private static Map<String, String> device() {
        return Map.of("Authorization", "Bearer " + TestSecrets.DEVICE_SECRET);
    }

    private static int nextHeartbeat(UdsHttp.Stream stream) throws IOException {
        for (var line = stream.readLine(); line != null; line = stream.readLine()) {
            if (line.startsWith("data:")) {
                return new JsonObject(line.substring(5).strip()).getInteger("seq");
            }
        }
        throw new IOException("event stream ended");
    }

    @Test
    @DisplayName("opens and closes on loopback at runtime, serves only its routes, and leaves the socket stream alone")
    void shouldOpenAndCloseLoopback() throws Exception {
        try (var events = UdsHttp.open(TestRuntime.paths().socket(), "GET", "/api/v1/events",
                     TestRuntime.bearer(TestRuntime.token()), null)) {
            assertEquals(0, nextHeartbeat(events));

            var opened = putWeb("{\"enabled\":true,\"lan\":false,\"port\":0}");
            assertEquals(200, opened.status(), opened.body());
            int port = new JsonObject(opened.body()).getInteger("port");
            assertNotEquals(0, port);
            assertEquals(new JsonObject().put("enabled", true).put("lan", false).put("port", port).put("open", true),
                    new JsonObject(opened.body()));

            var status = web(port, "GET", "/api/v1/status", device());
            assertEquals(200, status.status(), status.body());
            assertTrue(new JsonObject(status.body()).getJsonObject("web").getBoolean("open"));
            assertEquals(port, new JsonObject(status.body()).getJsonObject("web").getInteger("port"));
            WebRequestHandler.securityHeaders(StaticWebApp.fromContextClassLoader()).forEach((name, value) -> assertEquals(value,
                    status.headers().get(name.toLowerCase(Locale.ROOT)), name));
            assertFalse(status.headers().keySet().stream().anyMatch(name -> name.startsWith("access-control-")));

            assertEquals(401, web(port, "GET", "/api/v1/status",
                    Map.of("Authorization", "Bearer " + TestRuntime.token())).status());
            assertEquals(401, web(port, "GET", "/api/v1/status", Map.of()).status());
            assertEquals(404, web(port, "POST", "/mcp", Map.of("PM-MCP-Job-Token", TestSecrets.JOB_TOKEN)).status());
            assertEquals(404, web(port, "POST", "/mcp", device()).status());
            assertEquals(404, web(port, "GET", "/api/v2/status", device()).status());
            assertEquals(400, web(port, "GET", "/api/v1/../v1/status", device()).status());
            assertEquals(403, web(port, "GET", "/api/v1/status", Map.of("Host", "evil.example:" + port,
                    "Authorization", "Bearer " + TestSecrets.DEVICE_SECRET)).status());
            assertEquals(403, web(port, "OPTIONS", "/api/v1/status", device()).status());
            assertEquals(403, web(port, "PUT", "/api/v1/web", Map.of("Origin", "http://evil.example",
                    "Authorization", "Bearer " + TestSecrets.DEVICE_SECRET)).status());
            var cliOnly = web(port, "PUT", "/api/v1/web", Map.of("Origin", "http://127.0.0.1:" + port,
                    "Authorization", "Bearer " + TestSecrets.DEVICE_SECRET, "Content-Type", "application/json"));
            assertEquals(403, cliOnly.status());
            assertEquals("cli_only", new JsonObject(cliOnly.body()).getString("code"));
            var app = web(port, "GET", "/plans/some-plan", Map.of());
            assertEquals(200, app.status());
            assertTrue(app.body().contains("<!doctype html>"));
            assertEquals("DENY", app.headers().get("x-frame-options"));

            assertEquals(409, putWeb("{\"enabled\":true,\"lan\":true,\"port\":" + port + "}").status());
            assertEquals(200, putWeb("{\"enabled\":true,\"lan\":false,\"port\":" + port + "}").status());
            var again = putWeb("{\"enabled\":true,\"lan\":false,\"port\":0}");
            assertEquals(200, again.status());
            assertEquals(port, new JsonObject(again.body()).getInteger("port"));

            var seq = nextHeartbeat(events);
            var closed = putWeb("{\"enabled\":false,\"port\":" + port + "}");
            assertEquals(200, closed.status());
            assertFalse(new JsonObject(closed.body()).getBoolean("open"));
            assertThrows(ConnectException.class, () -> web(port, "GET", "/api/v1/status", device()));

            assertTrue(nextHeartbeat(events) > seq);
        }
    }

    @Test
    @DisplayName("serves HTTPS with the self-signed certificate in LAN mode")
    void shouldServeLanOverTls() throws Exception {
        try (var listener = lanEnabled()) {
            var port = listener.port();
            var pem = Files.readString(TestRuntime.paths().base().resolve("web/tls/cert.pem"));
            var certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            var trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            trust.setCertificateEntry("pm-mcpd", certificate);
            var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trust);
            var ssl = SSLContext.getInstance("TLS");
            ssl.init(null, factory.getTrustManagers(), null);
            try (var client = HttpClient.newBuilder().sslContext(ssl).build()) {
                var response = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port
                                + "/api/v1/status")).header("Authorization", "Bearer " + TestSecrets.DEVICE_SECRET).build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode(), response.body());
                assertTrue(new JsonObject(response.body()).getJsonObject("web").getBoolean("lan"));
            }
        }
    }

    @Test
    @DisplayName("an occupied port leaves the listener closed with web_listener_conflict")
    void shouldRefuseOccupiedPort() throws Exception {
        try (var occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var response = putWeb("{\"enabled\":true,\"port\":" + occupied.getLocalPort() + "}");

            assertEquals(409, response.status());
            assertEquals("web_listener_conflict", new JsonObject(response.body()).getString("code"));
        }
    }

    @ParameterizedTest(name = "foreign listener on {0}")
    @ValueSource(strings = {"127.0.0.1", "::1", NON_LOOPBACK})
    @DisplayName("LAN mode refuses a port that another listener holds on one local address")
    void shouldRefuseLanOnPortHeldOnOneAddress(String where) throws Exception {
        var address = NON_LOOPBACK.equals(where) ? nonLoopbackAddress() : Optional.of(InetAddress.getByName(where));
        assumeTrue(address.isPresent(), "this machine has no non-loopback address");
        ServerSocket foreign;
        try {
            foreign = new ServerSocket(0, 1, address.get());
        } catch (IOException e) {
            assumeTrue(false, "cannot listen on " + where + ": " + e.getMessage());
            return;
        }
        try (foreign) {
            var response = putWeb("{\"enabled\":true,\"lan\":true,\"port\":" + foreign.getLocalPort() + "}");

            assertEquals(409, response.status(), response.body());
            assertEquals("web_listener_conflict", new JsonObject(response.body()).getString("code"));
            var status = UdsHttp.request(TestRuntime.paths().socket(), "GET", "/api/v1/status",
                    TestRuntime.bearer(TestRuntime.token()), null);
            assertFalse(new JsonObject(status.body()).getJsonObject("web").getBoolean("open"));
        } finally {
            putWeb("{\"enabled\":false}");
        }
    }

    /**
     * Not a guarantee of the listener: this records what the platform lets another socket of the same user do
     * while the LAN listener is open. macOS lets a socket that sets {@code SO_REUSEADDR} bind a loopback
     * address on the listener's port, and that socket then receives the loopback connections; Linux refuses
     * the bind. The listener does not prevent the macOS case (web server specification, threat notes).
     */
    @Test
    @DisplayName("characterisation, not a guarantee: a same-user socket binding a loopback address beside the open LAN listener")
    void characterisesForeignBindBesideOpenLanListener() throws Exception {
        assumeTrue(OS.MAC.isCurrentOs() || OS.LINUX.isCurrentOs(), "measured on macOS and Linux only");
        var takenWithReuse = OS.MAC.isCurrentOs();
        try (var listener = lanEnabled()) {
            var measured = new LinkedHashMap<String, Boolean>();
            var expected = new LinkedHashMap<String, Boolean>();
            for (var host : new String[]{"127.0.0.1", "::1"}) {
                var address = InetAddress.getByName(host);
                if (!binds(address, 0, false)) {
                    // the address cannot be bound at all on this machine: nothing to characterise
                    continue;
                }
                measured.put(host + " with SO_REUSEADDR", binds(address, listener.port(), true));
                expected.put(host + " with SO_REUSEADDR", takenWithReuse);
                measured.put(host + " without SO_REUSEADDR", binds(address, listener.port(), false));
                expected.put(host + " without SO_REUSEADDR", false);
            }

            assertEquals(expected, measured);
        }
    }
}
