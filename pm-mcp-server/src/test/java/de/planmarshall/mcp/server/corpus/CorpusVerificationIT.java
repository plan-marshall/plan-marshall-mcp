/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.corpus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The corpus run of the question verification corpus (question-corpus-public-datasets).
 * <p>
 * Runs only in the {@code corpus} Maven profile of this module: it needs the network, so it is never part of
 * {@code verify} or a required check. For every manifest below {@code test/model/verification/sources} it checks
 * the pinned source the way the loader of {@code pm-mcp-core/corpus-sources} will fetch it — the repository at
 * its pinned commit through the git client, every file against its SHA-256 (a Git LFS file against its pointer
 * object id and size) — and parses the overlays the item generation reads.
 * <p>
 * A source that cannot be fetched or does not verify is reported by name, its items are skipped, and the run is
 * reported partial rather than failing: the corpus's own items are always there, so qualifying on a subset stays
 * accepted. A hash mismatch fails the run. Fetched datasets are never stored: hashes are checked on the fly and
 * only our manifests, overlays, and items live in the repository.
 * <p>
 * Hazards this answers: a silently unreachable source would generate an empty subset without naming it, and a
 * bit-rotted pin would generate items from content nobody reviewed. A run that never fetches proves nothing,
 * hence the partial report names every missing source, and a mismatch fails loudly.
 */
@DisplayName("Question corpus run over the pinned public sources")
class CorpusVerificationIT {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(120);

    private static final long MAX_DIRECT_BYTES = 128L * 1024L * 1024L;

