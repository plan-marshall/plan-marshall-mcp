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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.experiment.question.QuestionStub.Form;
import de.planmarshall.e2e.experiment.question.QuestionStub.PartAnswer;
import de.planmarshall.e2e.experiment.question.QuestionStub.PlanTask;
import de.planmarshall.e2e.experiment.question.QuestionStub.TaskSpec;

/**
 * The fixtures of the measurement (experiment only), generated at run time and never written into the repository.
 * <ul>
 * <li>Q1 to Q4 and R3: the generated items of the corpus topic {@code question/apply-answer} from the corpus cache
 * ({@code items/question/apply-answer/*.json}, written by {@code CorpusSourcesTest} with {@code -Pcorpus}): the
 * modified variant is the task, asking is forced ({@link #FORCE_ASK}: the choice is the operator's and unknown to the
 * worker, so it must ask before acting), the operator's reply is the text after {@code Operator's answer:} of the
 * item's facts, and the item's closed question is submitted after the answer; before the ask the facts introduce it as
 * that closed question ({@link #checkAfterAnswer(String, String)}) and never claim that the operator has answered. Each
 * item yields an entity task whose state declares {@code ask} and an envelope task with {@code ask:TASK-NNN}
 * ({@link #FORCE_ASK_PLAN_TASK}).</li>
 * <li>Q2 controls: the original descriptions, the {@code question/ask-or-proceed} items {@code *-original} of the same
 * group, unforced: their task text has no forcing sentence.</li>
 * <li>Q5: the {@code question/ask-or-proceed} items of the combined categories {@code 2ac}, {@code 2ap},
 * {@code 2cp}, each with two flaws, forced ({@link #FORCE_ASK_EACH}); the scripted reply answers only the question
 * asked first, or only restates the options.</li>
 * <li>R1 and R2: the probe only. R4: the probe for small work, and for large work only the scenario recipes
 * {@link #LARGE_SCENARIOS}, read at run time and read-only ({@code git show}, {@code git ls-tree}) from
 * {@value #SCENARIOS} of the branch {@code verification/question-items} of a local checkout of
 * {@code cuioss/plan-marshall-telemetry} ({@link #telemetry(Path, String)}), by default beside the main checkout
 * ({@link #siblingRepositories(Path)}); nothing of a recipe is written into this
 * repository. A recipe names the repository and its start commit, the archived plan and its asking task (whose title
 * and description are the worker's task), the reply to deliver, and the corpus item whose {@code input.choices} and
 * {@code target.answer} are the closed check ({@link #scenario(Source, String, Path)}). The worker directory is a new
 * repository fetched from the local clone of the recipe's repository and checked out at the start commit, so the
 * clone's checkout, branch and index stay untouched.</li>
 * <li>The probe task of the {@code resumability} stage of the profile verification: the worker reads a canary, asks,
 * and after the answer names the canary without reading it again.</li>
 * </ul>
 * A canary file {@code canary.txt} with a fresh token is written into every worker directory.
 */
final class QuestionFixtures {

    /** The directory of the scenario recipes on the telemetry branch. */
    static final String SCENARIOS = "verification/question-loop/scenarios";
    /** The verification corpus on the telemetry branch. */
    static final String CORPUS = "verification/corpus";
    /** The ref of the telemetry branch in the local checkout; {@code -Dpm.experiment.telemetry.ref} overrides it. */
    static final String TELEMETRY_REF = "origin/verification/question-items";
    /**
     * The scenario recipes of large work (R4); every other recipe is not used, because it needs tasks replayed from
     * records whose commits are gone, or outside state that has changed.
     */
    static final List<String> LARGE_SCENARIOS = List.of("q-api-vacuity-read-exceeds-dispatch",
            "q-pm-detector-residue-caller-root");
    /** The size of the probe's work before the ask. */
    static final String SMALL = "small";
    /** The size of a scenario's work before the ask. */
    static final String LARGE = "large";
    /** The canary file of a workspace. */
    static final String CANARY_FILE = "canary.txt";
    /** The marker of the operator's reply in an apply-answer item's facts. */
    static final String REPLY_MARKER = "\n\nOperator's answer:\n";
    /** The combined categories of Q5, each with two flaws. */
    static final Set<String> TWO_FLAWS = Set.of("2ac", "2ap", "2cp");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern VARIANT = Pattern.compile("variant (\\w+)");
    private static final String ITEMS = "items";
    private static final String ASK_OR_PROCEED = "question/ask-or-proceed";
    private static final String APPLY_ANSWER = "question/apply-answer";
    private static final String CHECK_SUBMIT = "answer:text[1..200],judgment:text[0..2000]";
    private static final String IMPLEMENT = "Implement the Python function below as its docstring describes, in the "
            + "file solution.py of your working directory.";

