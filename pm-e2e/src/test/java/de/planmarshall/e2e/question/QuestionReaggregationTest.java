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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;


import de.planmarshall.e2e.question.result.QuestionEvidence;
import de.planmarshall.e2e.question.result.QuestionResults;
import de.planmarshall.e2e.question.stub.QuestionStub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("QuestionReaggregation: a result and an audit from existing records")
class QuestionReaggregationTest {

    @TempDir
    Path directory;

    private QuestionResults.ResultTarget target() {
        return new QuestionResults.ResultTarget(directory.resolve("verification"), "again", Set.of(), true);
    }

    private Path results() throws IOException {
        var results = Files.createDirectories(directory.resolve("results"));
        var run = QuestionStub.JSON.createObjectNode();
        run.put("item", "R1");
        run.put("host", "claude");
        run.put("model", "m");
        run.put("form", "fresh");
        run.put("continuation", "hold");
        run.put("fixture", "probe");
        run.put("kind", "entity");
        run.put("reply", "option");
        run.put(QuestionResults.INVOCATION, "R1-test");
        run.put("stub_dir", results.resolve("gone").resolve("stub").toString());
        QuestionResults.recordRun(results, run);
        var invocation = QuestionStub.JSON.createObjectNode();
        invocation.put(QuestionResults.INVOCATION, "R1-test");
        invocation.put("item", "R1");
        invocation.put(QuestionResults.PLANNED_RUNS, 1);
        QuestionResults.recordInvocation(results, invocation);
        return results;
    }

    @Test
    @DisplayName("writes the result of an item from its records")
    void reaggregates() throws Exception {
        var file = QuestionReaggregation.reaggregate("R1", results(), target(), () -> 0);

        assertTrue(Files.isRegularFile(file));
    }

    @Test
    @DisplayName("refuses an item without a run record")
    void refusesEmptyItem() throws Exception {
        var results = results();

        assertThrows(IllegalArgumentException.class,
                () -> QuestionReaggregation.reaggregate("Q1", results, target(), () -> 0));
    }

    @Test
    @DisplayName("writes the answer audit of every run record and refuses a directory without one")
    void audits() throws Exception {
        var file = QuestionReaggregation.auditAnswers(results(), directory.resolve("audit"));

        assertAll(() -> assertTrue(Files.isRegularFile(file)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> QuestionReaggregation.auditAnswers(directory.resolve("empty"), directory)));
    }

    @Test
    @DisplayName("counts a relocation complete only when every invocation had a record")
    void relocated() {
        assertAll(() -> assertTrue(QuestionReaggregation.relocatedAll(Map.of("i1",
                        new QuestionEvidence.Moved(1, 0, 0, 0), "i2", new QuestionEvidence.Moved(0, 0, 0, 2)))),
                () -> assertFalse(QuestionReaggregation.relocatedAll(Map.of("i1",
                        new QuestionEvidence.Moved(0, 0, 0, 0)))),
                () -> assertFalse(QuestionReaggregation.relocatedAll(Map.of())));
    }
}
