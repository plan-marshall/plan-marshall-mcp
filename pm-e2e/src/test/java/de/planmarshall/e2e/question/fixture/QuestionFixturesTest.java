/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.e2e.question.fixture;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.question.fixture.QuestionFixtures.Check;
import de.planmarshall.e2e.question.fixture.QuestionFixtures.Direction;
import de.planmarshall.e2e.question.fixture.QuestionFixtures.Fixture;
import de.planmarshall.e2e.question.fixture.QuestionFixtures.Reply;
import de.planmarshall.e2e.question.fixture.QuestionFixtures.Source;
import de.planmarshall.e2e.question.stub.QuestionStub;
import de.planmarshall.e2e.question.stub.QuestionStub.Form;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fixtures of the measurement on in-test inputs: the composition of the operator's answer, which never contradicts
 * the fixture's reply, and the forcing text of every forced task, the large-work scenarios of R4 included.
 */
@DisplayName("QuestionFixtures: answer composition and forcing text")
class QuestionFixturesTest {

    private static final String HINTED = "question/apply-answer/he-106";
    private static final String UNHINTED = "question/apply-answer/he-7";
    private static final String REPLY = "The description is right; keep the order of i.";
    private static final String CONSISTENT = "description";
    private static final String CONTRADICTING = "example";
    private static final String OTHER = "other";

    @TempDir
    Path directory;

    private static Fixture fixture(String id) {
        return new Fixture(id, "a", "task", REPLY, new Check("q", List.of(), "r"), false, null);
    }

    private static ObjectNode option(String branch, String label) {
        var option = QuestionStub.JSON.createObjectNode();
        option.put("branch_key", branch);
        option.put("label", label);
        option.put("description", "");
        return option;
    }

    private static ObjectNode decision(String recommended, ObjectNode... options) {
        var decision = QuestionStub.JSON.createObjectNode();
        decision.put("id", "Q-001");
        var part = decision.putObject("envelope").putArray("parts").addObject();
        part.putObject("recommendation").put("branch_key", recommended);
        var array = part.putArray("options");
        for (var option : options) {
            array.add(option);
        }
        decision.putArray("items").addObject().put("question_id", "Q-001");
        return decision;
    }

    private static ObjectNode twoPartDecision() {
        var decision = decision(CONSISTENT, option(CONSISTENT, "Follow the description"));
        var second = ((ArrayNode) decision.path("envelope").path("parts")).addObject();
        second.putObject("recommendation").put("branch_key", OTHER);
        second.putArray("options").add(option(OTHER, "Return a list"));
        ((ArrayNode) decision.path("items")).addObject().put("question_id", "Q-002");
        return decision;
    }

    @Nested
    @DisplayName("answer composition")
    class Composition {

        @Test
        @DisplayName("the recommendation is used where it reads as the reply's direction")
        void recommendationConsistent() {
            var decision = decision(CONSISTENT, option(CONTRADICTING, "Follow the example, descending"),
                    option(CONSISTENT, "Follow the description, ascending"));

            var composition = QuestionFixtures.composeAnswer(fixture(HINTED), decision, Reply.OPTION);

            assertAll(
                    () -> assertEquals(CONSISTENT, composition.parts().getFirst().branchKey()),
                    () -> assertEquals("chosen", composition.parts().getFirst().kind()),
                    () -> assertEquals(REPLY, composition.parts().getFirst().text()),
                    () -> assertTrue(composition.notes().getFirst().hintMatched()),
                    () -> assertFalse(composition.fallbackOwn()));
        }

        @Test
        @DisplayName("a contradicting recommendation gives way to the first option that reads as the reply's direction")
        void firstMatchingOption() {
            var decision = decision(CONTRADICTING, option(CONTRADICTING, "Follow the example, descending"),
                    option(CONSISTENT, "Follow the description, ascending"));

            var composition = QuestionFixtures.composeAnswer(fixture(HINTED), decision, Reply.OPTION);

            assertAll(
                    () -> assertEquals(CONSISTENT, composition.parts().getFirst().branchKey()),
                    () -> assertEquals("Follow the description, ascending", composition.notes().getFirst().label()),
                    () -> assertFalse(composition.fallbackOwn()));
        }