    /**
     * The forcing sentence of the apply-answer fixtures (Q1, Q3, Q4, Q6, Q7, R3): as the probe does, it states that the
     * choice is the operator's and unknown to the worker, so it must ask before acting. Added before any measured run.
     */
    static final String FORCE_ASK = "Its description is flawed: which behaviour the function should have is the "
            + "operator's choice, which you do not know, so you must ask the operator before you write any code.";
    /**
     * The forcing sentence of the two-flaw fixtures (Q5), one question per flaw. Added before any measured run.
     */
    static final String FORCE_ASK_EACH = "Its description has more than one flaw (ambiguous, inconsistent with its "
            + "examples, or incomplete): how to resolve each is the operator's choice, which you do not know, so you "
            + "must ask the operator about each, one question at a time, before you write the code that depends on "
            + "it.";
    /**
     * The plan task {@code TASK-001} of an envelope over a forced fixture: the choice is the operator's and unknown to
     * the worker, and nothing claims that the operator has answered. Added before any measured run.
     */
    static final String FORCE_ASK_PLAN_TASK = "Implement the function in solution.py as its contract in "
            + "facts.contract says. Where the contract is flawed, the choice is the operator's, which you do not know: "
            + "ask the operator (ask:TASK-001) before you write any code, and implement it as the answer in "
            + "facts.answers settles it.";
    /**
     * How the closed question of an apply-answer item ({@code input.question}) begins in the corpus: it claims the
     * operator has answered, which before the ask is not true.
     */
    static final String ANSWERED_FRAMING = "You asked the operator about the function below, and the operator "
            + "answered. Implement it as the answer settles it. ";
    /**
     * What replaces {@link #ANSWERED_FRAMING} in the facts before the ask: the check is the closed question to submit
     * after the answer, answered from the operator's answer in {@code facts.answers}.
     */
    static final String CHECK_AFTER_ANSWER = "The closed question to submit after the operator's answer: once the "
            + "answer is in facts.answers, implement the function as it settles it, then answer: ";

    /** How the operator answers a decision. */
    enum Reply {
        /** An answered option: the recommended option, with the fixture's reply as the note. */
        OPTION,
        /** An answer with a text: the fixture's reply as the operator's own option. */
        TEXT,
        /** An assumed default: the choice delegated to the asker's recommendation, with the reply as the note. */
        DEFAULT,
        /** Only the question asked first is answered (the recommended option); a second flaw stays open. */
        FIRST_ONLY,
        /** The reply only restates the question's options, so the flaw stays open. */
        RESTATE_OPTIONS
    }

    /**
     * The closed question submitted after the answer.
     *
     * @param question  the question
     * @param choices   its choices
     * @param reference the reference answer
     */
    record Check(String question, List<String> choices, String reference) {
    }

