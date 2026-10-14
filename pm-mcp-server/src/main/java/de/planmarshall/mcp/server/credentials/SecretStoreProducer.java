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

import de.cuioss.tools.logging.CuiLogger;
import de.planmarshall.runtime.credentials.SecretStore;
import de.planmarshall.runtime.credentials.SecretStoreSelector;
import de.planmarshall.runtime.log.RuntimeLogMessages;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the one active {@link SecretStore}, selected when the runtime starts: the startup observer
 * injects the store, which creates the singleton (and runs the selection) before the runtime serves requests.
 */
@ApplicationScoped
class SecretStoreProducer {

    private static final CuiLogger LOGGER = new CuiLogger(SecretStoreProducer.class);

    @Produces
    @Singleton
    SecretStore secretStore() {
        var selection = SecretStoreSelector.select();
        selection.fallbackReason().ifPresent(reason -> LOGGER.warn(RuntimeLogMessages.WARN.KEYRING_UNAVAILABLE, reason));
        LOGGER.info(RuntimeLogMessages.INFO.CREDENTIAL_STORE_SELECTED, selection.store().name(), selection.service());
        return selection.store();
    }

    void selectAtStart(@Observes StartupEvent event, SecretStore store) {
        LOGGER.debug("Credential store %s active", store.name());
    }
}
