/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.tools;

import de.cuioss.tools.logging.CuiLogger;
import de.planmarshall.runtime.log.RuntimeLogMessages;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

/**
 * Minimal MCP tool proving the tool surface end to end.
 *
 * @since 0.1
 */
public class HelloTool {

    private static final CuiLogger LOGGER = new CuiLogger(HelloTool.class);

    /**
     * Greets the given name.
     *
     * @param name the name to greet
     * @return the greeting
     */
    @Tool(description = "Returns a greeting for the given name.")
    public String hello(@ToolArg(description = "The name to greet") String name) {
        LOGGER.info(RuntimeLogMessages.INFO.HELLO_TOOL_INVOKED, name);
        return "Hello, " + name + "!";
    }
}
