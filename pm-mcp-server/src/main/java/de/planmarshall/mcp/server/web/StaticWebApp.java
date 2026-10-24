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

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


import io.vertx.core.buffer.Buffer;

/**
 * The static web application {@code pm-web-app}, read from the classpath of the daemon: the resource JAR of the
 * clients repository carries its built SvelteKit SPA below {@value #ROOT}
 * (plan-marshall-documentation: doc/specification/cli-and-security/03-web-server.adoc, Web App).
 * <p>
 * Only files of that resource root are reachable; a request path is refused when it could name anything else
 * (backslash, percent-encoding, empty or dot segments), and a directory is never served or listed. The entry
 * document {@value #INDEX} is served for navigation, the content-hashed files below
 * {@value #IMMUTABLE_PREFIX} for a year, every other file without caching.
 * <p>
 * An absolute file of the resource root is returned as bytes with the content type of its extension, so the
 * handler that sends it stays independent of the SPA and of the file system layout.
 *
 * @since 0.1
 */
final class StaticWebApp {

    /** The resource root the clients' resource JAR packages the built app into. */
    static final String ROOT = "META-INF/resources";

    /** The entry document and the shell of the single-page application. */
    static final String INDEX = "index.html";

    /** The prefix of the content-hashed Vite assets. */
    static final String IMMUTABLE_PREFIX = "_app/immutable/";

    /** The cache directive of the entry document and every file that is not content-hashed. */
    static final String CACHE_NO_CACHE = "no-cache";