        @Test
        @DisplayName("without a matching option the reply goes as the operator's own option")
        void ownOption() {
            var decision = decision(CONTRADICTING, option(CONTRADICTING, "Follow the example, descending"));

            var composition = QuestionFixtures.composeAnswer(fixture(HINTED), decision, Reply.OPTION);

            assertAll(
                    () -> assertEquals("own", composition.parts().getFirst().branchKey()),
                    () -> assertEquals("own_option", composition.parts().getFirst().kind()),
                    () -> assertEquals(REPLY, composition.parts().getFirst().text()),
                    () -> assertTrue(composition.fallbackOwn()));
        }

        @Test
        @DisplayName("a delegation never takes a contradicting recommendation, nor another option")
        void delegationFallsBack() {
            var decision = decision(CONTRADICTING, option(CONTRADICTING, "Follow the example, descending"),
                    option(CONSISTENT, "Follow the description, ascending"));

            var composition = QuestionFixtures.composeAnswer(fixture(HINTED), decision, Reply.DEFAULT);

            assertAll(
                    () -> assertEquals("own", composition.parts().getFirst().branchKey()),
                    () -> assertTrue(composition.fallbackOwn()));
        }

        @Test
        @DisplayName("a delegation to a consistent recommendation is delegated")
        void delegation() {
            var decision = decision(CONSISTENT, option(CONSISTENT, "Follow the description, ascending"));

            var parts = QuestionFixtures.answer(fixture(HINTED), decision, Reply.DEFAULT);

            assertAll(
                    () -> assertEquals(CONSISTENT, parts.getFirst().branchKey()),
                    () -> assertEquals("delegated", parts.getFirst().kind()));
        }

        @Test
        @DisplayName("a fixture without a hint is answered with the recommended branch")
        void unhinted() {
            var decision = decision(CONTRADICTING, option(CONTRADICTING, "Follow the example, descending"));

            var composition = QuestionFixtures.composeAnswer(fixture(UNHINTED), decision, Reply.OPTION);

            assertAll(
                    () -> assertEquals(CONTRADICTING, composition.parts().getFirst().branchKey()),
                    () -> assertFalse(composition.notes().getFirst().hinted()));
        }

        @Test
        @DisplayName("a text reply goes as the operator's own option")
        void text() {
            var decision = decision(CONSISTENT, option(CONSISTENT, "Follow the description"));

            var parts = QuestionFixtures.answer(fixture(HINTED), decision, Reply.TEXT);

            assertEquals("own_option", parts.getFirst().kind());
        }

        @Test
        @DisplayName("first only answers the first part and leaves the second open")
        void firstOnly() {
            var parts = QuestionFixtures.answer(fixture(UNHINTED), twoPartDecision(), Reply.FIRST_ONLY);

            assertAll(
                    () -> assertEquals(CONSISTENT, parts.get(0).branchKey()),
                    () -> assertEquals("own", parts.get(1).branchKey()),
                    () -> assertEquals("Q-002", parts.get(1).questionId()));
        }

        @Test
        @DisplayName("restating the options names their labels only")
        void restate() {
            var decision = decision(CONSISTENT, option(CONSISTENT, "Ascending"), option(CONTRADICTING, "Descending"));

            var parts = QuestionFixtures.answer(fixture(UNHINTED), decision, Reply.RESTATE_OPTIONS);

            assertEquals("It is one of these: Ascending; Descending.", parts.getFirst().text());
        }

