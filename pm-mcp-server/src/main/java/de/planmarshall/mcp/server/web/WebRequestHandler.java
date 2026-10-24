/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.web;

import java.net.Inet6Address;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;


import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;

/**
 * The request handler of the web listener: {@code Host} validation, the Origin check on state-changing
 * requests, the security headers on every response, and the route restriction to {@code /api/v1/} and the
 * static web app (plan-marshall-documentation: doc/specification/cli-and-security/03-web-server.adoc, Request Validation & Security
 * Headers, API Access). {@code /api/v1/} is handed to the runtime's own HTTP root handler, so both listeners
 * share one router and one security chain; {@code /mcp} and every other path never reach it.
 * <p>
 * A {@code GET} or {@code HEAD} outside {@code /api/} is answered by the packaged {@code pm-web-app} through
 * {@link StaticWebApp}: an existing file is streamed, a navigation without a matching file receives the app
 * shell, and a missing asset stays {@code 404}. A build without the resource JAR keeps the placeholder, so the
 * listener still opens and names the missing app.
 *
 * @since 0.1
 */
final class WebRequestHandler implements Handler<HttpServerRequest> {

    static final String API_PREFIX = "/api/v1/";

    /** The security headers sent on every response of the web listener. */
    static final Map<String, String> SECURITY_HEADERS = Map.of(
            "Content-Security-Policy", "default-src 'self'; script-src 'self'; connect-src 'self'; "
            + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; "
            + "require-trusted-types-for 'script'",
            "X-Frame-Options", "DENY",
            "X-Content-Type-Options", "nosniff",
            "Referrer-Policy", "no-referrer");

    /** Placeholder of the static web app {@code pm-web-app}, served for every {@code GET} outside {@code /api/}. */
    static final String APP_PLACEHOLDER = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><title>pm-mcpd</title></head>
            <body><p>The web app is not part of this build.</p></body></html>
            """;

    private static final String NO_AUTHORITY = "";
    private static final String SCHEME_SEPARATOR = "://";
    private static final Set<HttpMethod> STATE_CHANGING = Set.of(HttpMethod.POST, HttpMethod.PUT,
            HttpMethod.PATCH, HttpMethod.DELETE);
    private static final Pattern NOT_NORMALIZED = Pattern.compile(
            "(?i)%2f|%5c|%2e|\\\\|//|/\\.\\.?(/|$)");

    private final Set<String> hosts;
    private final Set<String> origins;
    private final Handler<HttpServerRequest> api;
    private final StaticWebApp app;

    /**
     * @param hosts   the accepted {@code Host} values ({@code host:port}), as {@link #authority(String)} gives them
     * @param origins the listener's own origins ({@code scheme://host:port}), as {@link #origin(String)} gives
     *                them
     * @param api     the runtime's HTTP root handler serving {@code /api/v1/}
     * @param app     the static web app served outside {@code /api/}
     */
    WebRequestHandler(Set<String> hosts, Set<String> origins, Handler<HttpServerRequest> api, StaticWebApp app) {
        this.hosts = Set.copyOf(hosts);
        this.origins = Set.copyOf(origins);
        this.api = api;
        this.app = app;
    }

    @Override
    public void handle(HttpServerRequest request) {
        var response = request.response();
        SECURITY_HEADERS.forEach(response::putHeader);
        var host = request.getHeader(HttpHeaders.HOST);
        if (host == null || !hosts.contains(authority(host))) {
            response.setStatusCode(403).end();
            return;
        }
        var method = request.method();
        if (HttpMethod.OPTIONS.equals(method)) {
            response.setStatusCode(403).end();
            return;
        }
        var origin = request.getHeader(HttpHeaders.ORIGIN);
        if (STATE_CHANGING.contains(method) && origin != null
                && !origins.contains(origin(origin))) {
            response.setStatusCode(403).end();
            return;
        }
        var path = request.path();
        if (path == null || NOT_NORMALIZED.matcher(path).find()) {
            response.setStatusCode(400).end();
            return;
        }
        if (path.startsWith(API_PREFIX)) {
            api.handle(request);
        } else if ((HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method))
                && !path.startsWith("/api/") && !isMcp(path)) {
            serveApp(request, path);
        } else {
            response.setStatusCode(404).end();
        }
    }

    /**
     * Serves the packaged app, its shell for a navigation, or {@code 404} for a missing asset. Without the
     * resource JAR the placeholder answers every path, so the listener stays usable and says what is missing.
     *
     * @param request the request
     * @param path    its validated URL path
     */
    private void serveApp(HttpServerRequest request, String path) {
        var response = request.response();
        if (!app.present()) {
            response.putHeader(HttpHeaders.CONTENT_TYPE, "text/html; charset=utf-8").end(APP_PLACEHOLDER);
            return;
        }
        var found = app.resolve(path, acceptsHtml(request));
        if (found.isEmpty()) {
            response.setStatusCode(404).end();
            return;
        }
        var file = found.get();
        response.putHeader(HttpHeaders.CONTENT_TYPE, file.contentType())
                .putHeader(HttpHeaders.CACHE_CONTROL, file.cacheControl())
                .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(file.body().length()));
        if (HttpMethod.HEAD.equals(request.method())) {
            response.end();
        } else {
            response.end(file.body());
        }
    }

    /**
     * @param request a request
     * @return {@code true} if its {@code Accept} header names {@code text/html}, the browser's navigation
     */
    private static boolean acceptsHtml(HttpServerRequest request) {
        var accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("text/html");
    }

    /**
     * The form in which a {@code Host} value is compared. An IPv6 literal is compared by its address, since one
     * address has many spellings (a browser sends the compressed one, a certificate lists the full one); a
     * name and an IPv4 address are compared in lower case.
     *
     * @param authority {@code host} or {@code host:port}, an IPv6 address in brackets
     * @return the form to compare; a value no listener accepts for a malformed, scoped or IPv4-mapped literal
     */
    static String authority(String authority) {
        var value = authority.toLowerCase(Locale.ROOT);
        if (!value.startsWith("[")) {
            return value;
        }
        var end = value.indexOf(']');
        if (end < 0 || value.indexOf('%') >= 0) {
            return NO_AUTHORITY;
        }
        try {
            return Inet6Address.ofLiteral(value.substring(1, end)) instanceof Inet6Address address
                    ? "[" + HexFormat.of().formatHex(address.getAddress()) + "]" + value.substring(end + 1)
                    : NO_AUTHORITY;
        } catch (IllegalArgumentException _) {
            return NO_AUTHORITY;
        }
    }

    /**
     * @param origin an {@code Origin} value, {@code scheme://host:port}
     * @return the form to compare, its host as {@link #authority(String)} gives it
     */
    static String origin(String origin) {
        var separator = origin.indexOf(SCHEME_SEPARATOR);
        return separator < 0 ? origin.toLowerCase(Locale.ROOT)
                : origin.substring(0, separator + SCHEME_SEPARATOR.length()).toLowerCase(Locale.ROOT)
                + authority(origin.substring(separator + SCHEME_SEPARATOR.length()));
    }

    private static boolean isMcp(String path) {
        return "/mcp".equals(path) || path.startsWith("/mcp/");
    }
}