    /** The cache directive of a content-hashed asset: its name changes with its content. */
    static final String CACHE_IMMUTABLE = "public, max-age=31536000, immutable";

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final String HTML_CONTENT_TYPE = "text/html; charset=utf-8";
    private static final String REQUEST_PATH_SEPARATOR = "/";
    private static final String URL_PATH_SEPARATOR = "/";
    private static final String WINDOWS_SEPARATOR = "\\";
    private static final String PERCENT = "%";
    private static final String DOT = ".";
    private static final String PARENT = "..";
    private static final String CURRENT = ".";
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", HTML_CONTENT_TYPE),
            Map.entry("htm", HTML_CONTENT_TYPE),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("map", "application/json"),
            Map.entry("webmanifest", "application/manifest+json"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("ico", "image/vnd.microsoft.icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("xml", "application/xml"),
            Map.entry("wasm", "application/wasm"));

    private final ClassLoader loader;
    private final boolean present;

    /**
     * @param loader the class loader that sees the resource JAR
     */
    StaticWebApp(ClassLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.present = loader.getResource(ROOT + URL_PATH_SEPARATOR + INDEX) != null;
    }

    /**
     * @return the app as the class loader of the running application sees it
     */
    static StaticWebApp fromContextClassLoader() {
        var context = Thread.currentThread().getContextClassLoader();
        return new StaticWebApp(context != null ? context : StaticWebApp.class.getClassLoader());
    }

    /**
     * One file of the app, ready to be sent.
     *
     * @param body         the file's bytes as the response buffer
     * @param contentType  the content type of its extension
     * @param cacheControl the cache directive of its path
     */
    record Resource(Buffer body, String contentType, String cacheControl) {
    }

    /**
     * @return {@code true} if the resource JAR is on the classpath and carries the entry document
     */
    boolean present() {
        return present;
    }

    /**
     * Resolves a request path to the file to serve.
     * <p>
     * An existing file wins. A missing file falls back to the entry document only when the request can be a
     * navigation: a path without a file extension, or a request that accepts {@code text/html}. A missing
     * asset keeps its {@code 404}, so a broken script or stylesheet is not answered with the app shell.
     *
     * @param path        the URL path of the request, starting with {@code /}
     * @param acceptsHtml whether the request names {@code text/html} in its {@code Accept} header
     * @return the file to serve, or empty when the path is refused or nothing matches
     */
    Optional<Resource> resolve(String path, boolean acceptsHtml) {
        var name = resourceName(path);
        if (name == null) {
            return Optional.empty();
        }
        if (name.isEmpty()) {
            return resource(INDEX);
        }
        var existing = resource(name);
        if (existing.isPresent()) {
            return existing;
        }
        return navigable(name, acceptsHtml) ? resource(INDEX) : Optional.empty();
    }

    /**
     * @param path the URL path of a request
     * @return the name below the resource root, empty for the root itself, or {@code null} for a refused path
     */
    private static String resourceName(String path) {
        if (path == null || !path.startsWith(REQUEST_PATH_SEPARATOR)
                || path.contains(WINDOWS_SEPARATOR) || path.contains(PERCENT)) {
            return null;
        }
        var name = path.substring(1);
        if (name.isEmpty()) {
            return name;
        }
        if (name.contains(REQUEST_PATH_SEPARATOR + REQUEST_PATH_SEPARATOR)) {
            return null;
        }
        if (name.endsWith(REQUEST_PATH_SEPARATOR)) {
            name = name.substring(0, name.length() - 1);
        }
        for (var segment : name.split(REQUEST_PATH_SEPARATOR, -1)) {
            if (segment.isEmpty() || CURRENT.equals(segment) || PARENT.equals(segment)) {
                return null;
            }
        }
        return name;
    }

    /**
     * @param name        the name below the resource root
     * @param acceptsHtml whether the request accepts {@code text/html}
     * @return {@code true} if the entry document may answer the request
     */
    private static boolean navigable(String name, boolean acceptsHtml) {
        return acceptsHtml || !hasExtension(name);
    }

    /**
     * @param name the name below the resource root
     * @return {@code true} if the last segment carries a file extension
     */
    private static boolean hasExtension(String name) {
        var last = name.substring(name.lastIndexOf(REQUEST_PATH_SEPARATOR) + 1);
        var dot = last.lastIndexOf(DOT);
        return dot > 0 && dot < last.length() - 1;
    }

    /**
     * @param name the name below the resource root
     * @return the file, or empty when it does not exist, is a directory, or cannot be read
     */
    private Optional<Resource> resource(String name) {
        if (name.endsWith(REQUEST_PATH_SEPARATOR)) {
            return Optional.empty();
        }
        var url = loader.getResource(ROOT + URL_PATH_SEPARATOR + name);
        if (url == null || !isFile(url)) {
            return Optional.empty();
        }
        try (var in = url.openStream()) {
            return Optional.of(new Resource(Buffer.buffer(in.readAllBytes()), contentType(name),
                    cacheControl(name)));
        } catch (IOException _) {
            return Optional.empty();
        }
    }

    /**
     * A directory is not a file, and a classpath directory must not be listed. A JAR entry that names a
     * directory ends with {@code /} and is rejected before this check; an exploded classpath directory has no
     * such name, so its file system type decides.
     *
     * @param url the URL a class loader returned
     * @return {@code true} if the URL names a regular file
     */
    private static boolean isFile(URL url) {
        if (!"file".equals(url.getProtocol())) {
            return true;
        }
        try {
            return Files.isRegularFile(Path.of(url.toURI()));
        } catch (URISyntaxException | IllegalArgumentException _) {
            return false;
        }
    }

    /**
     * @param name the name below the resource root
     * @return the content type of its extension, or {@value #DEFAULT_CONTENT_TYPE}
     */
    private static String contentType(String name) {
        var last = name.substring(name.lastIndexOf(REQUEST_PATH_SEPARATOR) + 1);
        var dot = last.lastIndexOf(DOT);
        return dot < 0 ? DEFAULT_CONTENT_TYPE
                : CONTENT_TYPES.getOrDefault(last.substring(dot + 1).toLowerCase(Locale.ROOT), DEFAULT_CONTENT_TYPE);
    }

    /**
     * @param name the name below the resource root
     * @return {@value #CACHE_IMMUTABLE} for a content-hashed asset, otherwise {@value #CACHE_NO_CACHE}
     */
    private static String cacheControl(String name) {
        return name.startsWith(IMMUTABLE_PREFIX) ? CACHE_IMMUTABLE : CACHE_NO_CACHE;
    }
}
