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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.question.fixture.QuestionFixtures;
import de.planmarshall.e2e.question.result.QuestionResults.MissingEvidenceRefusal;
import de.planmarshall.e2e.question.result.QuestionResults.ResultTarget;
import de.planmarshall.e2e.question.stub.QuestionStub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evidence of the measurement against a temporary directory: the guard that never lets a result with missing stub
 * logs replace an existing result, the store directory of an invocation in the layout of the evidence store, the
 * relocation of stub logs out of the temporary directory, and the answer audit.
 */
@DisplayName("QuestionEvidence: guard, store layout, relocation and answer audit")
class QuestionEvidenceTest {

    private static final String INVOCATION = "inv-1";

    @TempDir
    Path directory;

    private ObjectNode run(String id, String invocation, boolean withStubLog) throws IOException {
        var stub = directory.resolve("tmp").resolve("ws").resolve(id).resolve("stub");
        if (withStubLog) {
            Files.createDirectories(stub);
            Files.writeString(stub.resolve(RunView.EVENTS_FILE),
                    "{\"event\":\"asked\",\"worker\":\"w\",\"generation\":1,\"at\":1}\n", StandardCharsets.UTF_8);
            Files.writeString(stub.resolve(RunView.QUESTIONS_FILE), "{\"line\":\"asked\"}\n", StandardCharsets.UTF_8);
        }
        var run = QuestionStub.JSON.createObjectNode();
        run.put("item", "Q1");
        run.put("invocation", invocation);
        run.put("host", "claude");
        run.put("fixture", "f-1");
        run.put(QuestionResults.CLIENT_VERSION, "2.1.0");
        run.put(RunView.STUB_DIR, stub.toString());
        return run;
    }

    private Path results(ObjectNode... runs) throws IOException {
        var results = Files.createDirectories(directory.resolve("results"));
        for (var run : runs) {
            QuestionResults.recordRun(results, run);
        }
        var invocation = QuestionStub.JSON.createObjectNode();
        invocation.put("invocation", INVOCATION);
        invocation.put("item", "Q1");
        QuestionResults.recordInvocation(results, invocation);
        return results;
    }

    @Nested
    @DisplayName("the evidence_missing guard")
    class Guard {

        private ObjectNode missingResult() throws IOException {
            return QuestionResults.evaluate("Q1", List.of(run("r-1", INVOCATION, false)));
        }

        @Test
        @DisplayName("a run without its stub log counts as evidence_missing and does not overwrite a result file")
        void refusesToReplace() throws Exception {
            var target = new ResultTarget(directory, null, Set.of(), false);
            Files.writeString(target.file("Q1"), "existing", StandardCharsets.UTF_8);
            var result = missingResult();

            assertAll(
                    () -> assertEquals(1, result.path(QuestionResults.EVIDENCE_MISSING).asInt()),
                    () -> assertThrows(MissingEvidenceRefusal.class,
                            () -> QuestionResults.write(target, "Q1", result)),
                    () -> assertEquals("existing", Files.readString(target.file("Q1"), StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("allowed, or as a new file, the result is written with the runs counted apart")
        void writesWhenAllowedOrNew() throws Exception {
            var allowed = new ResultTarget(directory, null, Set.of(), true);
            Files.writeString(allowed.file("Q1"), "existing", StandardCharsets.UTF_8);
            var fresh = new ResultTarget(directory, "new", Set.of(), false);
            var result = missingResult();

            QuestionResults.write(allowed, "Q1", result);
            QuestionResults.write(fresh, "Q1", result);

            assertAll(
                    () -> assertTrue(Files.readString(allowed.file("Q1")).contains(QuestionResults.EVIDENCE_MISSING)),
                    () -> assertEquals(directory.resolve("Q1-new.json"), fresh.file("Q1")),
                    () -> assertTrue(Files.exists(fresh.file("Q1"))));
        }

        @Test
        @DisplayName("a target refuses a malformed suffix, invocations without a suffix, and absent invocations")
        void target() {
            var named = new ResultTarget(directory, "s", Set.of("inv-9"), false);
            var records = List.<JsonNode>of(QuestionStub.JSON.createObjectNode()
                    .put("invocation", INVOCATION));

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new ResultTarget(directory, "../x", Set.of(), false)),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new ResultTarget(directory, null, Set.of(INVOCATION), false)),
                    () -> assertThrows(IllegalArgumentException.class, () -> named.select(records)),
                    () -> assertEquals(records, new ResultTarget(directory, null, Set.of(), false).select(records)));
        }
    }

