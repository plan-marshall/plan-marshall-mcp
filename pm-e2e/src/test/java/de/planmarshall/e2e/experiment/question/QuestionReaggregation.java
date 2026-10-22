/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.e2e.experiment.question;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The re-aggregation of one item (experiment only): writes {@code target/verification-results/question-loop/<item>.json}
 * again from the item's existing run and invocation records, through the same assembly as the measurement
 * ({@link QuestionResults#result}), without starting a stub, a worker or a model. It reads the records only; they are
 * not changed. Disabled unless {@code -Dpm.experiment.reaggregate=<item>} is set.
 * <p>
 * Properties: {@code pm.experiment.reaggregate.results} (the results directory with {@code runs.jsonl} and
 * {@code invocations.jsonl}, default {@code target/question-loop}), and {@code pm.experiment.reaggregate.run-timeouts}
 * ({@code <invocation>=<ISO duration>}, comma-separated): the run limit of invocations recorded before the run limit
 * was part of their settings, marked in the result as supplied at re-aggregation
 * ({@link QuestionResults#supplyRunTimeouts}). R4 reads the telemetry checkout for its unused scenario recipes
 * ({@link QuestionLoopExperiment#telemetryFromProperties()}).
 */
@DisplayName("Question loop re-aggregation: one item's result from its records with -Dpm.experiment.reaggregate=<item>")
@EnabledIfSystemProperty(named = "pm.experiment.reaggregate", matches = ".+")
class QuestionReaggregation {

    @Test
    @DisplayName("writes the result of the item named by pm.experiment.reaggregate from its existing records")
    void reaggregate() throws Exception {
        var item = System.getProperty("pm.experiment.reaggregate").strip().toUpperCase(Locale.ROOT);
        var results = Path.of(System.getProperty("pm.experiment.reaggregate.results", "target/question-loop"))
                .toAbsolutePath();
        var runs = QuestionResults.runs(results, item);
        assertFalse(runs.isEmpty(), "no run record of " + item + " in " + results);
        var invocations = QuestionResults.supplyRunTimeouts(QuestionResults.invocations(results, item),
                System.getProperty("pm.experiment.reaggregate.run-timeouts", ""));

        var result = QuestionResults.result(item, runs, invocations,
                () -> QuestionFixtures.unusedScenarios(QuestionLoopExperiment.telemetryFromProperties()).size());
        var file = QuestionResults.write(item, result);

        assertTrue(Files.isRegularFile(file), "no result written for " + item);
    }
}
