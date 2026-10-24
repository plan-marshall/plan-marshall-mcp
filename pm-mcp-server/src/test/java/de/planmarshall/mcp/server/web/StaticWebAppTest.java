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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The static handler reads the packaged app from a class path fixture: the entry document, the content types,
 * the cache directives, the SPA fallback, and the confinement that keeps every other file out.
 */
@DisplayName("Static web app from the resource JAR")
class StaticWebAppTest {

    private static final String RESOURCES = "META-INF/resources";
    private static final String INDEX_BODY = "<!doctype html><title>fixture app</title><script src=\"/_app/immutable/app.js\"></script>";

    @TempDir
    Path root;

    private URLClassLoader loader;
    private StaticWebApp app;

    @BeforeEach
    void createApp() throws IOException {
        var resources = root.resolve(RESOURCES);
        write(resources, "index.html", INDEX_BODY);
        write(resources, "_app/immutable/app.js", "console.log('fixture');");
        write(resources, "_app/immutable/nodes/0.js", "export {};");
        write(resources, "styles.css", "body { color: black; }");
        write(resources, "data.json", "{}");
        write(resources, "asset.map", "{}");
        write(resources, "brand-mark.svg", "<svg/>");
        write(resources, "icon.png", "png");
        write(resources, "favicon.ico", "ico");
        write(resources, "font.woff2", "woff2");
        write(resources, "notes.txt", "notes");
        write(resources, "BRAND", "no extension");
        loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, null);
        app = new StaticWebApp(loader);
    }

    @AfterEach
    void closeApp() throws IOException {
        loader.close();
    }

    private static void write(Path resources, String name, String body) throws IOException {
        var file = resources.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    private static Map<String, String> headers(StaticWebApp.Resource resource) {
        return Map.of("content-type", resource.contentType(), "cache-control", resource.cacheControl());
    }

    @Test
    @DisplayName("serves the entry document for the root, uncached")
    void shouldServeIndexForRoot() {
        var resource = app.resolve("/", false).orElseThrow();

        assertEquals(INDEX_BODY, resource.body().toString());
        assertEquals(Map.of("content-type", "text/html; charset=utf-8", "cache-control", "no-cache"),
                headers(resource));
        assertTrue(app.present());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/_app/immutable/app.js, text/javascript; charset=utf-8",
            "/_app/immutable/nodes/0.js, text/javascript; charset=utf-8",
            "/styles.css,            text/css; charset=utf-8",
            "/data.json,             application/json",
            "/asset.map,             application/json",
            "/brand-mark.svg,        image/svg+xml",
            "/icon.png,              image/png",
            "/favicon.ico,           image/vnd.microsoft.icon",
            "/font.woff2,            font/woff2",
            "/notes.txt,             text/plain; charset=utf-8",
            "/BRAND,                 application/octet-stream"})
    @DisplayName("answers a file with the content type of its extension")
    void shouldServeContentTypeOfExtension(String path, String contentType) {
        var resource = app.resolve(path, false).orElseThrow();

        assertEquals(contentType.strip(), resource.contentType());
    }

    @Test
    @DisplayName("caches a content-hashed asset as immutable and every other file not at all")
    void shouldCacheOnlyHashedAssets() {
        assertEquals(StaticWebApp.CACHE_IMMUTABLE, app.resolve("/_app/immutable/app.js", false).orElseThrow()
                .cacheControl());
        assertEquals(StaticWebApp.CACHE_IMMUTABLE, app.resolve("/_app/immutable/nodes/0.js", false).orElseThrow()
                .cacheControl());
        assertEquals(StaticWebApp.CACHE_NO_CACHE, app.resolve("/styles.css", false).orElseThrow().cacheControl());
        assertEquals(StaticWebApp.CACHE_NO_CACHE, app.resolve("/brand-mark.svg", false).orElseThrow().cacheControl());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/designer", "/plans/some-plan", "/settings/machine", "/onboarding"})
    @DisplayName("falls back to the entry document for an extensionless navigation")
    void shouldFallBackForExtensionlessNavigation(String path) {
        assertEquals(INDEX_BODY, app.resolve(path, false).orElseThrow().body().toString());
    }

    @Test
    @DisplayName("falls back to the entry document when the request accepts text/html")
    void shouldFallBackForHtmlAcceptHeader() {
        assertEquals(INDEX_BODY, app.resolve("/designer", true).orElseThrow().body().toString());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/missing.js", "/_app/immutable/missing.js", "/missing.css", "/missing.png"})
    @DisplayName("keeps a missing asset a 404 instead of answering it with the shell")
    void shouldKeepMissingAssetMissing(String path) {
        assertTrue(app.resolve(path, false).isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/../secret", "/_app/../../secret", "/.", "/..", "/a/./b", "/a//b",
            "/%2e%2e/secret", "/a%2fb", "/a\\b"})
    @DisplayName("refuses a path that could name a file outside the resource root")
    void shouldRefuseUnsafePath(String path) {
        assertTrue(app.resolve(path, false).isEmpty());
        assertTrue(app.resolve(path, true).isEmpty());
    }

    @Test
    @DisplayName("never serves or lists a directory, only the shell of a navigation")
    void shouldNeverServeDirectory() {
        assertEquals(INDEX_BODY, app.resolve("/_app", false).orElseThrow().body().toString());
        assertEquals(INDEX_BODY, app.resolve("/_app/", false).orElseThrow().body().toString());
        assertEquals(INDEX_BODY, app.resolve("/_app/immutable", true).orElseThrow().body().toString());
        assertEquals(INDEX_BODY, app.resolve("/_app/immutable/", true).orElseThrow().body().toString());
    }

    @Test
    @DisplayName("never serves a directory entry of the resource JAR, only the shell of a navigation")
    void shouldNeverServeDirectoryEntryOfAJar() throws Exception {
        var jar = root.resolve("app.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            entry(out, "META-INF/");
            entry(out, "META-INF/resources/");
            entry(out, "META-INF/resources/index.html", INDEX_BODY);
            entry(out, "META-INF/resources/_app/");
            entry(out, "META-INF/resources/_app/immutable/");
            entry(out, "META-INF/resources/_app/immutable/app.js", "console.log('jar');");
        }
        try (var jars = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            var fromJar = new StaticWebApp(jars);

            assertTrue(fromJar.present());
            assertEquals(INDEX_BODY, fromJar.resolve("/", false).orElseThrow().body().toString());
            assertEquals("console.log('jar');",
                    fromJar.resolve("/_app/immutable/app.js", false).orElseThrow().body().toString());
            assertEquals(INDEX_BODY, fromJar.resolve("/_app", false).orElseThrow().body().toString());
            assertEquals(INDEX_BODY, fromJar.resolve("/_app/", false).orElseThrow().body().toString());
            assertEquals(INDEX_BODY, fromJar.resolve("/_app/immutable", true).orElseThrow().body().toString());
            assertEquals(INDEX_BODY, fromJar.resolve("/_app/immutable/", true).orElseThrow().body().toString());
        }
    }

    private static void entry(JarOutputStream out, String name) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.closeEntry();
    }

    private static void entry(JarOutputStream out, String name, String body) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    @Test
    @DisplayName("reports an absent resource JAR and resolves nothing")
    void shouldReportAbsentApp() throws Exception {
        try (var empty = new URLClassLoader(new URL[0], null)) {
            var absent = new StaticWebApp(empty);

            assertFalse(absent.present());
            assertTrue(absent.resolve("/", false).isEmpty());
            assertTrue(absent.resolve("/designer", true).isEmpty());
        }
    }

    @Test
    @DisplayName("refuses a path that is not an absolute URL path")
    void shouldRefuseNonAbsolutePath() {
        assertTrue(app.resolve(null, false).isEmpty());
        assertTrue(app.resolve("index.html", false).isEmpty());
        assertTrue(app.resolve("", false).isEmpty());
    }

    @Test
    @DisplayName("the fixture tree holds no file below the resource root that is not reachable")
    void shouldReachEveryFixtureFile() throws Exception {
        try (Stream<Path> files = Files.walk(root.resolve(RESOURCES))) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var path = "/" + root.resolve(RESOURCES).relativize(file).toString().replace('\\', '/');
                assertTrue(app.resolve(path, false).isPresent(), path);
            }
        }
    }

    @Test
    @DisplayName("extracts the SHA-256 hash of an inline script from HTML")
    void shouldExtractInlineScriptHash() {
        var html = """
                <!doctype html>
                <html>
                  <head>
                    <script>console.log('hello');</script>
                  </head>
                </html>
                """;
        var hash = StaticWebApp.extractScriptHash(html);

        assertTrue(hash.isPresent());
        assertEquals(StaticWebApp.sha256Hash("console.log('hello');"), hash.get());
    }

    @Test
    @DisplayName("ignores external scripts with a src attribute and empty scripts")
    void shouldIgnoreExternalAndEmptyScripts() {
        var html = """
                <!doctype html>
                <html>
                  <head>
                    <script src="/_app/immutable/app.js"></script>
                    <script></script>
                    <script src="https://example.com/ext.js">ignored inline body</script>
                  </head>
                </html>
                """;
        assertTrue(StaticWebApp.extractScriptHash(html).isEmpty());
        assertTrue(StaticWebApp.extractScriptHash("<!doctype html><html></html>").isEmpty());
        assertTrue(StaticWebApp.extractScriptHash(null).isEmpty());
        assertTrue(StaticWebApp.extractScriptHash("   ").isEmpty());
    }

    @Test
    @DisplayName("computes inlineScriptHash for an app loaded from a class loader fixture")
    void shouldComputeInlineScriptHashForAppFixture() throws Exception {
        var inlineScript = "window.__boot = 42;";
        var customHtml = "<!doctype html><html><script>" + inlineScript + "</script></html>";
        var appDir = root.resolve("custom-app");
        write(appDir.resolve(RESOURCES), "index.html", customHtml);
        try (var customLoader = new URLClassLoader(new URL[]{appDir.toUri().toURL()}, null)) {
            var customApp = new StaticWebApp(customLoader);
            assertTrue(customApp.inlineScriptHash().isPresent());
            assertEquals(StaticWebApp.sha256Hash(inlineScript), customApp.inlineScriptHash().get());
        }
    }
}