    /**
     * One fixture.
     *
     * @param id       the fixture id (the corpus item id or the scenario id)
     * @param category the variant category, {@code original} for a control, {@code probe} for the probe,
     *                 {@value #LARGE} for a scenario
     * @param task     the task text the worker gets
     * @param reply    the operator's reply
     * @param check    the closed question submitted after the answer
     * @param control  whether the fixture holds the answer in its facts, so the worker must not ask
     * @param checkout the repository the worker directory is checked out from, or {@code null}
     */
    record Fixture(String id, String category, String task, String reply, Check check, boolean control,
    Checkout checkout) {

        /** @return the size of the work before the ask: {@value #LARGE} for a scenario, else {@value #SMALL} */
        String size() {
            return checkout == null ? SMALL : LARGE;
        }
    }

    /**
     * The repository of a scenario.
     *
     * @param localClone the local clone of the scenario's repository, read only
     * @param commit     the start commit
     */
    record Checkout(Path localClone, String commit) {
    }

    /** The files of the telemetry branch, read only. */
    interface Source {

        /**
         * @param path a path on the branch
         * @return the file's content
         * @throws IOException           if it cannot be read
         * @throws IllegalStateException if the file is not on the branch
         */
        String read(String path) throws IOException;

        /**
         * @param directory a directory on the branch
         * @return the names of its entries
         * @throws IOException           if it cannot be read
         * @throws IllegalStateException if the directory is not on the branch
         */
        List<String> names(String directory) throws IOException;
    }

    private QuestionFixtures() {
    }

    /**
     * @param cache the corpus cache root
     * @return the fixtures of Q1 to Q4 and R3
     * @throws IOException if the cache cannot be read
     */
    static List<Fixture> applyAnswer(Path cache) throws IOException {
        var fixtures = new ArrayList<Fixture>();
        for (var item : items(cache, APPLY_ANSWER)) {
            var facts = item.path("input").path("facts").asText();
            var marker = facts.indexOf(REPLY_MARKER);
            if (marker < 0) {
                throw new IllegalStateException(item.path("id").asText() + " has no operator's answer");
            }
            var variant = facts.substring(0, marker);
            var reply = facts.substring(marker + REPLY_MARKER.length()).strip();
            var matcher = VARIANT.matcher(item.path("title").asText());
            var category = matcher.find() ? matcher.group(1) : "unknown";
            var task = IMPLEMENT + " " + FORCE_ASK + "\n\n" + variant;
            var check = check(item);
            check = new Check(checkAfterAnswer(check.question(), item.path("id").asText()), check.choices(),
                    check.reference());
            fixtures.add(new Fixture(item.path("id").asText(), category, task, reply, check, false, null));
        }
        return fixtures;
    }

    /**
     * The closed question of an apply-answer item as the facts carry it before the ask: {@link #ANSWERED_FRAMING}
     * replaced by {@link #CHECK_AFTER_ANSWER}.
     *
     * @param question the item's {@code input.question}
     * @param id       the item's id
     * @return the check question
     * @throws IllegalStateException if the question does not begin with {@link #ANSWERED_FRAMING}
     */
    static String checkAfterAnswer(String question, String id) {
        if (!question.startsWith(ANSWERED_FRAMING)) {
            throw new IllegalStateException(id + ": input.question does not begin with the answered framing: "
                    + "stopped instead of rewording it");
        }
        return CHECK_AFTER_ANSWER + question.substring(ANSWERED_FRAMING.length());
    }

    /**
     * @param cache the corpus cache root
     * @return the controls of Q2: the original description of every apply-answer item's group
     * @throws IOException if the cache cannot be read
     */
    static List<Fixture> controls(Path cache) throws IOException {
        var groups = new ArrayList<String>();
        for (var item : items(cache, APPLY_ANSWER)) {
            groups.add(item.path("group").asText());
        }
        var fixtures = new ArrayList<Fixture>();
        for (var item : items(cache, ASK_OR_PROCEED)) {
            if (item.path("id").asText().endsWith("-original") && groups.contains(item.path("group").asText())) {
                var task = IMPLEMENT + "\n\n" + item.path("input").path("facts").asText();
                fixtures.add(new Fixture(item.path("id").asText(), "original", task, "",
                        new Check("Which function did you implement? Give its name.", List.of(), ""), true, null));
            }
        }
        return fixtures;
    }