        @Test
        @DisplayName("the hint reads an option by its label, else by its description")
        void hintDirections() {
            var hint = QuestionFixtures.hint(HINTED).orElseThrow();
            var byDescription = option(OTHER, "Option B");
            byDescription.put("description", "reverse the list");

            assertAll(
                    () -> assertEquals(Direction.CONSISTENT, hint.classify("follow the description")),
                    () -> assertEquals(Direction.CONTRADICTING, hint.classify("follow the example")),
                    () -> assertEquals(Direction.UNCLEAR, hint.classify("something else")),
                    () -> assertEquals(Direction.CONTRADICTING, hint.classify(byDescription)),
                    () -> assertTrue(QuestionFixtures.hint("no-such-fixture").isEmpty()));
        }
    }

    @Nested
    @DisplayName("forcing text")
    class Forcing {

        private Path cache() throws IOException {
            var apply = Files.createDirectories(directory.resolve("items/question/apply-answer"));
            var ask = Files.createDirectories(directory.resolve("items/question/ask-or-proceed"));
            Files.writeString(apply.resolve("he-1.json"), """
                    {"id":"question/apply-answer/he-1","group":"g-1","title":"he-1 variant 1a",
                     "input":{"facts":"def f(): pass\\n\\nOperator's answer:\\nAscending.",
                       "question":"%sWhat does f return?","choices":["a","b"]},
                     "target":{"answer":"a"}}
                    """.formatted(QuestionFixtures.ANSWERED_FRAMING), StandardCharsets.UTF_8);
            Files.writeString(ask.resolve("he-1-original.json"), """
                    {"id":"question/ask-or-proceed/he-1-original","group":"g-1","input":{"facts":"def f(): pass"}}
                    """, StandardCharsets.UTF_8);
            Files.writeString(ask.resolve("he-1-2ac.json"), """
                    {"id":"question/ask-or-proceed/he-1-2ac","group":"g-1","tags":["category:2ac"],
                     "input":{"facts":"def g(): pass"}}
                    """, StandardCharsets.UTF_8);
            return directory;
        }

        @Test
        @DisplayName("every apply-answer task carries the forcing text, its facts no answered framing")
        void applyAnswer() throws Exception {
            var fixtures = QuestionFixtures.applyAnswer(cache());

            var fixture = fixtures.getFirst();
            assertAll(
                    () -> assertEquals(1, fixtures.size()),
                    () -> assertTrue(fixture.task().contains(QuestionFixtures.FORCE_ASK)),
                    () -> assertEquals("Ascending.", fixture.reply()),
                    () -> assertEquals("1a", fixture.category()),
                    () -> assertTrue(fixture.check().question().startsWith(QuestionFixtures.CHECK_AFTER_ANSWER)),
                    () -> assertEquals(QuestionFixtures.SMALL, fixture.size()));
        }

        @Test
        @DisplayName("the controls of Q2 stay unforced, the two-flaw tasks of Q5 carry their own forcing text")
        void controlsAndTwoFlaws() throws Exception {
            var root = cache();

            var controls = QuestionFixtures.controls(root);
            var twoFlaws = QuestionFixtures.twoFlaws(root);

            assertAll(
                    () -> assertEquals(1, controls.size()),
                    () -> assertTrue(controls.getFirst().control()),
                    () -> assertFalse(controls.getFirst().task().contains(QuestionFixtures.FORCE_ASK)),
                    () -> assertEquals(1, twoFlaws.size()),
                    () -> assertTrue(twoFlaws.getFirst().task().contains(QuestionFixtures.FORCE_ASK_EACH)));
        }

        @Test
        @DisplayName("a closed question without the answered framing is refused, not reworded")
        void unframedQuestion() {
            assertThrows(IllegalStateException.class, () -> QuestionFixtures.checkAfterAnswer("What?", "x"));
        }

