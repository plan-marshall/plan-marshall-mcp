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

import java.util.Map;


import de.cuioss.tools.logging.CuiLogger;
import de.planmarshall.runtime.credentials.CredentialAccount;
import de.planmarshall.runtime.credentials.CredentialStoreChoice;
import de.planmarshall.runtime.credentials.InvalidCredentialNameException;
import de.planmarshall.runtime.credentials.SecretStore;
import de.planmarshall.runtime.credentials.SecretStoreException;
import de.planmarshall.runtime.log.RuntimeLogMessages;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * The credential resource of the local API: {@code PUT} stores the secret of a global entry
 * ({@code 204}), {@code GET} answers the entry's status ({@code present} or {@code not_found}) with
 * the serving backend ({@code 200} in both cases: an absent entry is a status, not a refusal),
 * {@code DELETE} removes it ({@code 204}). A key outside {@code [a-zA-Z0-9._-]} is refused with
 * {@code 400}; a backend failure answers {@code 500} (or {@code 423} for a locked keyring) with the
 * error code. Before the onboarding recorded the credential store, every request is refused with
 * {@code 409 onboarding_incomplete} ({@link CredentialStoreChoice#activeStore()}).
 * <p>
 * No answer of this resource contains a secret: a secret enters the runtime through {@code PUT}
 * and never leaves it through the API, because the runtime token that authenticates a request is
 * held by every process of the user.
 */
@Path("/api/v1/credentials/{key}")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CredentialsResource {

    private static final CuiLogger LOGGER = new CuiLogger(CredentialsResource.class);
    private static final int LOCKED = 423;

    /**
     * The request body of {@code PUT}.
     *
     * @param value the secret
     */
    public record CredentialValue(String value) {
    }

    /**
     * The response body of {@code GET}: the status of an entry, never its secret.
     *
     * @param status {@link #PRESENT} or {@link #NOT_FOUND}
     * @param store  the serving backend: {@code keychain}, {@code secret-service} or {@code file}
     */
    public record CredentialStatus(String status, String store) {

        /** The entry is stored. */
        public static final String PRESENT = "present";
        /** No entry is stored under the key. */
        public static final String NOT_FOUND = "not_found";
    }

    private final SecretStore store;

    /**
     * @param store the active backend
     */
    public CredentialsResource(SecretStore store) {
        this.store = store;
    }

    /**
     * @param key  the credential key
     * @param body the secret
     * @return {@code 204}, or {@code 400} without a value
     */
    @PUT
    public RestResponse<Void> put(@PathParam("key") String key, CredentialValue body) {
        var account = CredentialAccount.global(key);
        if (body == null || body.value() == null) {
            return RestResponse.status(RestResponse.Status.BAD_REQUEST);
        }
        store.put(account, body.value());
        return RestResponse.noContent();
    }

    /**
     * @param key the credential key
     * @return {@code 200} with the status of the entry and the backend
     */
    @GET
    public RestResponse<CredentialStatus> status(@PathParam("key") String key) {
        // The backend's real read decides the presence; the secret it returns is dropped here
        boolean present = store.get(CredentialAccount.global(key)).isPresent();
        return RestResponse.ok(new CredentialStatus(
                present ? CredentialStatus.PRESENT : CredentialStatus.NOT_FOUND, store.name()));
    }

    /**
     * @param key the credential key
     * @return {@code 204}, whether or not the entry existed
     */
    @DELETE
    public RestResponse<Void> delete(@PathParam("key") String key) {
        store.delete(CredentialAccount.global(key));
        return RestResponse.noContent();
    }

    /**
     * @param e the refused name
     * @return {@code 400}
     */
    @ServerExceptionMapper
    public RestResponse<Map<String, String>> invalidName(InvalidCredentialNameException e) {
        return RestResponse.status(RestResponse.Status.BAD_REQUEST, Map.of("error", "invalid_credential_key"));
    }

    /**
     * @param e the credential store refused before the onboarding's store step
     * @return {@code 409} with {@code onboarding_incomplete}
     */
    @ServerExceptionMapper
    public RestResponse<Map<String, String>> storeNotChosen(CredentialStoreChoice.Refusal e) {
        return RestResponse.status(RestResponse.Status.CONFLICT, Map.of("error", e.reason().code()));
    }

    /**
     * @param e the backend failure
     * @return {@code 423} for a locked keyring, else {@code 500}, with the error code
     */
    @ServerExceptionMapper
    public RestResponse<Map<String, String>> storeFailed(SecretStoreException e) {
        LOGGER.error(e, RuntimeLogMessages.ERROR.CREDENTIAL_STORE_FAILED, store.name(), e.getMessage());
        int status = e.reason() == SecretStoreException.Reason.LOCKED ? LOCKED : 500;
        return RestResponse.ResponseBuilder.<Map<String, String>>create(status)
                .entity(Map.of("error", e.reason().code())).build();
    }
}