    /**
     * @param cache the corpus cache root
     * @return the fixtures of Q5: the items of the combined categories, each with two flaws
     * @throws IOException if the cache cannot be read
     */
    static List<Fixture> twoFlaws(Path cache) throws IOException {
        var fixtures = new ArrayList<Fixture>();
        for (var item : items(cache, ASK_OR_PROCEED)) {
            var category = category(item);
            if (TWO_FLAWS.contains(category)) {
                var task = IMPLEMENT + " " + FORCE_ASK_EACH + "\n\n" + item.path("input").path("facts").asText();
                fixtures.add(new Fixture(item.path("id").asText(), category, task, "", null, false, null));
            }
        }
        return fixtures;
    }

    /**
     * @param checkout a local checkout of {@code cuioss/plan-marshall-telemetry}, never written
     * @param ref      the ref of the branch {@code verification/question-items} in it
     * @return the branch's files, read by {@code git show} and {@code git ls-tree}
     */
    static Source telemetry(Path checkout, String ref) {
        return new Source() {
            @Override
            public String read(String path) throws IOException {
                return git(checkout, "show", ref + ":" + path);
            }

            @Override
            public List<String> names(String directory) throws IOException {
                var names = new ArrayList<String>();
                for (var line : git(checkout, "ls-tree", "--name-only", ref, directory + "/").split("\n")) {
                    if (!line.isBlank()) {
                        names.add(line.substring(line.lastIndexOf('/') + 1));
                    }
                }
                return names;
            }
        };
    }

    /**
     * @param source the telemetry branch
     * @param clones the directory of the local clones, each named as its repository
     * @return the scenarios of large work of R4, {@link #LARGE_SCENARIOS} in order
     * @throws IOException           if a file cannot be read
     * @throws IllegalStateException if a file or field is not where the recipe says
     */
    static List<Fixture> largeScenarios(Source source, Path clones) throws IOException {
        var fixtures = new ArrayList<Fixture>();
        for (var id : LARGE_SCENARIOS) {
            fixtures.add(scenario(source, id, clones));
        }
        return fixtures;
    }

    /**
     * @param source the telemetry branch
     * @return the ids of the recipes on the branch that are not used
     * @throws IOException if the directory cannot be read
     */
    static List<String> unusedScenarios(Source source) throws IOException {
        return source.names(SCENARIOS).stream().filter(name -> name.endsWith(".json"))
                .map(name -> name.substring(0, name.length() - ".json".length()))
                .filter(id -> !LARGE_SCENARIOS.contains(id)).sorted().toList();
    }

    /**
     * Builds a scenario of large work from its recipe: the worker's task is the title and description of the recipe's
     * asking task in its archived plan ({@code projects/<repository>/archived-plans/<plan_month>/<plan>/tasks/}), the
     * reply is {@code answer_to_deliver.text}, and the check is the recipe's corpus item: its {@code input.question},
     * and its {@code input.choices} with {@code target.answer} when the answer is one of them; otherwise the operator
     * answered with a new option, which the item's own-words counterpart must confirm as {@code new_option}, and the
     * recipe's {@code answer_to_deliver.branch_key} is added as a further choice and is the answer.
     *
     * @param source the telemetry branch
     * @param id     the scenario id
     * @param clones the directory of the local clones, each named as its repository
     * @return the fixture
     * @throws IOException           if a file cannot be read
     * @throws IllegalStateException if a file or field is not where the recipe says
     */
    static Fixture scenario(Source source, String id, Path clones) throws IOException {
        var recipePath = SCENARIOS + "/" + id + ".json";
        var recipe = json(source, recipePath);
        if (!id.equals(text(recipe, recipePath, "id"))) {
            throw new IllegalStateException(recipePath + " names another id");
        }
        var repository = text(recipe, recipePath, "repository");
        var name = repository.substring(repository.lastIndexOf('/') + 1).replaceFirst("\\.git$", "");
        var taskPath = "projects/" + name + "/archived-plans/" + text(recipe, recipePath, "plan_month") + "/"
                + text(recipe, recipePath, "plan") + "/tasks/" + text(recipe, recipePath, "asking_task") + ".json";
        var archived = json(source, taskPath);
        var task = "Your working directory is a checkout of the repository " + name + ". Work the task below of its "
                + "plan.\n\n" + text(archived, taskPath, "title") + "\n\n" + text(archived, taskPath, "description")
                + "\n\nBefore you start, read " + CANARY_FILE + " of your working directory; when you submit, name "
                + "its token as canary.";
        var check = scenarioCheck(source, text(recipe, recipePath, "item"),
                text(recipe, recipePath, "answer_to_deliver", "branch_key"));
        return new Fixture(id, LARGE, task, text(recipe, recipePath, "answer_to_deliver", "text"), check, false,
                new Checkout(clones.resolve(name), text(recipe, recipePath, "start_commit")));
    }