        @Test
        @DisplayName("an envelope over a forced fixture asks in TASK-001, over a control it does not")
        void envelope() {
            var forced = QuestionFixtures.envelopeTask(fixture(HINTED), "T-1", "worker", Form.HOLD);
            var control = QuestionFixtures.envelopeTask(new Fixture("c", "original", "t", "", new Check("q",
                    List.of(), ""), true, null), "T-2", "worker", Form.HOLD);

            assertAll(
                    () -> assertEquals(QuestionFixtures.FORCE_ASK_PLAN_TASK, forced.planTasks().getFirst().text()),
                    () -> assertFalse(control.planTasks().getFirst().text().contains("ask:TASK-001")),
                    () -> assertEquals("task", forced.facts().path("contract").asText()));
        }

        @Test
        @DisplayName("both large-work scenarios of R4 carry the forcing text exactly as the loop tasks do")
        void largeScenariosForced() throws Exception {
            var source = new MapSource(recipeFiles());

            var fixtures = QuestionFixtures.largeScenarios(source, directory.resolve("clones"));

            assertAll(
                    () -> assertEquals(QuestionFixtures.LARGE_SCENARIOS,
                            fixtures.stream().map(Fixture::id).toList()),
                    () -> assertTrue(fixtures.stream().allMatch(fixture -> fixture.task()
                            .contains(QuestionFixtures.FORCE_ASK))),
                    () -> assertTrue(fixtures.stream().allMatch(fixture -> QuestionFixtures.LARGE
                            .equals(fixture.size()))),
                    () -> assertEquals("Use the cache.", fixtures.getFirst().reply()),
                    () -> assertEquals("cache", fixtures.getFirst().check().reference()));
        }

        @Test
        @DisplayName("the unused recipes are the ones besides the large scenarios")
        void unusedScenarios() throws Exception {
            var files = new HashMap<>(recipeFiles());
            files.put(QuestionFixtures.SCENARIOS + "/q-other.json", "{}");

            var unused = QuestionFixtures.unusedScenarios(new MapSource(files));

            assertEquals(List.of("q-other"), unused);
        }

        private Map<String, String> recipeFiles() {
            var files = new HashMap<String, String>();
            for (var id : QuestionFixtures.LARGE_SCENARIOS) {
                files.put(QuestionFixtures.SCENARIOS + "/" + id + ".json", """
                        {"id":"%s","repository":"https://github.com/o/repo.git","plan_month":"2026-10","plan":"p-1",
                         "asking_task":"TASK-001","item":"decision/x/%s","start_commit":"abc",
                         "answer_to_deliver":{"branch_key":"cache","text":"Use the cache."}}
                        """.formatted(id, id));
                files.put(QuestionFixtures.CORPUS + "/decision/x/" + id + ".json", """
                        {"input":{"question":"Which?","choices":["cache","none"]},"target":{"answer":"cache"}}
                        """);
            }
            files.put("projects/repo/archived-plans/2026-10/p-1/tasks/TASK-001.json",
                    "{\"title\":\"Add the cache\",\"description\":\"Add a cache.\"}");
            return files;
        }
    }

    /** The files of a telemetry branch held in memory. */
    private record MapSource(Map<String, String> files) implements Source {

        @Override
        public String read(String path) {
            var content = files.get(path);
            if (content == null) {
                throw new IllegalStateException(path + " is not on the branch");
            }
            return content;
        }

        @Override
        public List<String> names(String directory) {
            return files.keySet().stream().filter(path -> path.startsWith(directory + "/"))
                    .map(path -> path.substring(path.lastIndexOf('/') + 1)).sorted().toList();
        }
    }

    @Nested
    @DisplayName("refusals of the corpus cache and the scenario recipes")
    class Refusals {

