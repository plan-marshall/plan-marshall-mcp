/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.e2e.question.result;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;


import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.question.driver.Host;
import de.planmarshall.e2e.question.stub.QuestionStub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("RunView: one run as the evaluation reads it")
class RunViewTest {

    @TempDir
    Path directory;

    private ObjectNode run(Path stub) {
        var run = QuestionStub.JSON.createObjectNode();
        run.put("host", "opencode");
        run.put(RunView.STUB_DIR, stub.toString());
        return run;
    }

    @Test
    @DisplayName("reads the stub's events and records and the worker's output of a run")
    void readsRun() throws Exception {
        var stub = Files.createDirectories(directory.resolve("run-1").resolve("stub"));
        Files.writeString(stub.resolve(RunView.EVENTS_FILE), "{\"event\":\"asked\"}\n\n{\"event\":\"call\"}\n",
                StandardCharsets.UTF_8);
        Files.writeString(stub.resolve(RunView.QUESTIONS_FILE), "{\"id\":\"d1\"}\n", StandardCharsets.UTF_8);
        var output = directory.resolve("output.jsonl");
        Files.writeString(output, "{\"at\":1,\"line\":\"x\"}\n", StandardCharsets.UTF_8);
        var run = run(stub);
        run.putArray("workers").addObject().put("output", output.toString());

        var view = RunView.read(run);

        assertAll(() -> assertEquals(2, view.events().size()),
                () -> assertEquals(1, view.named("asked").size()),
                () -> assertEquals(1, view.records().size()),
                () -> assertEquals(1, view.outputs().size()),
                () -> assertFalse(view.evidenceMissing()),
                () -> assertEquals(Host.OPENCODE, view.host()),
                () -> assertEquals("run-1", RunView.runId(run)));
    }

    @Test
    @DisplayName("reads a run whose stub log is gone as missing evidence with no events")
    void missingEvidence() throws Exception {
        var run = run(directory.resolve("gone").resolve("stub"));

        var view = RunView.read(run);

        assertAll(() -> assertTrue(view.evidenceMissing()), () -> assertTrue(view.events().isEmpty()),
                () -> assertTrue(RunView.evidenceMissing(QuestionStub.JSON.createObjectNode())));
    }

    @Test
    @DisplayName("never counts a run whose setup failed as missing evidence")
    void setupFailed() {
        var run = QuestionStub.JSON.createObjectNode();
        run.put(RunView.SETUP_FAILED, true);
        run.put(RunView.SETUP_FAILED_REASON, "fatal: bad object");

        assertAll(() -> assertTrue(RunView.setupFailed(run)), () -> assertFalse(RunView.evidenceMissing(run)),
                () -> assertEquals("fatal: bad object", RunView.setupFailedReason(run)),
                () -> assertEquals("", RunView.setupFailedReason(QuestionStub.JSON.createObjectNode())));
    }

    @Test
    @DisplayName("reads no line from a file that does not exist")
    void absentFile() throws Exception {
        assertEquals(List.of(), RunView.readJsonLines(directory.resolve("none.jsonl")));
    }

    @ParameterizedTest
    @CsvSource(value = {"claude-haiku-4-5|claude-haiku-4-5", "null|harness default", "''|harness default"},
            delimiter = '|')
    @DisplayName("names the model, the harness default where the launch named none")
    void models(String model, String expected) {
        var run = QuestionStub.JSON.createObjectNode();
        run.put("model", model);

        assertEquals(expected, RunView.model(run));
    }

    @Test
    @DisplayName("matches an event of a generation and normalizes a text")
    void sameAndNormalize() throws Exception {
        var event = QuestionStub.JSON.readTree("{\"worker\":\"w1\",\"generation\":2}");

        assertAll(() -> assertTrue(RunView.same(event, "w1", 2)), () -> assertFalse(RunView.same(event, "w1", 1)),
                () -> assertEquals("f(x) == 3", RunView.normalize("  \"`F(X)   ==  3`\" ")),
                () -> assertEquals("\"", RunView.normalize("\"")),
                () -> assertEquals("plain", RunView.runId(QuestionStub.JSON.createObjectNode().put(RunView.STUB_DIR,
                        "plain"))));
    }
}