    @Nested
    @DisplayName("the store directory")
    class Store {

        @Test
        @DisplayName("holds the invocation's records, stub logs, archive and answer audit in the store's layout")
        void layout() throws Exception {
            var own = run("r-1", INVOCATION, true);
            var other = run("r-2", "inv-2", true);
            var results = results(own, other);

            var store = QuestionEvidence.store(results, "Q1", INVOCATION, () -> 0);

            assertAll(
                    () -> assertEquals(results.resolve(QuestionEvidence.STORE).resolve(INVOCATION), store),
                    () -> assertEquals(1, Files.readAllLines(store.resolve(QuestionEvidence.RUNS_FILE)).size()),
                    () -> assertEquals(1,
                            Files.readAllLines(store.resolve(QuestionEvidence.INVOCATIONS_FILE)).size()),
                    () -> assertTrue(Files.isRegularFile(store.resolve(QuestionEvidence.STUB).resolve("r-1")
                            .resolve(RunView.EVENTS_FILE))),
                    () -> assertFalse(Files.exists(store.resolve(QuestionEvidence.STUB).resolve("r-2"))),
                    () -> assertTrue(Files.isRegularFile(store.resolve(QuestionEvidence.ARCHIVE)
                            .resolve(QuestionEvidence.RESULT_FILE))),
                    () -> assertTrue(Files.readString(store.resolve(QuestionEvidence.ARCHIVE)
                            .resolve(QuestionEvidence.ANALYSIS_FILE)).contains("stub logs stored: 1")),
                    () -> assertEquals(0, QuestionStub.JSON.readTree(store.resolve(QuestionEvidence.ANSWER_AUDIT_FILE)
                            .toFile()).path("totals").path(AnswerAudit.CONTRADICTING).asInt()));
        }

        @Test
        @DisplayName("a run without its stub log is stored without stub logs and counted missing")
        void missingStubLog() throws Exception {
            var results = results(run("r-1", INVOCATION, false));

            var store = QuestionEvidence.store(results, "Q1", INVOCATION, () -> 0);

            assertAll(
                    () -> assertFalse(Files.exists(store.resolve(QuestionEvidence.STUB).resolve("r-1"))),
                    () -> assertEquals(1, QuestionStub.JSON.readTree(store.resolve(QuestionEvidence.ARCHIVE)
                            .resolve(QuestionEvidence.RESULT_FILE).toFile())
                            .path(QuestionResults.EVIDENCE_MISSING).asInt()));
        }

