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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


import de.planmarshall.e2e.question.result.QuestionEvidence;
import de.planmarshall.e2e.question.result.QuestionResults;
import de.planmarshall.e2e.question.stub.QuestionStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    @Nested
    @DisplayName("the property-driven entry points, called with their properties pointing into a temporary directory")
    class PropertyDriven {

        private final Map<String, String> saved = new HashMap<>();

        private void set(String name, String value) {
            if (!saved.containsKey(name)) {
                saved.put(name, System.getProperty(name));
            }
            System.setProperty(name, value);
        }

        @AfterEach
        void restore() {
            saved.forEach((name, value) -> {
                if (value == null) {
                    System.clearProperty(name);
                } else {
                    System.setProperty(name, value);
                }
            });
            saved.clear();
        }

        @Test
        @DisplayName("re-aggregates the item the property names, upper-cased, into a suffixed result file")
        void reaggregatesNamedItem() throws Exception {
            var suffix = "coverage-" + UUID.randomUUID();
            var file = QuestionResults.ResultTarget.DIRECTORY.resolve("R1-" + suffix + ".json");
            set(QuestionReaggregation.REAGGREGATE, " r1 ");
            set(QuestionReaggregation.RESULTS, results().toString());
            set(QuestionResults.ResultTarget.SUFFIX, suffix);
            set(QuestionResults.ResultTarget.INVOCATIONS, "R1-test");
            try {
                new QuestionReaggregation().reaggregate();

                assertTrue(Files.isRegularFile(file), "no suffixed result written: " + file);
            } finally {
                Files.deleteIfExists(file);
            }
        }

        @Test
        @DisplayName("relocates the stub logs of the invocation the property names into the persistent root")
        void relocatesNamedInvocation() throws Exception {
            var results = Files.createDirectories(directory.resolve("relocation"));
            var stub = Files.createDirectories(directory.resolve("os").resolve("ws").resolve("run-1").resolve("stub"));
            Files.writeString(stub.resolve("events.jsonl"), "{}\n");
            var run = QuestionStub.JSON.createObjectNode();
            run.put("item", "R1");
            run.put(QuestionResults.INVOCATION, "R1-move");
            run.put("stub_dir", stub.toString());
            QuestionResults.recordRun(results, run);
            var workRoot = directory.resolve("work");
            set(QuestionReaggregation.RESULTS, results.toString());
            set(QuestionReaggregation.RELOCATE, "R1-move");
            set(QuestionReaggregation.RELOCATE + ".fallback", directory.resolve("fallback").toString());
            set(QuestionEvidence.WORK_PROPERTY, workRoot.toString());

            new QuestionReaggregation().relocate();

            assertAll(() -> assertTrue(Files.isRegularFile(workRoot.resolve("ws").resolve("run-1").resolve("stub")
                            .resolve("events.jsonl")), "the stub log was not copied"),
                    () -> assertTrue(Files.readString(results.resolve(QuestionEvidence.RUNS_FILE))
                            .contains(workRoot.toString()), "the record was not rewritten"));
        }
    }
}
