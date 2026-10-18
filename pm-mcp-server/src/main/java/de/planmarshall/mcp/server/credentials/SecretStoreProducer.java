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

import java.util.List;
import java.util.Optional;


import de.cuioss.tools.logging.CuiLogger;
import de.planmarshall.mcp.server.runtime.RuntimeContext;
import de.planmarshall.runtime.credentials.CredentialAccount;
import de.planmarshall.runtime.credentials.CredentialStoreChoice;
import de.planmarshall.runtime.credentials.SecretStore;
import de.planmarshall.runtime.log.RuntimeLogMessages;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the one {@link CredentialStoreChoice} of the runtime and the {@link SecretStore} that serves the
 * recorded store.
 * <p>
 * The store is the operator's recorded choice ({@link CredentialStoreChoice#activeStore()}): the producer does not
 * resolve it at startup, since before the machine's onboarding recorded one every operation is refused with
 * {@code onboarding_incomplete}, and it resolves it at every call, since a move between the stores changes the
 * recorded store at runtime. The startup observer logs the recorded store when the operator recorded one and
 * nothing before the choice; a later recording is logged by {@link CredentialStoreChoice} itself.
 */
@ApplicationScoped
class SecretStoreProducer {

    private static final CuiLogger LOGGER = new CuiLogger(SecretStoreProducer.class);

    /** @param context the runtime instance, whose base the machine configuration is read below */
    @Produces
    @Singleton
    CredentialStoreChoice credentialStoreChoice(RuntimeContext context) {
        return CredentialStoreChoice.of(context.paths());
    }

    /** @param choice the recorded choice the store serves */
    @Produces
    @Singleton
    SecretStore secretStore(CredentialStoreChoice choice) {
        return new RecordedStore(choice);
    }

    /**
     * Logs the store the operator recorded, when one exists; before the choice nothing is logged, and no store is
     * touched.
     */
    void logRecordedStore(@Observes StartupEvent event, CredentialStoreChoice choice) {
        choice.recorded().ifPresent(store -> LOGGER.info(RuntimeLogMessages.INFO.CREDENTIAL_STORE_SELECTED,
                store.value(), choice.service()));
    }

    /**
     * The one active {@link SecretStore}: every call resolves {@link CredentialStoreChoice#activeStore()}, so a move
     * that changes the recorded store takes effect for the next operation. Before a store is recorded every call is
     * refused with {@code onboarding_incomplete}.
     */
    private record RecordedStore(CredentialStoreChoice choice) implements SecretStore {

        @Override
        public String name() {
            return choice.activeStore().name();
        }

        @Override
        public void put(CredentialAccount account, String secret) {
            choice.activeStore().put(account, secret);
        }

        @Override
        public Optional<String> get(CredentialAccount account) {
            return choice.activeStore().get(account);
        }

        @Override
        public boolean delete(CredentialAccount account) {
            return choice.activeStore().delete(account);
        }

        @Override
        public List<CredentialAccount> entries() {
            return choice.activeStore().entries();
        }
    }
}
