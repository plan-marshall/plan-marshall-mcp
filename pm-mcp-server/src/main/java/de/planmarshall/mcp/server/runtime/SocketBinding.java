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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;


import de.cuioss.tools.logging.CuiLogger;
import de.planmarshall.runtime.log.RuntimeLogMessages;
import de.planmarshall.runtime.start.PosixModes;
import de.planmarshall.runtime.start.StartupSequence;
import io.quarkus.runtime.Quarkus;
import io.quarkus.vertx.http.DomainSocketServerStart;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;

/**
 * Startup steps 6 and 7 after Quarkus bound the socket: restrict the socket to mode {@code 0600}, write the
 * runtime record {@code state/runtime.json}, and report readiness.
 * <p>
 * Vert.x creates the socket under the process umask (typically {@code 0755} on macOS with umask {@code 022});
 * Java cannot set the umask, so the mode is set right after the bind. Until then the {@code 0700} directory
 * {@code run/} keeps every other user out. Quarkus fires {@link DomainSocketServerStart} after the bind,
 * whereas {@code StartupEvent} observers run before the HTTP server binds.
 *
 * @since 0.1
 */
@ApplicationScoped
public class SocketBinding {

    private static final CuiLogger LOGGER = new CuiLogger(SocketBinding.class);

    private final RuntimeContext context;

    /**
     * @param context the runtime identity
     */
    public SocketBinding(RuntimeContext context) {
        this.context = context;
    }

    void onBound(@ObservesAsync DomainSocketServerStart start) {
        var socket = context.paths().socket();
        try {
            secure(socket);
            writeRecord();
            LOGGER.info(RuntimeLogMessages.INFO.RUNTIME_READY, socket, context.pid());
        } catch (IOException e) {
            LOGGER.error(e, RuntimeLogMessages.ERROR.SOCKET_NOT_SECURED, socket);
            Quarkus.asyncExit(StartupSequence.EXIT_REFUSED);
        }
    }

    /**
     * Restricts the socket to mode {@code 0600} and verifies the result.
     *
     * @param socket the bound socket
     * @throws IOException if the mode cannot be set or does not hold afterwards
     */
    static void secure(Path socket) throws IOException {
        var mode = Files.getPosixFilePermissions(socket, LinkOption.NOFOLLOW_LINKS);
        if (!mode.equals(PosixModes.FILE)) {
            LOGGER.warn(RuntimeLogMessages.WARN.SOCKET_MODE_RESTRICTED, socket, PosixModes.octal(mode));
            Files.setPosixFilePermissions(socket, PosixModes.FILE);
        }
        var after = Files.getPosixFilePermissions(socket, LinkOption.NOFOLLOW_LINKS);
        if (!after.equals(PosixModes.FILE)) {
            throw new IOException("socket mode is %s after chmod".formatted(PosixModes.octal(after)));
        }
    }

    /**
     * Writes {@code state/runtime.json} atomically with mode {@code 0600}.
     *
     * @throws IOException if the record cannot be written
     */
    void writeRecord() throws IOException {
        var paths = context.paths();
        var record = new JsonObject()
                .put("pid", context.pid())
                .put("start_instant", ProcessHandle.current().info().startInstant().map(Object::toString)
                        .orElse(null))
                .put("version", context.version())
                .put("listener", RuntimeContext.LISTENER_UNIX)
                .put("socket_path", paths.socket().toString())
                .put("started_at", context.startedAt().toString());
        var target = paths.runtimeRecord();
        var tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.write(Files.createFile(tmp, PosixModes.fileAttribute()),
                record.encode().getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE,
                StandardOpenOption.SYNC);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
