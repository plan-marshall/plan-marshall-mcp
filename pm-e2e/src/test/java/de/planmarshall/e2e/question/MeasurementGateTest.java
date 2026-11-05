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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.util.Set;
import java.util.stream.Collectors;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * The gate of the measurement: without its properties ({@code pm.experiment.question}, {@code pm.experiment.reaggregate},
 * {@code pm.experiment.answer-audit}, {@code pm.experiment.relocate}, {@code pm.experiment.smoke}), which the build's
 * surefire configuration passes empty, {@link QuestionLoopMeasurement}, {@link QuestionReaggregation} and
 * {@link HostLaunchSmokeTest} are disabled and start no process. The classes are run through the JUnit Platform
 * launcher as a build runs them; the test itself is disabled whenever a gate property is set, so it never runs a
 * measurement.
 */
@DisplayName("The measurement gate: without its properties no measurement, re-aggregation or smoke check runs")
@DisabledIfSystemProperty(named = QuestionLoopMeasurement.ITEM_PROPERTY, matches = ".+")
@DisabledIfSystemProperty(named = QuestionReaggregation.REAGGREGATE, matches = ".+")
@DisabledIfSystemProperty(named = QuestionReaggregation.ANSWER_AUDIT, matches = ".+")
@DisabledIfSystemProperty(named = QuestionReaggregation.RELOCATE, matches = ".+")
@DisabledIfSystemProperty(named = HostLaunchSmokeTest.SMOKE, matches = ".+")
class MeasurementGateTest {

    @Test
    @DisplayName("the measurement entry is disabled as a whole")
    void measurementDisabled() {
        var processes = descendants();

        var summary = run(QuestionLoopMeasurement.class);

        assertAll(() -> assertEquals(0, summary.getTestsStartedCount(), "tests started"),
                () -> assertEquals(1, summary.getContainersSkippedCount(), "containers skipped"),
                () -> assertNoNewProcess(processes));
    }

    @Test
    @DisplayName("each re-aggregation, relocation and audit is disabled")
    void reaggregationDisabled() {
        var processes = descendants();

        var summary = run(QuestionReaggregation.class);

        assertAll(() -> assertEquals(0, summary.getTestsStartedCount(), "tests started"),
                () -> assertEquals(3, summary.getTestsSkippedCount(), "tests skipped"),
                () -> assertNoNewProcess(processes));
    }

    @Test
    @DisplayName("each host's smoke check is disabled")
    void smokeDisabled() {
        var processes = descendants();

        var summary = run(HostLaunchSmokeTest.class);

        assertAll(() -> assertEquals(0, summary.getTestsStartedCount(), "tests started"),
                () -> assertEquals(3, summary.getTestsSkippedCount(), "tests skipped"),
                () -> assertNoNewProcess(processes));
    }

    private static TestExecutionSummary run(Class<?> gated) {
        var request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(gated)).build();
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request, listener);
        return listener.getSummary();
    }

    private static Set<Long> descendants() {
        return ProcessHandle.current().descendants().map(ProcessHandle::pid).collect(Collectors.toSet());
    }

    private static void assertNoNewProcess(Set<Long> before) {
        var started = ProcessHandle.current().descendants().map(ProcessHandle::pid)
                .filter(pid -> !before.contains(pid)).toList();
        assertTrue(started.isEmpty(), "processes started: " + started);
    }
}
