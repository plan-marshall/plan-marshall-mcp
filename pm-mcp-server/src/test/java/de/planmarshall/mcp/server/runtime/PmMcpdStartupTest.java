/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;


import de.planmarshall.api.MachinePaths;
import de.planmarshall.core.service.AuditEvent;
import de.planmarshall.runtime.start.AuditChainVerification;
import de.planmarshall.runtime.start.StartupSequence;
import de.planmarshall.runtime.test.TestBases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Startup steps of pm-mcpd")
class PmMcpdStartupTest {

    private Path base;

    @BeforeEach
    void createBase() {
        base = TestBases.create("pmd");
    }

    @AfterEach
    void deleteBase() {
        TestBases.delete(base);
    }

    @Test
    @DisplayName("verifies no audit chain and starts for the running user")
    void shouldWireStartupSequence() throws Exception {
        var paths = new MachinePaths(base, MachinePaths.Os.current());

        var sequence = PmMcpd.startupSequence(paths);

        assertEquals(System.getProperty("user.name"), sequence.user());
        assertSame(AuditChainVerification.NONE, sequence.auditChain());
        var ready = assertInstanceOf(StartupSequence.Outcome.Ready.class, sequence.run(new SecureRandom()));
        try (var _ = ready.lock()) {
            assertInstanceOf(AuditChainVerification.Outcome.NotVerified.class, ready.verification());
            assertTrue(Files.exists(paths.runtimeToken()));
        }
    }

    @Test
    @DisplayName("refuses an audit record instead of dropping it")
    void shouldRefuseAuditRecord() {
        var event = new AuditEvent(StartupSequence.AUDIT_CHAIN_BROKEN, StartupSequence.CATEGORY_SECURITY,
                StartupSequence.OUTCOME_FAILED, Map.of());

        var thrown = assertThrows(UncheckedIOException.class, () -> PmMcpd.NO_AUDIT_STORE.append(event));

        assertTrue(thrown.getMessage().contains(StartupSequence.AUDIT_CHAIN_BROKEN), thrown.getMessage());
    }
}
