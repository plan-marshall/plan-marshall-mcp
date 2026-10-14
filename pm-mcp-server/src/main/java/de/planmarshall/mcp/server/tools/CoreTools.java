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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;


import de.cuioss.tools.logging.CuiLogger;
import de.planmarshall.runtime.log.RuntimeLogMessages;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;

/**
 * The ten core tools with their final flat input schemas, registered programmatically so that the emitted
 * {@code inputSchema} is exactly the schema file under {@code pm-mcp/tools/} (no {@code $schema}, no
 * {@code title}, {@code additionalProperties: false}). Annotated tools cannot express this: the generated
 * schema has no {@code additionalProperties}, {@code pattern} or {@code enum} control.
 * <p>
 * Each handler validates its arguments against its schema ({@link FlatSchemaValidator}) and answers a fixed
 * text: the handlers are stubs until the workflow engine exists.
 *
 * @since 0.1
 */
@ApplicationScoped
public class CoreTools {

    /** The core tool set (plan-marshall-documentation: doc/specification/mcp-tools/01-core-workflow-tools.adoc, Architectural Constraints). */
    public static final List<String> NAMES = List.of("pm_state", "pm_do", "pm_wait", "pm_plans", "pm_epics",
            "pm_build", "pm_lsp", "pm_skills", "pm_skill", "pm_skill_file");

    static final String SCHEMA_DIR = "pm-mcp/tools/";

    private static final CuiLogger LOGGER = new CuiLogger(CoreTools.class);

    private final ToolManager toolManager;

    /**
     * @param toolManager the tool registry of the MCP server
     */
    public CoreTools(ToolManager toolManager) {
        this.toolManager = toolManager;
    }

    /**
     * Registers the core tools after every other startup observer; a name another component registered already is
     * left alone and reported.
     *
     * @param event the startup event
     */
    void register(@Observes @Priority(Interceptor.Priority.APPLICATION + 1000) StartupEvent event) {
        var registered = new ArrayList<String>();
        for (var name : NAMES) {
            if (toolManager.getTool(name) != null) {
                LOGGER.warn(RuntimeLogMessages.WARN.CORE_TOOL_SHADOWED, name);
                continue;
            }
            var definition = load(name);
            var schema = definition.getJsonObject("inputSchema");
            var validator = new FlatSchemaValidator(schema);
            toolManager.newTool(name)
                    .setDescription(definition.getString("description"))
                    .setInputSchema(schema)
                    .setHandler(arguments -> validator.validate(arguments.args())
                            .map(violation -> ToolResponse.error("error_code: invalid_arguments\nparameter: "
                                    + violation.parameter() + "\nrule: " + violation.rule()))
                            .orElseGet(() -> ToolResponse.success(stubAnswer(name))))
                    .register();
            registered.add(name);
        }
        LOGGER.info(RuntimeLogMessages.INFO.CORE_TOOLS_REGISTERED, registered);
    }

    /**
     * @param name a core tool name
     * @return the fixed answer of its stub handler
     */
    static String stubAnswer(String name) {
        return "status: stub\ntool: " + name;
    }

    /**
     * @param name a core tool name
     * @return its description
     */
    public static String description(String name) {
        return load(name).getString("description");
    }

    /**
     * @param name a core tool name
     * @return its definition: {@code description} and {@code inputSchema}
     */
    static JsonObject load(String name) {
        try (var in = CoreTools.class.getClassLoader().getResourceAsStream(SCHEMA_DIR + name + ".json")) {
            if (in == null) {
                throw new IllegalStateException("missing tool schema " + SCHEMA_DIR + name + ".json");
            }
            return new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
