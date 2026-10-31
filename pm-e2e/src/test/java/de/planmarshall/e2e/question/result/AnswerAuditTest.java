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
import java.util.Optional;
import java.util.regex.Pattern;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import de.planmarshall.e2e.question.fixture.QuestionFixtures;
import de.planmarshall.e2e.question.fixture.QuestionFixtures.Composition;
import de.planmarshall.e2e.question.fixture.QuestionFixtures.ConsistencyHint;
import de.planmarshall.e2e.question.fixture.QuestionFixtures.PartNote;
import de.planmarshall.e2e.question.fixture.QuestionFixtures.Reply;
import de.planmarshall.e2e.question.stub.QuestionStub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("AnswerAudit: how the driver answered on the operator's behalf")
class AnswerAuditTest {

    private static final ConsistencyHint HINT = new ConsistencyHint(Pattern.compile("description"),
            Pattern.compile("examples"), "the description");
    private static final String PART = "{\"options\":[{\"branch_key\":\"d\",\"label\":\"Follow the description\"},"
            + "{\"branch_key\":\"e\",\"label\":\"Follow the examples\"},{\"branch_key\":\"x\",\"label\":\"Other\"}]}";

    @TempDir
    Path directory;

    private static JsonNode json(String text) {
        try {
            return QuestionStub.JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    @Test
    @DisplayName("lists the fields of each composed part")
    void compositionFields() {
        var fields = AnswerAudit.compositionFields("d1", Reply.DEFAULT, new PartNote("q1", "d", "Label", true, true,
                false));

        assertAll(() -> assertEquals("d1", fields.get("decision")), () -> assertEquals("default", fields.get("reply")),
                () -> assertEquals("q1", fields.get("question_id")), () -> assertEquals(false, fields.get("fallback_own")));
    }

    @Test
    @DisplayName("appends each composed part to the run record and marks a fallback to the own option once")
    void recordsComposition() {
        var run = QuestionStub.JSON.createObjectNode();

        AnswerAudit.recordComposition(run, "d1", Reply.OPTION, new Composition(List.of(),
                List.of(new PartNote("q1", "own", "Own", true, false, true))));
        AnswerAudit.recordComposition(run, "d2", Reply.OPTION, new Composition(List.of(),
                List.of(new PartNote("q2", "d", "D", true, true, false))));

        assertAll(() -> assertEquals(2, run.path(AnswerAudit.ANSWER_COMPOSITIONS).size()),
                () -> assertTrue(run.path(AnswerAudit.ANSWER_FALLBACK_OWN).asBoolean()));
    }

    @Test
    @DisplayName("classifies an answer by its channel, its own option, the hint and the chosen option")
    void answerClasses() {
        var part = json(PART);

        assertAll(() -> assertEquals("quorum", AnswerAudit.answerClass("quorum", json("{}"), part, Optional.of(HINT))),
                () -> assertEquals("own", AnswerAudit.answerClass("cli", json("{\"branch_key\":\"own\"}"), part,
                        Optional.of(HINT))),
                () -> assertEquals("own", AnswerAudit.answerClass("cli", json("{\"kind\":\"own_option\"}"), part,
                        Optional.of(HINT))),
                () -> assertEquals("unhinted", AnswerAudit.answerClass("cli", json("{\"branch_key\":\"d\"}"), part,
                        Optional.empty())),
                () -> assertEquals("consistent", AnswerAudit.answerClass("cli", json("{\"branch_key\":\"d\"}"), part,
                        Optional.of(HINT))),
                () -> assertEquals(AnswerAudit.CONTRADICTING, AnswerAudit.answerClass("cli",
                        json("{\"branch_key\":\"e\"}"), part, Optional.of(HINT))),
                () -> assertEquals("unclear", AnswerAudit.answerClass("cli", json("{\"branch_key\":\"x\"}"), part,
                        Optional.of(HINT))),
                () -> assertEquals("unclear", AnswerAudit.answerClass("cli", json("{\"branch_key\":\"d\"}"), null,
                        Optional.of(HINT))));
    }

    @Test
    @DisplayName("audits the answers of stored runs per group and skips a run whose setup failed")
    void audits() throws Exception {
        var stub = Files.createDirectories(directory.resolve("run-1").resolve("stub"));
        Files.writeString(stub.resolve(RunView.EVENTS_FILE), "{\"event\":\"asked\"}\n", StandardCharsets.UTF_8);
        Files.writeString(stub.resolve(RunView.QUESTIONS_FILE),
                "{\"line\":\"asked\",\"question_ids\":[\"q1\"],\"envelope\":{\"parts\":[" + PART + "]}}\n"
                        + "{\"line\":\"answered\",\"channel\":\"cli\",\"items\":[{\"question_id\":\"q1\","
                        + "\"branch_key\":\"d\",\"label\":\"Follow the description\"}]}\n",
                StandardCharsets.UTF_8);
        var run = json("{\"item\":\"Q1\",\"host\":\"claude\",\"model\":\"m\",\"form\":\"fresh\",\"kind\":\"entity\","
                + "\"reply\":\"option\",\"fixture\":\"probe\",\"stub_dir\":\"" + stub + "\"}");
        var failed = json("{\"item\":\"Q1\",\"setup_failed\":true}");

        var audit = AnswerAudit.audit(List.of(run, failed));

        assertAll(() -> assertEquals(1, audit.path("groups").size()),
                () -> assertEquals(1, audit.path("totals").path("runs").asInt()),
                () -> assertEquals(0, audit.path("totals").path(AnswerAudit.CONTRADICTING).asInt()),
                () -> assertFalse(audit.path("totals").isEmpty()),
                () -> assertTrue(QuestionFixtures.hint("probe").isEmpty() || audit.path("options").has("probe")));
    }
}