        private Map<String, String> recipe(String corpusItem, String ownWords) {
            var id = QuestionFixtures.LARGE_SCENARIOS.getFirst();
            var files = new HashMap<String, String>();
            files.put(QuestionFixtures.SCENARIOS + "/" + id + ".json", """
                    {"id":"%s","repository":"https://github.com/o/repo","plan_month":"2026-10","plan":"p-1",
                     "asking_task":"TASK-001","item":"decision/x/%s","start_commit":"abc",
                     "answer_to_deliver":{"branch_key":"cache","text":"Use the cache."}}
                    """.formatted(id, id));
            files.put("projects/repo/archived-plans/2026-10/p-1/tasks/TASK-001.json",
                    "{\"title\":\"Add the cache\",\"description\":\"Add a cache.\"}");
            files.put(QuestionFixtures.CORPUS + "/decision/x/" + id + ".json", corpusItem);
            if (ownWords != null) {
                files.put(QuestionFixtures.CORPUS + "/decision/own-words/" + id + ".json", ownWords);
            }
            return files;
        }

        private Fixture scenario(Map<String, String> files) throws IOException {
            return QuestionFixtures.scenario(new MapSource(files), QuestionFixtures.LARGE_SCENARIOS.getFirst(),
                    directory.resolve("clones"));
        }

        @Test
        @DisplayName("an answer the operator gave as a new option is added as a choice once its own-words item says so")
        void newOption() throws Exception {
            var fixture = scenario(recipe("""
                    {"input":{"question":"Which?","choices":["a","b"]},"target":{"answer":"c"}}""",
                    "{\"target\":{\"answer\":\"new_option: cache\"}}"));

            assertAll(() -> assertEquals(List.of("a", "b", "cache"), fixture.check().choices()),
                    () -> assertEquals("cache", fixture.check().reference()),
                    () -> assertEquals(directory.resolve("clones").resolve("repo"),
                            fixture.checkout().localClone()));
        }

        @Test
        @DisplayName("a recipe or corpus item that does not say what the fixture needs is refused, not completed")
        void refusals() {
            var otherId = recipe("{}", null);
            otherId.put(QuestionFixtures.SCENARIOS + "/" + QuestionFixtures.LARGE_SCENARIOS.getFirst() + ".json",
                    "{\"id\":\"other\"}");
            var noChoices = recipe("""
                    {"input":{"question":"Which?"},"target":{"answer":"cache"}}""", null);
            var answerNone = recipe("""
                    {"input":{"question":"Which?","choices":["cache","none"]},"target":{"answer":"none"}}""", null);
            var answerNotAChoice = recipe("""
                    {"input":{"question":"Which?","choices":["cache","none"]},"target":{"answer":"other"}}""", null);
            var contradictingAnswer = recipe("""
                    {"input":{"question":"Which?","choices":["a","b"]},"target":{"answer":"c"}}""",
                    "{\"target\":{\"answer\":\"a\"}}");
            var blankQuestion = recipe("""
                    {"input":{"question":" ","choices":["cache"]},"target":{"answer":"cache"}}""", null);

            assertAll(
                    () -> assertThrows(IllegalStateException.class, () -> scenario(otherId)),
                    () -> assertThrows(IllegalStateException.class, () -> scenario(noChoices)),
                    () -> assertThrows(IllegalStateException.class, () -> scenario(answerNone)),
                    () -> assertThrows(IllegalStateException.class, () -> scenario(answerNotAChoice)),
                    () -> assertThrows(IllegalStateException.class, () -> scenario(contradictingAnswer)),
                    () -> assertThrows(IllegalStateException.class, () -> scenario(blankQuestion)));
        }

        @Test
        @DisplayName("a cache without the topic, or an apply-answer item without the operator's answer, is refused")
        void cacheRefusals() throws Exception {
            var apply = Files.createDirectories(directory.resolve("items/question/apply-answer"));
            Files.writeString(apply.resolve("he-2.json"), """
                    {"id":"question/apply-answer/he-2","input":{"facts":"def f(): pass"}}""", StandardCharsets.UTF_8);
            var nothing = directory.resolve("nothing");

            assertAll(
                    () -> assertThrows(IllegalStateException.class, () -> QuestionFixtures.applyAnswer(nothing)),
                    () -> assertThrows(IllegalStateException.class, () -> QuestionFixtures.applyAnswer(directory)));
        }