    private static Check scenarioCheck(Source source, String item, String branchKey) throws IOException {
        var itemPath = CORPUS + "/" + item + ".json";
        var question = json(source, itemPath);
        var choices = new ArrayList<String>();
        question.path("input").path("choices").forEach(choice -> choices.add(choice.asText()));
        if (choices.isEmpty()) {
            throw new IllegalStateException(itemPath + " has no input.choices: stopped instead of inventing them");
        }
        var questionText = text(question, itemPath, "input", "question");
        var answer = text(question, itemPath, "target", "answer");
        if (choices.contains(answer)) {
            if (!answer.equals(branchKey)) {
                throw new IllegalStateException(itemPath + ": target.answer is not the recipe's branch_key");
            }
            return new Check(questionText, List.copyOf(choices), answer);
        }
        if (choices.contains(branchKey)) {
            throw new IllegalStateException(itemPath + ": the recipe's branch_key is an offered choice, but "
                    + "target.answer is none of them");
        }
        var ownWordsPath = CORPUS + "/decision/own-words/" + item.substring(item.lastIndexOf('/') + 1) + ".json";
        if (!text(json(source, ownWordsPath), ownWordsPath, "target", "answer").startsWith("new_option")) {
            throw new IllegalStateException(ownWordsPath + " does not map the operator's answer to new_option");
        }
        choices.add(branchKey);
        return new Check(questionText, List.copyOf(choices), branchKey);
    }

    private static JsonNode json(Source source, String path) throws IOException {
        return QuestionStub.JSON.readTree(source.read(path));
    }

    private static String text(JsonNode node, String file, String... path) {
        var value = node;
        for (var field : path) {
            value = value.path(field);
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(file + " has no " + String.join(".", path)
                    + ": stopped instead of inventing it");
        }
        return value.asText();
    }

    /**
     * Runs git and returns its output.
     *
     * @param directory the repository, {@code null} for none
     * @param args      the arguments
     * @return the standard output
     * @throws IOException           if git cannot be run
     * @throws IllegalStateException if git fails
     */
    static String git(Path directory, String... args) throws IOException {
        var command = new ArrayList<String>();
        command.add("git");
        if (directory != null) {
            command.add("-C");
            command.add(directory.toString());
        }
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectError(ProcessBuilder.Redirect.PIPE).start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (process.waitFor() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " failed: " + err.strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted: " + String.join(" ", command), e);
        }
        return out;
    }

    /**
     * The directory that holds the main checkout of a repository and, beside it, the sibling repositories (the
     * telemetry checkout, the local clones of the scenarios): the parent of the main checkout, found from the git
     * common directory, so that a linked worktree (below {@code .plan/local/worktrees/}) resolves the same directory
     * as the main checkout.
     *
     * @param workingTree the main checkout or a linked worktree of the repository
     * @return the parent of the main checkout
     * @throws IOException           if git cannot be run
     * @throws IllegalStateException if git fails, or the common directory has no parent checkout
     */
    static Path siblingRepositories(Path workingTree) throws IOException {
        var common = workingTree.resolve(git(workingTree, "rev-parse", "--git-common-dir").strip()).normalize();
        var mainCheckout = common.getParent();
        if (mainCheckout == null || mainCheckout.getParent() == null) {
            throw new IllegalStateException("no main checkout above the git common directory " + common);
        }
        return mainCheckout.getParent();
    }

