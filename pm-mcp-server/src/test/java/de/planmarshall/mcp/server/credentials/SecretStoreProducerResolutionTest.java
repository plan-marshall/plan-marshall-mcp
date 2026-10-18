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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;


import de.planmarshall.core.service.AuditSink;
import de.planmarshall.runtime.credentials.CredentialAccount;
import de.planmarshall.runtime.credentials.CredentialStoreChoice;
import de.planmarshall.runtime.credentials.CredentialStoreChoiceFixture;
import de.planmarshall.runtime.credentials.FileSecretStore;
import de.planmarshall.runtime.credentials.SecretStore;
import de.planmarshall.runtime.lock.FileLockManager;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The store the producer gives is one object whose every operation resolves the recorded choice; a store move
 * between the credential stores therefore takes effect for the next operation. The OS store is a plain file store
 * here, so the test runs on every platform without a keyring.
 */
@DisplayName("SecretStoreProducer: the store resolves the recorded choice at every call")
class SecretStoreProducerResolutionTest {

    private static final String SERVICE = "de.planmarshall.mcp.test";

    /** The store recording under test appends no audit record; the audit log is not this test's subject. */
    private static final AuditSink NO_AUDIT = event -> {
    };

    @TempDir
    Path base;

    @Test
    @DisplayName("every operation delegates to the store recorded now, and the startup log runs its branch")
    void delegatesToTheRecordedStore() {
        var choice = CredentialStoreChoiceFixture.withOsStore(base, SERVICE,
                new FileSecretStore(base.resolve("credentials")));
        choice.record(new FileLockManager(NO_AUDIT), System.getProperty("user.name"),
                CredentialStoreChoice.Store.OS);
        var producer = new SecretStoreProducer();
        var store = producer.secretStore(choice);
        var account = CredentialAccount.global("github");

        assertEquals(SecretStore.KEYCHAIN, store.name());
        store.put(account, "secret");
        assertEquals(Optional.of("secret"), store.get(account));
        assertEquals(List.of(account), store.entries());
        assertTrue(store.delete(account));
        assertFalse(store.delete(account));

        producer.logRecordedStore(new StartupEvent(), choice);
    }
}
