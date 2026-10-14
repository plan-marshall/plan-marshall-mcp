/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;


import de.planmarshall.api.MachinePaths;
import de.planmarshall.mcp.server.runtime.PmMcpd;
import de.planmarshall.runtime.start.RuntimeLock;
import de.planmarshall.runtime.start.StartupSequence;
import de.planmarshall.runtime.test.TestBases;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Gives every {@code @QuarkusTest} start a private, short {@code PM_MCP_BASE} and runs the startup steps before
 * the bind there, as the entry point {@code PmMcpd} does in production (which a {@code @QuarkusTest} does not
 * run). The base lies in {@code /tmp}: the module's {@code target/} path in a worktree is too long for the
 * {@code sun_path} limit of macOS.
 */
public class RuntimeBaseResource implements QuarkusTestResourceLifecycleManager {

    private Path base;
    private RuntimeLock lock;

    @Override
    public Map<String, String> start() {
        base = TestBases.create("pmq");
        var paths = new MachinePaths(base, MachinePaths.Os.current());
        try {
            var outcome = PmMcpd.startupSequence(paths).run(new SecureRandom());
            if (!(outcome instanceof StartupSequence.Outcome.Ready ready)) {
                throw new IllegalStateException("startup refused: " + outcome);
            }
            lock = ready.lock();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.of(PmMcpd.BASE_PROPERTY, base.toString(), PmMcpd.SOCKET_PROPERTY, paths.socket().toString());
    }

    @Override
    public void stop() {
        try {
            if (lock != null) {
                lock.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        TestBases.delete(base);
    }
}