        @Test
        @DisplayName("a title without a variant counts as unknown, a two-flaw item without a tag by its id")
        void categoriesWithoutMarks() throws Exception {
            var apply = Files.createDirectories(directory.resolve("items/question/apply-answer"));
            var ask = Files.createDirectories(directory.resolve("items/question/ask-or-proceed"));
            Files.writeString(apply.resolve("he-3.json"), """
                    {"id":"question/apply-answer/he-3","group":"g-3","title":"he-3",
                     "input":{"facts":"def f(): pass\\n\\nOperator's answer:\\nAscending.","question":"%sWhat?"}}
                    """.formatted(QuestionFixtures.ANSWERED_FRAMING), StandardCharsets.UTF_8);
            Files.writeString(ask.resolve("he-3-2CP.json"), """
                    {"id":"question/ask-or-proceed/he-3-2CP","group":"g-3","input":{"facts":"def g(): pass"}}
                    """, StandardCharsets.UTF_8);
            Files.writeString(ask.resolve("he-3-original.json"), """
                    {"id":"question/ask-or-proceed/he-3-original","group":"g-other","input":{"facts":"x"}}
                    """, StandardCharsets.UTF_8);
            Files.writeString(ask.resolve("notes.txt"), "not an item", StandardCharsets.UTF_8);

            var applyAnswer = QuestionFixtures.applyAnswer(directory);
            var twoFlaws = QuestionFixtures.twoFlaws(directory);
            var controls = QuestionFixtures.controls(directory);

            assertAll(() -> assertEquals("unknown", applyAnswer.getFirst().category()),
                    () -> assertEquals("2cp", twoFlaws.getFirst().category()),
                    () -> assertTrue(controls.isEmpty()));
        }
    }

    @Nested
    @DisplayName("the tasks of a fixture and the question ids of a decision without items")
    class Tasks {

        @Test
        @DisplayName("an entity and a consulting task carry the fixture's task and check in their facts")
        void entityAndConsulting() {
            var entity = QuestionFixtures.entityTask(fixture(HINTED), "T-1", "worker", Form.HOLD);
            var consulting = QuestionFixtures.consultingTask(fixture(HINTED), "T-2", "worker", Form.HOLD,
                    List.of("reviewer"));
            var unchecked = QuestionFixtures.entityTask(new Fixture("x", "2ac", "t", "", null, false, null), "T-3",
                    "worker", Form.HOLD);

            assertAll(() -> assertEquals("task", entity.facts().path("task").asText()),
                    () -> assertEquals("q", consulting.facts().path("check_question").asText()),
                    () -> assertFalse(unchecked.facts().has("check_question")));
        }

        @Test
        @DisplayName("numbers the parts from the decision's id when it lists no items")
        void questionIdsFromDecisionId() {
            var decision = twoPartDecision();
            decision.put("id", "Q-005");
            decision.remove("items");

            var parts = QuestionFixtures.answer(fixture(UNHINTED), decision, Reply.OPTION);

            assertEquals(List.of("Q-005", "Q-006"), parts.stream().map(QuestionStub.PartAnswer::questionId).toList());
        }
    }

    @Nested
    @DisplayName("git: the telemetry branch, the sibling repositories and the scenario checkout")
    class Git {

