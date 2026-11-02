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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;


import de.planmarshall.e2e.question.fixture.QuestionFixtures;
import de.planmarshall.e2e.question.result.AnswerAudit;
import de.planmarshall.e2e.question.result.QuestionEvidence;
import de.planmarshall.e2e.question.result.QuestionResults;
import de.planmarshall.e2e.question.result.RunView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The re-aggregation of one item: writes {@code target/verification-results/question-loop/<item>.json} again from the
 * item's existing run and invocation records, through the same assembly as the measurement
 * ({@link QuestionResults#result}), without starting a stub, a worker or a model. It reads the records only. Disabled
 * unless {@value #REAGGREGATE}{@code =<item>} is set; the build's surefire configuration passes it empty.
 * <p>
 * Properties: {@value #RESULTS} (the results directory with {@code runs.jsonl} and {@code invocations.jsonl}, default
 * {@code target/question-loop}). R4 reads the telemetry checkout for its unused scenario recipes
 * ({@link QuestionLoopMeasurement#telemetryFromProperties()}). The result goes through the target and guard of
 * {@link QuestionResults.ResultTarget} ({@code pm.experiment.result.suffix}, {@code pm.experiment.result.invocations},
 * {@code pm.experiment.result.allow-missing-evidence}).
 * <p>
 * Beside it, the answer audit ({@link AnswerAudit#audit}) over every run record of the results directory, disabled
 * unless {@value #ANSWER_AUDIT}{@code =true}: it writes {@code target/verification-results/question-loop/answer-audit.json};
 * and the relocation of stub logs ({@link QuestionEvidence#relocate}), disabled unless
 * {@value #RELOCATE}{@code =<invocation>,...}: it copies the stub directories of those invocations' run records into the
 * persistent root ({@link QuestionEvidence#workRoot}), from the directory the record names while it exists, else from a
 * copy below a root of {@value #RELOCATE}{@code .fallback=<dir>,...}, rewrites only their {@code stub_dir}, and writes
 * its report beside the logs.
 */
@DisplayName("Question loop re-aggregation: one item's result from its records with -Dpm.experiment.reaggregate=<item>")
@SuppressWarnings("java:S3577") // not named *Test on purpose: the default surefire run must not select it
class QuestionReaggregation {

    /** The property that names the item to re-aggregate. */
    static final String REAGGREGATE = "pm.experiment.reaggregate";
    /** The property of the results directory. */
    static final String RESULTS = "pm.experiment.reaggregate.results";
    /** The property that switches the answer audit on. */
    static final String ANSWER_AUDIT = "pm.experiment.answer-audit";
    /** The property that names the invocations whose stub logs {@link #relocate()} moves. */
    static final String RELOCATE = "pm.experiment.relocate";

    @Test
    @EnabledIfSystemProperty(named = REAGGREGATE, matches = ".+")
    @DisplayName("writes the result of the item named by pm.experiment.reaggregate from its existing records")
    void reaggregate() throws Exception {
        var item = System.getProperty(REAGGREGATE).strip().toUpperCase(Locale.ROOT);

        var file = reaggregate(item, results(), QuestionResults.ResultTarget.fromProperties(),
                () -> QuestionFixtures.unusedScenarios(QuestionLoopMeasurement.telemetryFromProperties()).size());

        assertTrue(Files.isRegularFile(file), "no result written for " + item);
    }

    @Test
    @EnabledIfSystemProperty(named = RELOCATE, matches = ".+")
    @DisplayName("relocates the stub logs of the invocations named by pm.experiment.relocate into the persistent root")
    void relocate() throws Exception {
        var results = results();
        var invocations = QuestionEvidence.list(System.getProperty(RELOCATE));
        var fallbacks = QuestionEvidence.list(System.getProperty(RELOCATE + ".fallback", "")).stream()
                .map(Path::of).toList();
        var workRoot = QuestionEvidence.workRoot(results);

        var moved = QuestionEvidence.relocate(results.resolve(QuestionEvidence.RUNS_FILE), invocations, workRoot,
                fallbacks);
        var report = QuestionEvidence.report(workRoot, moved);

        assertAll("the relocation, reported in " + report,
                () -> assertTrue(relocatedAll(moved), "an invocation without records: " + moved),
                () -> assertTrue(Files.isRegularFile(report)));
    }

    @Test
    @EnabledIfSystemProperty(named = ANSWER_AUDIT, matches = "true")
    @DisplayName("writes the answer audit of every stored run record")
    void auditAnswers() throws Exception {
        var file = auditAnswers(results(), Path.of("target", "verification-results", "question-loop"));

        assertTrue(Files.isRegularFile(file), "no answer audit written");
    }

    /**
     * Writes the result of one item from its existing records.
     *
     * @param item    the item
     * @param results the results directory
     * @param target  the target of the result
     * @param unused  the count of unused scenario recipes, asked for R4 only
     * @return the result file
     * @throws IOException              if the records cannot be read or the result not be written
     * @throws IllegalArgumentException if the item has no run record
     */
    static Path reaggregate(String item, Path results, QuestionResults.ResultTarget target,
            QuestionResults.UnusedScenarios unused) throws IOException {
        var runs = target.select(QuestionResults.runs(results, item));
        if (runs.isEmpty()) {
            throw new IllegalArgumentException("no run record of " + item + " in " + results);
        }
        var invocations = target.select(QuestionResults.invocations(results, item));
        return QuestionResults.write(target, item, QuestionResults.result(item, runs, invocations, unused));
    }

    /**
     * Writes the answer audit of every run record of a results directory.
     *
     * @param results   the results directory
     * @param directory where {@code answer-audit.json} goes
     * @return the audit file
     * @throws IOException              if the records cannot be read or the audit not be written
     * @throws IllegalArgumentException if the directory has no run record
     */
    static Path auditAnswers(Path results, Path directory) throws IOException {
        var runs = RunView.readJsonLines(results.resolve(QuestionEvidence.RUNS_FILE));
        if (runs.isEmpty()) {
            throw new IllegalArgumentException("no run record in " + results);
        }
        var file = Files.createDirectories(directory).resolve(QuestionEvidence.ANSWER_AUDIT_FILE);
        Files.writeString(file, QuestionResults.render(AnswerAudit.audit(runs)), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * @param moved the relocation per invocation
     * @return whether there is an invocation and every one had a record that was relocated now or before
     */
    static boolean relocatedAll(Map<String, QuestionEvidence.Moved> moved) {
        return !moved.isEmpty()
                && moved.values().stream().allMatch(counts -> counts.relocated() + counts.already() > 0);
    }

    private static Path results() {
        return Path.of(System.getProperty(RESULTS, "target/question-loop")).toAbsolutePath();
    }
}