    /**
     * Checks a scenario's repository out into an empty worker directory: a new repository that fetches every ref of
     * the local clone (which only reads the clone) and checks out the start commit detached.
     *
     * @param checkout  the scenario's repository
     * @param directory the worker directory
     * @throws IOException if git cannot be run
     */
    static void checkout(Checkout checkout, Path directory) throws IOException {
        if (!Files.exists(checkout.localClone().resolve(".git"))) {
            throw new IllegalStateException("no local clone at " + checkout.localClone()
                    + ": set -Dpm.experiment.clones to the directory of the local clones");
        }
        git(null, "init", "--quiet", directory.toString());
        git(directory, "fetch", "--quiet", "--no-tags", checkout.localClone().toString(), "+refs/*:refs/source/*");
        git(directory, "checkout", "--quiet", "--detach", checkout.commit());
    }

    /**
     * @return the probe task of the {@code resumability} stage of the profile verification
     */
    static Fixture probe() {
        var task = "Read " + CANARY_FILE + " of your working directory and keep its token. Then write a greeting "
                + "into greeting.txt: whether it is formal or informal is the operator's choice, which you do not "
                + "know, so ask before you write it (decision kind approach). After the answer, write the greeting "
                + "and submit, naming the token of " + CANARY_FILE + " as canary without reading the file again.";
        return new Fixture("probe-resumability", "probe", task, "Formal.",
                new Check("Is the greeting formal or informal?", List.of("formal", "informal"), "formal"), false,
                null);
    }

    /**
     * Prepares a worker directory: checks out the fixture's repository, if it has one, and writes a fresh canary.
     *
     * @param fixture   the fixture
     * @param directory the worker directory
     * @return the canary token
     * @throws IOException if the directory cannot be written
     */
    static String prepare(Fixture fixture, Path directory) throws IOException {
        Files.createDirectories(directory);
        if (fixture.checkout() != null) {
            checkout(fixture.checkout(), directory);
        }
        var bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        var canary = "canary-" + HexFormat.of().formatHex(bytes);
        Files.writeString(directory.resolve(CANARY_FILE), canary + "\n", StandardCharsets.UTF_8);
        return canary;
    }

    /**
     * @param fixture the fixture
     * @param taskId  the queue task id
     * @param role    the role
     * @param form    how the task continues after a question
     * @return the task of a state that declares {@code ask}, closed by {@code submit} with the check's answer
     */
    static TaskSpec entityTask(Fixture fixture, String taskId, String role, Form form) {
        return TaskSpec.entity(taskId, role, "Work the task in facts.task", facts(fixture), CHECK_SUBMIT, form,
                List.of());
    }

    /**
     * @param fixture      the fixture
     * @param taskId       the queue task id
     * @param role         the role
     * @param form         how the task continues after a question
     * @param consultRoles the roles its state may consult
     * @return the task of a state that declares {@code ask} and {@code consult role} for each role
     */
    static TaskSpec consultingTask(Fixture fixture, String taskId, String role, Form form, List<String> consultRoles) {
        return TaskSpec.entity(taskId, role, "Work the task in facts.task", facts(fixture), CHECK_SUBMIT, form,
                consultRoles);
    }

