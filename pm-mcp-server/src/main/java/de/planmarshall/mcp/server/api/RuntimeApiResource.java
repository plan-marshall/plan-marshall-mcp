/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.api;

import java.time.Duration;
import java.util.Map;


import com.fasterxml.jackson.annotation.JsonProperty;
import de.cuioss.tools.logging.CuiLogger;
import de.planmarshall.mcp.server.runtime.RuntimeContext;
import de.planmarshall.mcp.server.web.WebListener;
import de.planmarshall.runtime.log.RuntimeLogMessages;
import de.planmarshall.runtime.web.WebListenerConflictException;
import de.planmarshall.runtime.web.WebState;
import io.quarkus.runtime.Quarkus;
import io.smallrye.mutiny.Multi;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;

/**
 * The runtime resources of the local API {@code /api/v1}: status, the runtime-wide event stream, the web
 * access switch, and stop (plan-marshall-documentation: doc/specification/runtime-model/01-processes-and-transport.adoc, Local API).
 * Every request is authenticated by the path policy before it reaches a method.
 *
 * @since 0.1
 */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
public class RuntimeApiResource {

    private static final CuiLogger LOGGER = new CuiLogger(RuntimeApiResource.class);
    static final String CODE = "code";
    static final Duration HEARTBEAT = Duration.ofSeconds(1);
    static final long STOP_DELAY_MILLIS = 100;
    private static final String WEB_REQUEST = "PUT /api/v1/web";

    private final RuntimeContext context;
    private final WebListener webListener;
    private final Vertx vertx;

    /**
     * @param context     the runtime identity
     * @param webListener the web listener
     * @param vertx       the runtime's Vert.x instance, for the delayed stop
     */
    public RuntimeApiResource(RuntimeContext context, WebListener webListener, Vertx vertx) {
        this.context = context;
        this.webListener = webListener;
        this.vertx = vertx;
    }

    /**
     * The runtime status.
     *
     * @param version    the runtime version
     * @param pid        the process id
     * @param listener   the listener in use
     * @param socketPath the socket path
     * @param startedAt  the start time
     * @param web        the web access setting and listener state
     */
    public record Status(String version, long pid, String listener,
    @JsonProperty("socket_path") String socketPath, @JsonProperty("started_at") String startedAt,
    WebState web) {
    }

    /**
     * The web access request body.
     *
     * @param enabled whether web access is enabled
     * @param lan     whether the listener serves the LAN over TLS
     * @param port    the port, default {@value WebState#DEFAULT_PORT}; {@code 0} for any free port
     */
    public record WebSetting(Boolean enabled, Boolean lan, Integer port) {
    }

    /** @return the runtime status */
    @GET
    @Path("status")
    public Status status() {
        return new Status(context.version(), context.pid(), RuntimeContext.LISTENER_UNIX,
                context.paths().socket().toString(), context.startedAt().toString(), webListener.state());
    }

    /**
     * The runtime-wide event stream: one {@code heartbeat} at once, then one per second, each flushed as
     * it is sent.
     *
     * @param sse the SSE context
     * @return the event stream
     */
    @GET
    @Path("events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> events(@Context Sse sse) {
        var sequence = Multi.createBy().concatenating().streams(Multi.createFrom().item(0L),
                Multi.createFrom().ticks().every(HEARTBEAT).map(tick -> tick + 1));
        return sequence
                .map(seq -> sse.newEventBuilder()
                        .id(String.valueOf(seq))
                        .name("heartbeat")
                        .data(String.class, "{\"seq\":" + seq + "}")
                        .build());
    }

    /**
     * Opens or closes the web listener; CLI-only, so refused on the web listener itself.
     *
     * @param setting the requested setting
     * @param request the HTTP request, for the listener it arrived on
     * @return {@code 200} with the {@code web} object once the listener opened or closed; while the listener is
     *         open its {@code port} is the port the listener bound, in the answer to a close it is the port of
     *         the request and names no listener
     */
    @PUT
    @Path("web")
    public Response web(WebSetting setting, @Context HttpServerRequest request) {
        if (webListener.serves(request.connection())) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of(CODE, "cli_only")).build();
        }
        if (setting == null || setting.enabled() == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of(CODE, "invalid_arguments")).build();
        }
        var port = setting.port() == null ? WebState.DEFAULT_PORT : setting.port();
        try {
            return Response.ok(webListener.apply(setting.enabled(), Boolean.TRUE.equals(setting.lan()), port,
                    WEB_REQUEST))
                    .build();
        } catch (WebListenerConflictException e) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of(CODE, WebListenerConflictException.CODE))
                    .build();
        }
    }

    /**
     * Stops the runtime: answers {@code 202}, then exits with code {@code 0} shortly after.
     *
     * @return {@code 202 Accepted}
     */
    @POST
    @Path("runtime/stop")
    public Response stop() {
        LOGGER.info(RuntimeLogMessages.INFO.RUNTIME_STOP_REQUESTED);
        vertx.setTimer(STOP_DELAY_MILLIS, _ -> Quarkus.asyncExit(0));
        return Response.accepted().build();
    }
}
