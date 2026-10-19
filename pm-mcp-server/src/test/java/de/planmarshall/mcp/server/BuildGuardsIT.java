/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Controls of the build guards of this repository (PM-IMPL-1): each fixture project below
 * {@code src/guard-controls} breaks one rule of the root POM, and its build must fail in the enforcer
 * execution of that rule.
 * <p>
 * Hazards the guards answer: {@code pm-e2e} and {@code pm-conformance} reach the product through its built
 * binaries alone. A class of {@code pm-mcp-server} on the classpath of {@code pm-e2e} lets a test pass against
 * the code of the build where the release layout would fail; any Maven dependency of {@code pm-conformance} on
 * the assembly ties the out-of-band harness to the regular build. A guard that never fails proves nothing, hence
 * these controls. The test lives in this module because its integration tests are the ones the CI job
 * {@code integration-tests} runs.
 */
@DisplayName("Build guards of the repository")
class BuildGuardsIT {

    private static final String VERSION_TOKEN = "@project.version@";

    @ParameterizedTest(name = "{0} fails in {1}")
    @CsvSource({
            "e2e-assembly-classes, binaries-only",
            "conformance-assembly, no-assembly"})
    @DisplayName("a fixture that breaks a rule fails the build")
    void fixtureFails(String fixture, String execution, @TempDir Path temp) throws Exception {
        var root = Path.of(System.getProperty("pm.root"));
        var pom = materialise(root, fixture);
        // The repository's maven.config names the global settings relative to the repository root, because
        // m2e cannot expand ${session.rootDirectory}. The materialised fixture would resolve that relative
        // path against its own directory, so the root settings are named explicitly; the command line wins
        // over maven.config.
        // The output goes to a file: reading the pipe to its end would wait for a build that hangs, and the
        // time limit below would never apply.
        var log = temp.resolve("fixture.log");
        var builder = new ProcessBuilder(root.resolve("mvnw").toString(), "-B", "--no-transfer-progress",
                "-gs", root.resolve(".mvn/settings.xml").toString(), "-f", pom.toString(), "validate")
                .directory(pom.getParent().toFile()).redirectErrorStream(true).redirectOutput(log.toFile());

        var process = builder.start();
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            // mvnw is a script and Maven its child: both are ended, and the log is read when they are gone
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly().waitFor(30, TimeUnit.SECONDS);
            fail("the build of the fixture did not end:\n" + Files.readString(log));
        }
        var output = Files.readString(log);

        assertNotEquals(0, process.exitValue(), output);
        assertTrue(output.contains("enforce (" + execution + ")"), output);
        assertTrue(output.contains("BUILD FAILURE"), output);
    }

    /**
     * Writes the fixture below {@code target/guard-controls} of the repository, with the version of this build as
     * the version of its parent. The copy lies as deep below the root as its template, so the relative path to
     * the root POM holds, and inside the repository, so Maven finds its {@code .mvn}.
     */
    private static Path materialise(Path root, String fixture) throws IOException {
        var template = Files.readString(root.resolve("src/guard-controls").resolve(fixture).resolve("pom.xml"));
        assertTrue(template.contains(VERSION_TOKEN), "the fixture names no " + VERSION_TOKEN);
        var pom = root.resolve("target/guard-controls").resolve(fixture).resolve("pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, template.replace(VERSION_TOKEN, System.getProperty("pm.version")));
        return pom;
    }
}