        private Path repository() throws IOException {
            var repository = Files.createDirectories(directory.resolve("repo"));
            QuestionFixtures.git(null, "init", "--quiet", repository.toString());
            var recipes = Files.createDirectories(repository.resolve(QuestionFixtures.SCENARIOS));
            Files.writeString(recipes.resolve("q-a.json"), "{\"id\":\"q-a\"}", StandardCharsets.UTF_8);
            QuestionFixtures.git(repository, "add", ".");
            QuestionFixtures.git(repository, "-c", "user.name=test", "-c", "user.email=test@example.org", "-c",
                    "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "--quiet", "-m", "recipes");
            return repository;
        }

        @Test
        @DisplayName("reads files and directory entries of a ref, and no entry of a directory the ref lacks")
        void telemetry() throws Exception {
            var source = QuestionFixtures.telemetry(repository(), "HEAD");

            assertAll(() -> assertEquals("{\"id\":\"q-a\"}", source.read(QuestionFixtures.SCENARIOS + "/q-a.json")),
                    () -> assertEquals(List.of("q-a.json"), source.names(QuestionFixtures.SCENARIOS)),
                    () -> assertEquals(List.of(), source.names("no/such/directory")),
                    () -> assertEquals(List.of("q-a"), QuestionFixtures.unusedScenarios(source)));
        }

        @Test
        @DisplayName("a failing git command reports its first line of output, a silent one an empty line")
        void gitFailure() {
            var failure = assertThrows(QuestionFixtures.GitFailure.class,
                    () -> QuestionFixtures.git(directory, "rev-parse", "--verify", "no-such-ref"));

            assertAll(() -> assertFalse(failure.firstLine().isEmpty()),
                    () -> assertEquals("", new QuestionFixtures.GitFailure("git x", "").firstLine()));
        }

        @Test
        @DisplayName("the sibling repositories are the parent of the main checkout")
        void siblingRepositories() throws Exception {
            var repository = repository();

            assertEquals(repository.toRealPath().getParent(),
                    QuestionFixtures.siblingRepositories(repository).toRealPath());
        }

        @Test
        @DisplayName("checks a scenario out from the clone's objects and writes a canary beside it")
        void checkout() throws Exception {
            var clone = repository();
            var commit = QuestionFixtures.git(clone, "rev-parse", "HEAD").strip();
            var fixture = new Fixture("s", QuestionFixtures.LARGE, "t", "r", null, false,
                    new QuestionFixtures.Checkout(clone, commit));
            var worker = directory.resolve("worker");

            var canary = QuestionFixtures.prepare(fixture, worker);

            assertAll(() -> assertTrue(canary.startsWith("canary-")),
                    () -> assertTrue(Files.isRegularFile(worker.resolve(QuestionFixtures.SCENARIOS).resolve(
                            "q-a.json"))),
                    () -> assertEquals(commit, QuestionFixtures.git(worker, "rev-parse", "HEAD").strip()));
        }

        @Test
        @DisplayName("a missing clone or a start commit no object store holds fails the setup with its reason")
        void setupFailures() throws Exception {
            var clone = repository();
            var missing = new Fixture("s", QuestionFixtures.LARGE, "t", "r", null, false,
                    new QuestionFixtures.Checkout(directory.resolve("absent"), "abc"));
            var unknownCommit = new Fixture("s", QuestionFixtures.LARGE, "t", "r", null, false,
                    new QuestionFixtures.Checkout(clone, "0123456789abcdef0123456789abcdef01234567"));

            var noClone = assertThrows(QuestionFixtures.SetupFailure.class,
                    () -> QuestionFixtures.prepare(missing, directory.resolve("w1")));
            var noCommit = assertThrows(QuestionFixtures.SetupFailure.class,
                    () -> QuestionFixtures.prepare(unknownCommit, directory.resolve("w2")));

            assertAll(() -> assertTrue(noClone.reason().startsWith("no local clone at ")),
                    () -> assertFalse(noCommit.reason().isEmpty()),
                    () -> assertInstanceOf(QuestionFixtures.GitFailure.class, noCommit.getCause()));
        }
    }

    @Test
    @DisplayName("a prepared worker directory holds a fresh canary")
    void prepare() throws Exception {
        var canary = QuestionFixtures.prepare(QuestionFixtures.probe(), directory.resolve("w"));

        assertAll(
                () -> assertTrue(canary.startsWith("canary-")),
                () -> assertEquals(canary + "\n", Files.readString(directory.resolve("w")
                        .resolve(QuestionFixtures.CANARY_FILE), StandardCharsets.UTF_8)));
    }
}
