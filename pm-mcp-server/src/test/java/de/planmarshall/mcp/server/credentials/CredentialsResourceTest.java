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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import de.planmarshall.api.MachinePaths;
import de.planmarshall.runtime.credentials.CredentialAccount;
import de.planmarshall.runtime.credentials.CredentialStoreChoice;
import de.planmarshall.runtime.credentials.FakeSecretService;
import de.planmarshall.runtime.credentials.FileSecretStore;
import de.planmarshall.runtime.credentials.InvalidCredentialNameException;
import de.planmarshall.runtime.credentials.KeychainSecretStore;
import de.planmarshall.runtime.credentials.SecretServiceStore;
import de.planmarshall.runtime.credentials.SecretStore;
import de.planmarshall.runtime.credentials.SecretStoreException;
import de.planmarshall.runtime.credentials.dbus.FakeBus;
import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The resource methods against real stores; the HTTP binding and the authentication are supplied by
 * the local API listener (another slice). The answers are checked as the JSON the API sends.
 */
@EnableTestLogger
@DisplayName("CredentialsResource: status codes and bodies")
class CredentialsResourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "github";

    @TempDir
    Path base;

    private CredentialsResource resource;

    @BeforeEach
    void create() {
        resource = new CredentialsResource(new FileSecretStore(base.resolve("credentials")));
    }

    private static String json(Object entity) throws JsonProcessingException {
        return JSON.writeValueAsString(entity);
    }

    /**
     * Put, status, replace, status, delete, status through the resource; every answer is checked for
     * the two secrets.
     */
    private static void assertStatusNeverRevealsTheSecret(SecretStore store) throws JsonProcessingException {
        var resource = new CredentialsResource(store);
        var first = "first-" + UUID.randomUUID();
        var second = "second-" + UUID.randomUUID();
        var present = "{\"status\":\"present\",\"store\":\"" + store.name() + "\"}";
        var absent = "{\"status\":\"not_found\",\"store\":\"" + store.name() + "\"}";
        try {
            assertEquals(absent, json(resource.status(KEY).getEntity()));

            var put = resource.put(KEY, new CredentialsResource.CredentialValue(first));
            var stored = resource.status(KEY);
            var replace = resource.put(KEY, new CredentialsResource.CredentialValue(second));
            var replaced = resource.status(KEY);
            var delete = resource.delete(KEY);
            var gone = resource.status(KEY);

            assertEquals(204, put.getStatus());
            assertEquals(204, replace.getStatus());
            assertEquals(204, delete.getStatus());
            assertEquals(200, stored.getStatus());
            assertEquals(200, gone.getStatus());
            assertEquals(present, json(stored.getEntity()));
            assertEquals(present, json(replaced.getEntity()));
            assertEquals(absent, json(gone.getEntity()));
            for (RestResponse<?> response : List.of(put, stored, replace, replaced, delete, gone)) {
                var answer = json(response.getEntity());
                assertFalse(answer.contains(first) || answer.contains(second), answer);
            }
        } finally {
            store.delete(CredentialAccount.global(KEY));
        }
    }

    @Test
    @DisplayName("PUT 204, GET 200 present with the store, DELETE 204, GET 200 not_found")
    void lifecycle() {
        assertEquals(204, resource.put(KEY, new CredentialsResource.CredentialValue("tok")).getStatus());

        var entry = resource.status(KEY);
        assertEquals(200, entry.getStatus());
        assertEquals(new CredentialsResource.CredentialStatus("present", "file"), entry.getEntity());

        assertEquals(204, resource.delete(KEY).getStatus());
        assertEquals(204, resource.delete(KEY).getStatus());
        var gone = resource.status(KEY);
        assertEquals(200, gone.getStatus());
        assertEquals(new CredentialsResource.CredentialStatus("not_found", "file"), gone.getEntity());
    }

    @Test
    @DisplayName("PUT without value is 400")
    void missingValue() {
        assertEquals(400, resource.put(KEY, null).getStatus());
        assertEquals(400, resource.put(KEY, new CredentialsResource.CredentialValue(null)).getStatus());
    }

    @Test
    @DisplayName("an invalid key maps to 400 invalid_credential_key")
    void invalidKey() {
        var e = assertThrows(InvalidCredentialNameException.class, () -> resource.status("../x"));

        var response = resource.invalidName(e);
        assertEquals(400, response.getStatus());
        assertEquals(Map.of("error", "invalid_credential_key"), response.getEntity());
    }

    @Test
    @DisplayName("a locked keyring maps to 423, any other failure to 500, with the error code")
    void storeFailures() {
        var locked = resource.storeFailed(new SecretStoreException(SecretStoreException.Reason.LOCKED, "locked"));
        var invalid = resource.storeFailed(new SecretStoreException(SecretStoreException.Reason.INVALID, "bad"));

        assertEquals(423, locked.getStatus());
        assertEquals(Map.of("error", "credential_store_locked"), locked.getEntity());
        assertEquals(500, invalid.getStatus());
        assertEquals(Map.of("error", "credentials_file_invalid"), invalid.getEntity());
    }

    @Test
    @DisplayName("a credential before the store is chosen maps to 409 onboarding_incomplete")
    void storeNotChosen() {
        var choice = CredentialStoreChoice.of(new MachinePaths(base, MachinePaths.Os.current()));
        var refusal = assertThrows(CredentialStoreChoice.Refusal.class, choice::activeStore);

        var response = resource.storeNotChosen(refusal);

        assertEquals(409, response.getStatus());
        assertEquals(Map.of("error", "onboarding_incomplete"), response.getEntity());
    }

    @Nested
    @DisplayName("GET never returns the secret")
    class NoSecretRead {

        @Test
        @DisplayName("file store")
        void fileStore() throws Exception {
            assertStatusNeverRevealsTheSecret(new FileSecretStore(base.resolve("status")));
        }

        @Test
        @DisplayName("Secret Service (fake session bus)")
        void secretService() throws Exception {
            try (var _ = new FakeBus(base.resolve("bus"), 4242, new FakeSecretService())) {
                assertStatusNeverRevealsTheSecret(new SecretServiceStore(base.resolve("bus"), 4242,
                        "de.planmarshall.mcp", Duration.ofSeconds(5)));
            }
        }

        @Test
        @EnabledOnOs(OS.MAC)
        @DisplayName("macOS login keychain")
        void keychain() throws Exception {
            assertStatusNeverRevealsTheSecret(new KeychainSecretStore("de.planmarshall.mcp.test/" + UUID.randomUUID()));
        }

        @Test
        @DisplayName("a backend failure answers the error code only and logs no secret")
        void failureCarriesNoSecret() throws Exception {
            var secret = "secret-" + UUID.randomUUID();
            resource.put(KEY, new CredentialsResource.CredentialValue(secret));
            Files.writeString(base.resolve("credentials").resolve(KEY + ".json"),
                    "{\"format_version\":1,\"value\":\"" + secret + "\",\"extra\":true}");

            var e = assertThrows(SecretStoreException.class, () -> resource.status(KEY));
            var response = resource.storeFailed(e);

            assertEquals(500, response.getStatus());
            assertEquals("{\"error\":\"credentials_file_invalid\"}", json(response.getEntity()));
            assertFalse(e.getMessage().contains(secret), e.getMessage());
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.ERROR, secret);
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.ERROR, "PM_RUNTIME-201");
        }
    }
}
