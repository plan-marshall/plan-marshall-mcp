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

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;


import de.planmarshall.api.MachinePaths;
import de.planmarshall.core.service.AuditSink;
import de.planmarshall.runtime.start.AuditChainVerification;
import de.planmarshall.runtime.start.NativeLibraryPath;
import de.planmarshall.runtime.start.StartupSequence;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Entry point of {@code pm-mcpd}: runs the startup steps before the bind ({@link StartupSequence}), hands the
 * socket path to Quarkus, and starts it.
 * <p>
 * The socket path is computed at runtime from {@code PM_MCP_BASE} and passed as the system property
 * {@code quarkus.http.domain-socket}, which Quarkus reads when it creates the HTTP server options. The base is
 * passed as {@code pm.mcp.base}, so the beans use the base this process locked.
 *
 * @since 0.1
 */
@QuarkusMain
public final class PmMcpd {

    /** Configuration key of the machine root as used by the beans. */
    public static final String BASE_PROPERTY = "pm.mcp.base";

    /** Quarkus configuration key of the domain socket path. */
    public static final String SOCKET_PROPERTY = "quarkus.http.domain-socket";

    /**
     * The audit sink while the runtime has no audit store: it refuses every record instead of dropping it, so the
     * start sequence logs a record it could not hand over and goes on.
     */
    static final AuditSink NO_AUDIT_STORE = event -> {
        throw new UncheckedIOException(new IOException("no audit store, record '" + event.event() + "' not appended"));
    };

    private PmMcpd() {
    }

    /**
     * The startup steps before the bind for the running user. The runtime has no audit trail yet: there is no chain
     * to verify ({@link AuditChainVerification#NONE}) and no store to hand a record to ({@link #NO_AUDIT_STORE}).
     *
     * @param paths the machine paths of the runtime
     * @return the startup sequence
     */
    public static StartupSequence startupSequence(MachinePaths paths) {
        return new StartupSequence(paths, System.getProperty("user.name"), AuditChainVerification.NONE, NO_AUDIT_STORE);
    }

    /**
     * @param args the command-line arguments, passed to Quarkus
     * @throws IOException if the singleton lock cannot be released on shutdown
     */
    public static void main(String... args) throws IOException {
        var paths = MachinePaths.current();
        StartupSequence.Outcome outcome;
        try {
            outcome = startupSequence(paths).run(new SecureRandom());
        } catch (IOException e) {
            outcome = new StartupSequence.Outcome.Refused(e.toString());
        }
        switch (outcome) {
            case StartupSequence.Outcome.LockHeld _ -> System.exit(0);
            case StartupSequence.Outcome.Refused refused -> {
                stderr().println("pm-mcpd: " + refused.diagnostic());
                System.exit(StartupSequence.EXIT_REFUSED);
            }
            case StartupSequence.Outcome.Ready ready -> {
                // The lock is held while Quarkus runs (until shutdown) and released when it returns.
                try (var _ = ready.lock()) {
                    System.setProperty(BASE_PROPERTY, paths.base().toString());
                    System.setProperty(SOCKET_PROPERTY, paths.socket().toString());
                    NativeLibraryPath.includeExecutableDirectory();
                    Quarkus.run(args);
                }
            }
        }
    }

    // The daemon's only direct write to stderr: the refusal diagnostic before Quarkus (and its logging) runs.
    private static PrintStream stderr() {
        return new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
    }
}
