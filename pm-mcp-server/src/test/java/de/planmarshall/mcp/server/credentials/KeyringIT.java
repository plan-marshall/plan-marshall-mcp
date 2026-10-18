/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.planmarshall.api.MachinePaths;
import de.planmarshall.core.service.AuditSink;
import de.planmarshall.mcp.server.test.DaemonProcess;
import de.planmarshall.mcp.server.test.TestRuntime;
import de.planmarshall.mcp.server.test.UdsHttp;
import de.planmarshall.runtime.credentials.CredentialStoreChoice;
import de.planmarshall.runtime.credentials.SecretStore;
import de.planmarshall.runtime.credentials.ServiceName;
import de.planmarshall.runtime.credentials.VerificationResults;
import de.planmarshall.runtime.lock.FileLockManager;
import de.planmarshall.runtime.test.TestBases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gates 4 and 5 from the packaged daemon (runner JAR or native {@code pm-mcpd}, started as a process with its own
 * short {@code PM_MCP_BASE}, so the entries live under an instance-qualified service name): the test records
 * {@code credentials.store} for the instance as the machine onboarding's credential-store aspect does, then a put,
 * status, replace and delete of one credential through the credential resource of the local API, served by the store
 * the machine recorded. The status read runs the store's read of the secret and answers
 * {@code present} or {@code not_found} with the store; no answer contains the secret. Records {@code target/verification-results/gate4-keychain-<mode>.json} (macOS) or
 * {@code gate5-secret-service-<mode>.json} (Linux); the times include the request over the socket.
 */
@DisplayName("OS keyring from the packaged daemon")
class KeyringIT {

    private static final String KEY = "pm-mcp-keyring-it";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The store recording under test appends no audit record; the audit log is not this test's subject. */
    private static final AuditSink NO_AUDIT = event -> {
    };

    private Path base;

    @BeforeEach
    void prepare() {
        base = TestBases.create("pmk");
    }

    @AfterEach
    void cleanUp() {
        TestBases.delete(base);
    }

    /**
     * Records {@code credentials.store} for this instance, as the credential-store aspect of the machine's
     * onboarding does before the runtime starts: the OS store where it is available (the Keychain on macOS, the
     * Secret Service on Linux), else the file store, since the integration host need not have a session keyring.
     * The recorded store is what the daemon serves, so a returned store of {@code file} on Linux says that the
     * Secret Service path was not exercised.
     *
     * @param base the machine root
     * @return the store that was recorded
     */
    private static CredentialStoreChoice.Store recordStore(Path base) {
        var paths = new MachinePaths(base, MachinePaths.Os.current());
        var available = !SecretStore.FILE.equals(CredentialStoreChoice.of(paths).integrityKeyStore().name());
        var store = available ? CredentialStoreChoice.Store.OS : CredentialStoreChoice.Store.FILE;
        CredentialStoreChoice.of(paths).record(new FileLockManager(NO_AUDIT), System.getProperty("user.name"), store);
        return store;
    }

    private static UdsHttp.Response send(DaemonProcess daemon, String method, String body) throws IOException {
        var headers = TestRuntime.bearer(daemon.token());
        headers.put("Content-Type", "application/json");
        return UdsHttp.request(daemon.paths().socket(), method, "/api/v1/credentials/" + KEY, headers, body);
    }

    private static UdsHttp.Response credential(DaemonProcess daemon, String method, String value) throws IOException {
        return send(daemon, method,
                value == null ? null : JSON.writeValueAsString(JSON.createObjectNode().put("value", value)));
    }

    private static Set<String> fieldNames(JsonNode node) {
        var names = new HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String status(UdsHttp.Response response) throws IOException {
        return JSON.readTree(response.body()).path("status").asText();
    }

    @Test
    @DisplayName("put, status, replace and delete through the recorded backend, no answer with the secret")
    void roundTrip() throws Exception {
        var macos = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
        var recorded = recordStore(base);
        var secret = UUID.randomUUID().toString();
        var values = new LinkedHashMap<String, Object>();
        values.put("mode", DaemonProcess.isNative() ? "native" : "jvm");
        try (var daemon = DaemonProcess.startReady(base)) {
            try {
                long start = System.nanoTime();
                var put = credential(daemon, "PUT", secret);
                values.put("put_us", (System.nanoTime() - start) / 1000);
                start = System.nanoTime();
                var read = credential(daemon, "GET", null);
                values.put("get_us", (System.nanoTime() - start) / 1000);
                var replace = credential(daemon, "PUT", secret + "-2");
                var replaced = credential(daemon, "GET", null);
                start = System.nanoTime();
                var delete = credential(daemon, "DELETE", null);
                values.put("delete_us", (System.nanoTime() - start) / 1000);
                var gone = credential(daemon, "GET", null);
                // A body the resource cannot read: the refusal must not repeat it
                var malformed = send(daemon, "PUT", "{\"value\":\"" + secret + "-3\"");
                var mistyped = send(daemon, "PUT", "{\"value\":{\"nested\":\"" + secret + "-4\"}}");
                var untouched = credential(daemon, "GET", null);

                assertEquals(204, put.status(), put.body() + daemon.output());
                assertEquals(200, read.status(), read.body() + daemon.output());
                var entry = JSON.readTree(read.body());
                var store = entry.path("store").asText();
                values.put("store", store);
                assertEquals(Set.of("status", "store"), fieldNames(entry), read.body());
                boolean roundTrip = "present".equals(status(read)) && replace.status() == 204
                        && replaced.status() == 200 && "present".equals(status(replaced))
                        && delete.status() == 204 && gone.status() == 200 && "not_found".equals(status(gone))
                        && store.equals(JSON.readTree(gone.body()).path("store").asText());
                values.put("round_trip", roundTrip);
                boolean secretFree = true;
                for (var response : List.of(put, read, replace, replaced, delete, gone, malformed, mistyped,
                        untouched)) {
                    secretFree &= !response.body().contains(secret);
                }
                values.put("secret_free", secretFree);
                var expectedStore = macos ? SecretStore.KEYCHAIN : SecretStore.SECRET_SERVICE;
                VerificationResults.write((macos ? "gate4-keychain-" : "gate5-secret-service-") + values.get("mode"),
                        values, roundTrip && secretFree && expectedStore.equals(store));

                assertTrue(roundTrip, values + " " + replaced.body() + " " + gone.status() + " " + gone.body());
                assertTrue(secretFree, "an answer contains the secret");
                assertEquals(400, malformed.status());
                assertEquals(400, mistyped.status());
                assertEquals("not_found", status(untouched), untouched.body());
                assertFalse(daemon.output().contains(secret), "the daemon output contains the secret");
                assertTrue(daemon.output().contains("service name '" + ServiceName.DEFAULT + "/"), daemon.output());
                if (macos) {
                    assertEquals(CredentialStoreChoice.Store.OS, recorded, daemon.output());
                    assertEquals(SecretStore.KEYCHAIN, store, daemon.output());
                }
            } finally {
                credential(daemon, "DELETE", null);
            }
        }
    }
}
