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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


import de.planmarshall.runtime.credentials.CredentialAccount;
import de.planmarshall.runtime.credentials.CredentialStoreChoice;
import de.planmarshall.runtime.credentials.SecretStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one {@link SecretStore} bean serves the store the operator recorded and resolves it at every call; before a
 * store is recorded every operation is refused with {@code onboarding_incomplete}. No test here writes through the
 * HTTP resource: on macOS that would reach the login keychain under the real service name.
 */
@QuarkusTest
@DisplayName("SecretStoreProducer in the application")
class SecretStoreProducerTest {

    @Inject
    SecretStore store;

    @Inject
    CredentialStoreChoice choice;

    @Test
    @DisplayName("no store is recorded before the onboarding's store step")
    void noStoreRecorded() {
        assertTrue(choice.recorded().isEmpty());
    }

    @Test
    @DisplayName("the store refuses with onboarding_incomplete before a store is recorded")
    void refusesBeforeTheChoice() {
        var name = assertThrows(CredentialStoreChoice.Refusal.class, store::name);
        assertEquals(CredentialStoreChoice.Refusal.Reason.ONBOARDING_INCOMPLETE, name.reason());

        var put = assertThrows(CredentialStoreChoice.Refusal.class,
                () -> store.put(CredentialAccount.global("github"), "secret"));
        assertEquals(CredentialStoreChoice.Refusal.Reason.ONBOARDING_INCOMPLETE, put.reason());
    }
}