    /**
     * @param fixture the fixture
     * @param taskId  the queue task id
     * @param role    the role
     * @param form    how the envelope task continues after a question
     * @return an envelope task: {@code TASK-001} implements ({@link #FORCE_ASK_PLAN_TASK} for a forced fixture),
     *         {@code TASK-002} answers the check
     */
    static TaskSpec envelopeTask(Fixture fixture, String taskId, String role, Form form) {
        var implement = fixture.control() ? "Implement the function in solution.py as its contract in facts.contract "
                + "says." : FORCE_ASK_PLAN_TASK;
        var planTasks = List.of(
                new PlanTask("TASK-001", implement, "summary:text[1..500]"),
                new PlanTask("TASK-002", "Answer the check question in facts.check_question with one of "
                        + "facts.check_choices, as your implementation computes it.", CHECK_SUBMIT));
        var facts = facts(fixture);
        facts.set("contract", facts.remove("task"));
        return TaskSpec.envelope(taskId, role, "Work the plan task in facts.task", facts, planTasks, form);
    }

    /**
     * Composes the operator's answer to a decision.
     *
     * @param fixture  the fixture
     * @param decision the decision's snapshot ({@link QuestionStub#decision(String)})
     * @param reply    how the operator answers
     * @return one answer per part
     */
    static List<PartAnswer> answer(Fixture fixture, JsonNode decision, Reply reply) {
        var answers = new ArrayList<PartAnswer>();
        var parts = decision.path("envelope").path("parts");
        var ids = decision.path("items");
        for (var index = 0; index < parts.size(); index++) {
            var part = parts.get(index);
            var questionId = questionId(decision, index, ids);
            var recommended = part.path("recommendation").path("branch_key").asText("own");
            var first = index == 0;
            answers.add(switch (reply) {
                case OPTION -> new PartAnswer(questionId, recommended, fixture.reply(), "chosen");
                case TEXT -> new PartAnswer(questionId, "own", fixture.reply(), "own_option");
                case DEFAULT -> new PartAnswer(questionId, recommended, fixture.reply(), "delegated");
                case FIRST_ONLY -> first ? new PartAnswer(questionId, recommended,
                        "This settles what you asked; nothing else is settled.", "chosen")
                        : new PartAnswer(questionId, "own", "Not settled by this answer.", "own_option");
                case RESTATE_OPTIONS -> new PartAnswer(questionId, "own", restate(part), "own_option");
            });
        }
        return answers;
    }

    private static String questionId(JsonNode decision, int index, JsonNode items) {
        if (items.has(index)) {
            return items.get(index).path("question_id").asText();
        }
        var first = Integer.parseInt(decision.path("id").asText().substring(2));
        return "Q-%03d".formatted(first + index);
    }

    private static String restate(JsonNode part) {
        var labels = new ArrayList<String>();
        part.path("options").forEach(option -> labels.add(option.path("label").asText()));
        return "It is one of these: " + String.join("; ", labels) + ".";
    }

    private static ObjectNode facts(Fixture fixture) {
        var facts = QuestionStub.JSON.createObjectNode();
        facts.put("task", fixture.task());
        if (fixture.check() != null) {
            facts.put("check_question", fixture.check().question());
            var choices = facts.putArray("check_choices");
            fixture.check().choices().forEach(choices::add);
        }
        return facts;
    }

    private static Check check(JsonNode item) {
        var choices = new ArrayList<String>();
        item.path("input").path("choices").forEach(choice -> choices.add(choice.asText()));
        return new Check(item.path("input").path("question").asText(), choices,
                item.path("target").path("answer").asText());
    }

    private static String category(JsonNode item) {
        for (var tag : item.path("tags")) {
            if (tag.asText().startsWith("category:")) {
                return tag.asText().substring("category:".length());
            }
        }
        var id = item.path("id").asText();
        return id.substring(id.lastIndexOf('-') + 1).toLowerCase(Locale.ROOT);
    }

    private static List<JsonNode> items(Path cache, String topic) throws IOException {
        var directory = cache.resolve(ITEMS).resolve(topic);
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("no items at " + directory
                    + ": generate the corpus cache with ./mvnw test -Pcorpus -pl pm-mcp-server -am");
        }
        var items = new ArrayList<JsonNode>();
        try (Stream<Path> entries = Files.list(directory)) {
            for (var file : entries.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                items.add(QuestionStub.JSON.readTree(file.toFile()));
            }
        }
        return items;
    }
}