        @Test
        @DisplayName("an invocation id that is no single directory name is refused")
        void invocationId() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> QuestionEvidence.storeDirectory(directory, "../x")),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> QuestionEvidence.storeDirectory(directory, " ")),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> QuestionEvidence.storeDirectory(directory, ".hidden")));
        }

        @Test
        @DisplayName("the work root and the switch of the store directory default from the system properties")
        void defaults() {
            assertAll(
                    () -> assertEquals(directory.resolve("work"), QuestionEvidence.workRoot(directory)),
                    () -> assertTrue(QuestionEvidence.archiveEnabled()),
                    () -> assertEquals(List.of("a", "b"), QuestionEvidence.list(" a, ,b ")));
        }
    }

    @Nested
    @DisplayName("relocation")
    class Relocation {

        @Test
        @DisplayName("copies the stub logs into the work root and rewrites only stub_dir, once")
        void relocates() throws Exception {
            var results = results(run("r-1", INVOCATION, true), run("r-2", "inv-2", true));
            var runsFile = results.resolve(QuestionEvidence.RUNS_FILE);
            var work = directory.resolve("work");

            var first = QuestionEvidence.relocate(runsFile, List.of(INVOCATION), work, List.of());
            var second = QuestionEvidence.relocate(runsFile, List.of(INVOCATION), work, List.of());

            var lines = Files.readAllLines(runsFile);
            assertAll(
                    () -> assertEquals(1, first.get(INVOCATION).relocated()),
                    () -> assertEquals(1, second.get(INVOCATION).already()),
                    () -> assertTrue(Files.isRegularFile(work.resolve("ws").resolve("r-1").resolve("stub")
                            .resolve(RunView.EVENTS_FILE))),
                    () -> assertTrue(lines.get(0).contains(work.toString())),
                    () -> assertTrue(lines.get(1).contains(directory.resolve("tmp").toString())),
                    () -> assertTrue(Files.isRegularFile(QuestionEvidence.report(work, first))));
        }

        @Test
        @DisplayName("a record without its stub logs anywhere is refused")
        void noSource() throws Exception {
            var results = results(run("r-1", INVOCATION, false));

            assertThrows(IOException.class, () -> QuestionEvidence.relocate(results.resolve(
                    QuestionEvidence.RUNS_FILE), List.of(INVOCATION), directory.resolve("work"), List.of()));
        }

        @Test
        @DisplayName("a line that names its stub directory twice is refused")
        void twice() throws Exception {
            var record = run("r-1", INVOCATION, true);
            record.put("copy", record.path(RunView.STUB_DIR).asText());
            var line = QuestionStub.JSON.writeValueAsString(record).replace("\"copy\"", "\"" + RunView.STUB_DIR
                    + "\"");

            assertThrows(IOException.class, () -> QuestionEvidence.withStubDir(line, record, directory));
        }
    }

    @Nested
    @DisplayName("the answer audit")
    class Audit {

        @Test
        @DisplayName("classifies the driver's answers by the fixture's hint")
        void classes() {
            var hint = QuestionFixtures.hint("question/apply-answer/he-106");
            var part = QuestionStub.JSON.createObjectNode();
            var options = part.putArray("options");
            options.addObject().put("branch_key", "a").put("label", "Follow the example");
            options.addObject().put("branch_key", "b").put("label", "Follow the description");
            var chosenA = QuestionStub.JSON.createObjectNode().put("branch_key", "a");
            var chosenB = QuestionStub.JSON.createObjectNode().put("branch_key", "b");
            var own = QuestionStub.JSON.createObjectNode().put("branch_key", "own");

            assertAll(
                    () -> assertEquals(AnswerAudit.CONTRADICTING, AnswerAudit.answerClass("cli", chosenA, part, hint)),
                    () -> assertEquals("consistent", AnswerAudit.answerClass("cli", chosenB, part, hint)),
                    () -> assertEquals("own", AnswerAudit.answerClass("cli", own, part, hint)),
                    () -> assertEquals("quorum", AnswerAudit.answerClass("quorum", chosenA, part, hint)),
                    () -> assertEquals("unhinted", AnswerAudit.answerClass("cli", chosenA, part, Optional.empty())),
                    () -> assertEquals("unclear", AnswerAudit.answerClass("cli", chosenA, null, hint)));
        }

        @Test
        @DisplayName("records how an answer was composed and whether it fell back to the own option")
        void composition() {
            var run = QuestionStub.JSON.createObjectNode();
            var note = new QuestionFixtures.PartNote("Q-001", "own", "", true, false, true);
            var composition = new QuestionFixtures.Composition(List.of(), List.of(note));

            AnswerAudit.recordComposition(run, "Q-001", QuestionFixtures.Reply.OPTION, composition);

            assertAll(
                    () -> assertTrue(run.path(AnswerAudit.ANSWER_FALLBACK_OWN).asBoolean()),
                    () -> assertEquals("option", run.path(AnswerAudit.ANSWER_COMPOSITIONS).get(0).path("reply")
                            .asText()));
        }
    }

    @Nested
    @DisplayName("what a relocation counts per invocation")
    class MovedCounts {

        @Test
        @DisplayName("counts a copy from the recorded directory, a copy from a fallback and a record left as it was")
        void counts() {
            var moved = new QuestionEvidence.Moved(0, 0, 0, 0).add(false).add(true).add(true).kept();

            assertEquals(new QuestionEvidence.Moved(3, 1, 2, 1), moved);
        }
    }
}
