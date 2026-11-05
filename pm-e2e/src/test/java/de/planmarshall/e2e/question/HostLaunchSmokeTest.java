/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.e2e.question;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;


import com.fasterxml.jackson.databind.JsonNode;
import de.planmarshall.e2e.question.driver.Host;
import de.planmarshall.e2e.question.driver.QuestionDriver;
import de.planmarshall.e2e.question.driver.QuestionDriver.OpenCodeDataHome;
import de.planmarshall.e2e.question.fixture.QuestionFixtures;
import de.planmarshall.e2e.question.stub.QuestionStub;
import de.planmarshall.e2e.question.stub.QuestionStub.Form;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launch check of the three hosts, enabled only by {@value #SMOKE}{@code =<host>}: it starts the harness of that
 * host once through the measurement's launch code ({@link QuestionDriver#startFresh}, {@code pm-exec}) on the probe and
 * verifies that the harness starts and its relay connects to the stub ({@value QuestionDriver#CONNECTED}). It measures
 * no item and writes no result; the build's surefire configuration passes the property empty, so a normal build never
 * starts a harness. The OpenCode and Antigravity cases are skipped where their harness is not installed
 * ({@link Host#resolveExecutable}); the Claude Code case fails then, since Claude Code is the host the measurement
 * runs on.
 */
@DisplayName("Host launch smoke check: the harness starts and its relay connects, with -Dpm.experiment.smoke=<host>")
class HostLaunchSmokeTest {

    /** The property that names the host whose launch is checked. */
    static final String SMOKE = "pm.experiment.smoke";

    private static final SecureRandom RANDOM = new SecureRandom();

    @TempDir
    Path directory;

    @Test
    @EnabledIfSystemProperty(named = SMOKE, matches = "(?i)claude")
    @DisplayName("Claude Code starts through pm-exec and its relay connects")
    void claude() throws Exception {
        assertLaunched(Host.CLAUDE);
    }

    @Test
    @EnabledIfSystemProperty(named = SMOKE, matches = "(?i)opencode")
    @DisplayName("OpenCode starts through pm-exec and its relay connects")
    void opencode() throws Exception {
        assumeTrue(installed(Host.OPENCODE, System.getenv()), "OpenCode is not installed");
        assertLaunched(Host.OPENCODE);
    }

    @Test
    @EnabledIfSystemProperty(named = SMOKE, matches = "(?i)antigravity|agy")
    @DisplayName("Antigravity starts through pm-exec and its relay connects")
    void antigravity() throws Exception {
        assumeTrue(installed(Host.ANTIGRAVITY, System.getenv()), "Antigravity is not installed");
        assertLaunched(Host.ANTIGRAVITY);
    }

    private void assertLaunched(Host host) throws IOException, InterruptedException {
        var launch = launch(host, directory);

        assertAll("the launch of " + host.executable(),
                () -> assertTrue(launch.connected().isPresent(), "the relay did not connect: " + launch),
                () -> assertTrue(launch.failure().isEmpty(), "the launch failed: " + launch));
    }

    /**
     * What one launch showed.
     *
     * @param connected the stub's {@value QuestionDriver#CONNECTED} of the generation, empty when none came
     * @param failure   the launch failure, empty when the generation launched
     */
    record Launched(Optional<JsonNode> connected, Optional<QuestionDriver.LaunchFailure> failure) {
    }

    /**
     * Starts the harness of a host once on the probe, waits up to its silence limit for its relay to connect, and ends
     * it.
     *
     * @param host the host
     * @param base the directory of the stub and the worker directories
     * @return what the launch showed
     * @throws IOException          if the stub or the worker cannot be started
     * @throws InterruptedException if interrupted
     */
    static Launched launch(Host host, Path base) throws IOException, InterruptedException {
        var bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        var socket = Path.of("/tmp", "pms-" + HexFormat.of().formatHex(bytes) + ".sock");
        var stubSettings = QuestionStub.Settings.of(base.resolve("stub"), socket).withWindow(host.boundedWait());
        var dataHome = OpenCodeDataHome.of(System.getProperty(OpenCodeDataHome.PROPERTY));
        var model = QuestionLoopMeasurement.Config.models(dataHome).get(host);
        try (var driver = QuestionDriver.start(stubSettings, new QuestionDriver.Settings(host, model,
                     base.resolve("workers"), base.resolve("run"), QuestionDriver.resolveExec(), dataHome, 1))) {
            var fixture = QuestionFixtures.probe();
            QuestionFixtures.prepare(fixture, driver.settings().workspace().resolve("w1"));
            var worker = driver.startFresh(QuestionFixtures.entityTask(fixture, QuestionLoopMeasurement.TASK_ID,
                    "implement", Form.HOLD), QuestionLoopMeasurement.workerLaunch("w1", 1, "implement", host, false,
                    null));
            var connected = driver.awaitEventWhileLaunched(
                    event -> QuestionDriver.CONNECTED.equals(event.path("event").asText())
                            && worker.id().equals(event.path("worker").asText()),
                    System.currentTimeMillis() + host.silenceLimit().toMillis());
            var failure = driver.launchFailure();
            driver.terminate(worker);
            return new Launched(connected, failure);
        }
    }

    /**
     * @param host        a host
     * @param environment the environment whose {@code PATH} is searched
     * @return whether the host's harness is installed ({@link Host#resolveExecutable})
     */
    static boolean installed(Host host, Map<String, String> environment) {
        try {
            host.resolveExecutable(environment);
            return true;
        } catch (IllegalStateException _) {
            return false;
        }
    }
}