    @Test
    @DisplayName("every pinned source verifies, or the run says which one is missing")
    void corpusRun() throws Exception {
        var root = corpusRoot();
        var sources = root.resolve("sources");
        assertTrue(Files.isDirectory(sources), "no sources directory below " + root);

        var mapper = new ObjectMapper();
        var missing = new ArrayList<String>();
        var mismatches = new ArrayList<String>();
        var verified = new ArrayList<String>();

        List<Path> manifests;
        try (Stream<Path> stream = Files.list(sources)) {
            manifests = stream.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        assertFalse(manifests.isEmpty(), "no source manifests below " + sources);

        for (var manifestPath : manifests) {
            var name = manifestPath.getFileName().toString().replace(".json", "");
            Map<String, Object> manifest;
            try {
                @SuppressWarnings("unchecked")
                var parsed = (Map<String, Object>) mapper.readValue(manifestPath.toFile(), Map.class);
                manifest = parsed;
            } catch (IOException error) {
                fail("source manifest " + name + " does not parse: " + error.getMessage());
                return;
            }
            var repository = string(manifest, "repository");
            var commit = string(manifest, "commit");
            assertNotNull(repository, "manifest " + name + " names no repository");
            assertNotNull(commit, "manifest " + name + " pins no commit");

            if (!pinResolves(repository, commit)) {
                missing.add(name + " (unreachable at its pin)");
                /*~~(TODO: Use CuiLogger. Suppress: // cui-rewrite:disable CuiLoggerStandardsRecipe)~~>*/System.out.println("corpus source " + name + ": MISSING, its items are skipped");
                continue;
            }
            var files = list(manifest, "files");
            var ok = true;
            for (var file : files) {
                var outcome = verifyFile(repository, commit, file);
                if (!outcome.verified()) {
                    ok = false;
                    mismatches.add(name + "/" + string(file, "path") + ": " + outcome.detail());
                }
            }
            if (ok) {
                verified.add(name);
                /*~~(TODO: Use CuiLogger. Suppress: // cui-rewrite:disable CuiLoggerStandardsRecipe)~~>*/System.out.println("corpus source " + name + ": verified (" + files.size() + " files)");
            }
        }

        var overlays = root.resolve("sources").resolve("overlay");
        if (Files.isDirectory(overlays)) {
            try (Stream<Path> stream = Files.list(overlays)) {
                for (var overlay : stream.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                    try {
                        mapper.readValue(overlay.toFile(), Map.class);
                    } catch (IOException error) {
                        fail("overlay " + overlay.getFileName() + " does not parse: " + error.getMessage());
                    }
                }
            }
        }

        if (!mismatches.isEmpty()) {
            fail("hash mismatch:\n" + String.join("\n", mismatches));
        }
        if (!missing.isEmpty()) {
            /*~~(TODO: Use CuiLogger. Suppress: // cui-rewrite:disable CuiLoggerStandardsRecipe)~~>*/System.out.println("CORPUS RUN PARTIAL: missing sources " + missing);
        } else {
            /*~~(TODO: Use CuiLogger. Suppress: // cui-rewrite:disable CuiLoggerStandardsRecipe)~~>*/System.out.println("CORPUS RUN COMPLETE: verified sources " + verified);
        }
    }

    /**
     * The corpus root: {@code test/model/verification} below the repository the build runs from. The failsafe
     * run of the {@code corpus} profile passes it as {@code pm.root}; anywhere else the working directory and
     * its parents are searched.
     */
    private static Path corpusRoot() {
        var rootProperty = System.getProperty("pm.root");
        if (rootProperty != null) {
            return Path.of(rootProperty, "test", "model", "verification");
        }
        var directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            var candidate = directory.resolve("test").resolve("model").resolve("verification");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("the corpus root is found neither below pm.root nor below the working directory");
    }

    /**
     * Whether the pinned commit resolves at the repository: {@code git ls-remote} through the git client.
     * Anything but a clean match — unreachable host, unknown commit, no git at all — reads as not resolving,
     * so the source is reported missing and the run stays partial instead of failing on the network.
     */
    private static boolean pinResolves(String repository, String commit) {
        try {
            var process = new ProcessBuilder("git", "ls-remote", repository, commit).redirectErrorStream(true)
                    .start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            var output = new String(process.getInputStream().readAllBytes());
            return process.exitValue() == 0 && output.contains(commit);
        } catch (IOException | InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record FileOutcome(boolean verified, String detail) {
    }

    /**
     * Checks one manifest file against its SHA-256. A Git LFS file is checked against its pointer object id
     * and size through the dataset tree, so the run never downloads the tens of megabytes; a plain file is
     * downloaded once and hashed. A file that cannot be fetched reads as unverified with its reason, and the
     * caller maps that onto the partial report — only a measured mismatch fails the run.
     */
    private static FileOutcome verifyFile(String repository, String commit, Map<String, Object> file) throws Exception {
        var path = string(file, "path");
        var expected = string(file, "sha256");
        if (path == null || expected == null) {
            return new FileOutcome(false, "manifest entry names no path or sha256");
        }
        var fileRepository = string(file, "repository") != null ? string(file, "repository") : repository;
        var fileCommit = string(file, "commit") != null ? string(file, "commit") : commit;
        var lfs = Boolean.TRUE.equals(file.get("lfs"));

        if (lfs) {
            return verifyLfsPointer(fileRepository, fileCommit, path, expected, file.get("size"));
        }
        var url = resolveUrl(fileRepository, fileCommit, path);
        if (url == null) {
            return new FileOutcome(false, "no download form for " + fileRepository);
        }
        byte[] body;
        try {
            body = download(url);
        } catch (IOException error) {
            return new FileOutcome(false, "cannot be fetched (" + error.getMessage() + ")");
        }
        var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        if (!actual.equalsIgnoreCase(expected)) {
            return new FileOutcome(false, "hash mismatch: expected " + expected + " but fetched " + actual);
        }
        return new FileOutcome(true, "");
    }

    private static FileOutcome verifyLfsPointer(String repository, String commit, String path, String expected,
            Object size) throws Exception {
        var api = treeUrl(repository, commit);
        if (api == null) {
            return new FileOutcome(false, "the repository has no dataset tree to read the pointer from");
        }
        List<Map<String, Object>> tree;
        try {
            var body = download(api);
            @SuppressWarnings("unchecked")
            var parsed = (List<Map<String, Object>>) new ObjectMapper().readValue(body, List.class);
            tree = parsed;
        } catch (IOException error) {
            return new FileOutcome(false, "cannot be fetched (" + error.getMessage() + ")");
        }
        for (var entry : tree) {
            if (path.equals(entry.get("path"))) {
                @SuppressWarnings("unchecked")
                var pointer = (Map<String, Object>) entry.get("lfs");
                if (pointer == null) {
                    return new FileOutcome(false, "the manifest calls it LFS but the tree holds a plain file");
                }
                var oid = String.valueOf(pointer.get("oid"));
                if (!oid.equalsIgnoreCase(expected)) {
                    return new FileOutcome(false,
                            "pointer mismatch: expected " + expected + " but the tree names " + oid);
                }
                if (size instanceof Number number
                        && pointer.get("size") instanceof Number actual
                        && number.longValue() != actual.longValue()) {
                    return new FileOutcome(false,
                            "pointer size mismatch: expected " + number + " but the tree names " + actual);
                }
                return new FileOutcome(true, "");
            }
        }
        return new FileOutcome(false, "the tree at the pin holds no " + path);
    }

    private static byte[] download(String url) throws IOException {
        try {
            var client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).followRedirects(
                    HttpClient.Redirect.NORMAL).build();
            var response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(HTTP_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            if (response.body().length > MAX_DIRECT_BYTES) {
                throw new IOException("refuses a direct download above " + MAX_DIRECT_BYTES + " bytes");
            }
            return response.body();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /**
     * The download URL of a dataset file at its pin. Hugging Face datasets resolve below
     * {@code /datasets/.../resolve/...}; anything else has no download form this run knows, so the file
     * reads as unverifiable rather than guessed at.
     */
    private static String resolveUrl(String repository, String commit, String path) {
        var marker = "huggingface.co/datasets/";
        var at = repository.indexOf(marker);
        if (at < 0) {
            return null;
        }
        return "https://huggingface.co/datasets/" + repository.substring(at + marker.length()) + "/resolve/"
                + commit + "/" + path;
    }

    private static String treeUrl(String repository, String commit) {
        var marker = "huggingface.co/datasets/";
        var at = repository.indexOf(marker);
        if (at < 0) {
            return null;
        }
        return "https://huggingface.co/api/datasets/" + repository.substring(at + marker.length()) + "/tree/"
                + commit;
    }

    private static String string(Map<String, Object> map, String key) {
        var value = map.get(key);
        return value instanceof String text ? text : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> map, String key) {
        var value = map.get(key);
        if (value instanceof List<?> items) {
            var typed = new ArrayList<Map<String, Object>>(items.size());
            for (var item : items) {
                if (item instanceof Map<?, ?> entry) {
                    typed.add((Map<String, Object>) entry);
                }
            }
            return typed;
        }
        return List.of();
    }
}
