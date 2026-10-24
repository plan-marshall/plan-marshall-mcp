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
import java.util.Map;
import java.util.Optional;
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
 * repository that reads the object store of the local clone of the recipe's repository and is checked out at the start
 * commit, also one no ref of the clone contains, so the clone's checkout, refs, index and objects stay untouched
 * ({@link #checkout(Checkout, Path)}).</li>
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
    private static final String OWN = "own";
    private static final String OWN_OPTION = "own_option";
    private static final String OPTIONS = "options";
    private static final String BRANCH = "branch_key";
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

    /** How the operator answers a decision ({@link #composeAnswer}). */
    enum Reply {
        /**
         * An answered option: the recommended option, with the fixture's reply as the note; on a fixture with a
         * consistency hint an option that reads as the reference direction, else the reply as the operator's own
         * option.
         */
        OPTION,
        /** An answer with a text: the fixture's reply as the operator's own option. */
        TEXT,
        /**
         * An assumed default: the choice delegated to the asker's recommendation, with the reply as the note; on a
         * fixture with a consistency hint only a recommendation that reads as the reference direction, else the reply
         * as the operator's own option.
         */
        DEFAULT,
        /** Only the question asked first is answered (the recommended option); a second flaw stays open. */
        FIRST_ONLY,
        /** The reply only restates the question's options, so the flaw stays open. */
        RESTATE_OPTIONS
    }

    /** How an option of the asker reads against the reference direction of a fixture ({@link ConsistencyHint}). */
    enum Direction {
        /** The option states the direction the fixture's reply and reference settle. */
        CONSISTENT,
        /** The option states the opposite direction: chosen with the fixture's reply, the answer contradicts itself. */
        CONTRADICTING,
        /** The option states neither, or both. */
        UNCLEAR
    }

    /**
     * The consistency hint of a fixture whose reply asserts a direction: two regular expressions, matched
     * case-insensitively, one for an option text that states the reference direction and one for an option text that
     * states the opposite one. An option is read by its label first and, where its label states neither or both, by its
     * description ({@link #classify(JsonNode)}); an answer chooses only an option that reads as the reference direction
     * and whose description does not read as the opposite one ({@link #consistent(JsonNode)}). The expressions are
     * written from the fixture's reply and reference, and checked against the options the askers offered in the stored
     * runs (the {@code options} of the answer audit, {@link QuestionResults#answerAudit}), not guessed from a model's
     * wording at answer time. An option they cannot read either way is never chosen: the reply then goes as the
     * operator's own option.
     *
     * @param consistent    matches a text that states the reference direction
     * @param contradicting matches a text that states the opposite direction
     * @param reference     the reference direction in words, for the records
     */
    record ConsistencyHint(Pattern consistent, Pattern contradicting, String reference) {

        /**
         * @param option an option of the asker's envelope ({@code label}, {@code description})
         * @return how it reads: by its label, else by its description
         */
        Direction classify(JsonNode option) {
            var byLabel = classify(option.path("label").asText(""));
            return byLabel == Direction.UNCLEAR ? classify(option.path("description").asText("")) : byLabel;
        }

        /**
         * @param option an option of the asker's envelope
         * @return whether an answer may choose it: it reads as the reference direction ({@link #classify(JsonNode)})
         *         and its description does not read as the opposite direction
         */
        boolean consistent(JsonNode option) {
            return classify(option) == Direction.CONSISTENT
                    && classify(option.path("description").asText("")) != Direction.CONTRADICTING;
        }

        /**
         * @param text an option text
         * @return {@link Direction#CONSISTENT} when only the consistent expression matches,
         *         {@link Direction#CONTRADICTING} when only the contradicting one does or its last match starts after
         *         the last match of the consistent one ("compute forward, then reverse"), else
         *         {@link Direction#UNCLEAR}: neither, or a consistent term after a contradicting one ("reverse order,
         *         description as stated"), which is never read as consistent
         */
        Direction classify(String text) {
            var lastConsistent = lastMatch(consistent, text);
            var lastContradicting = lastMatch(contradicting, text);
            if (lastContradicting < 0) {
                return lastConsistent < 0 ? Direction.UNCLEAR : Direction.CONSISTENT;
            }
            return lastContradicting > lastConsistent ? Direction.CONTRADICTING : Direction.UNCLEAR;
        }

        private static int lastMatch(Pattern pattern, String text) {
            var matcher = pattern.matcher(text);
            var last = -1;
            while (matcher.find()) {
                last = matcher.start();
            }
            return last;
        }
    }

    /**
     * The consistency hints by fixture id: the apply-answer fixtures and the probe, whose replies assert a direction.
     * A fixture without one (the Q2 controls, never answered; the two-flaw fixtures of Q5, answered by
     * {@link Reply#FIRST_ONLY} and {@link Reply#RESTATE_OPTIONS}; the large scenarios of R4, whose replies are the
     * operator's own words of the recipe) is answered as before. Added after the Q4/Q6 OpenCode run on {@code he-106},
     * whose operator answer chose the asker's recommended branch (follow the example, descending) beside the fixture's
     * reply that the description is right and the order that of i, so the model followed the branch and the run was
     * scored as not following the answer.
     * <ul>
     * <li>{@code he-0} (has_close_elements, reference {@code False}): the reply settles "True if any two numbers in the
     * list are closer to each other than the threshold"; consistent are closer, within, below, less than, at most,
     * {@code <} or {@code ≤} the threshold, and any two numbers or all pairs; opposite are farther, further, larger,
     * greater, above, more than, at least the threshold or apart, far, exceeding, {@code >}, {@code ≥} or {@code !=} the
     * threshold, only consecutive or adjacent pairs, and any pair at all. The reference holds for {@code <} and
     * {@code ≤} alike (no two of its numbers are within 0.8).</li>
     * <li>{@code he-106} (f, reference {@code [1, 2, 6, 24, 15, 720, 28]}): the reply settles that the description is
     * right and the example wrong, the factorial of i for even i and the sum 1..i for odd i, i counted from 1, in the
     * order of i; consistent are the description, docstring, prose, text or literal reading, ascending, increasing,
     * forward or natural order, i from 1 or 1 to n, and the reference values {@code [1, 2, 6, 24, 15}; opposite are
     * the example (not "not the example", nor "the example is wrong" or "treated as erroneous"), descending,
     * decreasing, reversed (not "without reversal"), backward or from n down to 1 order, a swapped, flipped or inverted
     * parity rule (the factorial for odd i), a 0-based or 0-indexed i or parity (not a 0-based list position), and
     * the values of the other readings ({@code [15,}, {@code [28,}, {@code [1, 3, 6}, {@code [1, 1, 2}, {@code [0, 1},
     * {@code [120}).</li>
     * <li>{@code probe-resumability} (reference {@code formal}): the reply is {@code Formal.}; consistent is formal,
     * opposite informal or casual.</li>
     * </ul>
     */
    static final Map<String, ConsistencyHint> CONSISTENCY_HINTS = Map.of(
            "question/apply-answer/he-0", new ConsistencyHint(
                    Pattern.compile("closer|\\bclose\\b|within|below|less than|smaller|at most|no (?:farther|further|"
                            + "more)|any (?:two|pair)|all (?:possible )?pairs|<\\s*=?\\s*(?:the\\s+)?(?:threshold|t\\b)"
                            + "|≤", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("(?<!no )farther|(?<!no )further|larger|greater|above|(?<!no )more than|"
                            + "at least (?:the )?(?:threshold|t\\b)"
                            + "|at least .{0,20}apart|exceed|\\bfar\\b|consecutive|adjacent|at all|whenever|not exactly"
                            + "|>\\s*=?\\s*(?:the\\s+)?(?:threshold|t\\b)|≥|!=\\s*(?:the\\s+)?threshold|≠",
                            Pattern.CASE_INSENSITIVE),
                    "closer than the threshold"),
            "question/apply-answer/he-106", new ConsistencyHint(
                    Pattern.compile("descri|docstring|prose|\\btext\\b|wording|literal|ascend|increas|forward|"
                            + "natural order|in (?:that|index|normal) order|left[- ]to[- ]right|from 1\\b|\\b1 to n\\b|"
                            + "\\b1\\.\\.n\\b|\\[\\s*1,\\s*2,\\s*6,\\s*24,\\s*15", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("(?<!not )(?<!not the )(?<!no )exampl(?!es?(?:'s order)? (?:is |are |must be )?"
                            + "(?:treated )?(?:as )?(?:wrong|erroneous|incorrect|a typo))|descend|decreas|"
                            + "(?<!not )(?<!no )(?<!without )revers|backward|down to|\\bfrom n\\b|\\bn to 1\\b|"
                            + "\\bn\\.\\.1\\b|\\bn\\s*-\\s*[jk]\\b|largest i first|swap|flip|invert|opposite|"
                            + "0-based (?:index|indexing|parity|i)\\b|0-indexed|zero-based (?:index|i)\\b|"
                            + "i starts at 0|from 0\\b|\\bi\\s*=\\s*0\\b|(?:factorial|i!)(?:\\(i\\))? "
                            + "(?:for|on|when|if) (?:i is )?odd|odd i (?:gives|gets|uses) (?:the )?factorial|"
                            + "\\[\\s*15,|\\[\\s*28,|\\[\\s*1,\\s*3,\\s*6|\\[\\s*1,\\s*1,\\s*2|\\[\\s*0,\\s*1|"
                            + "\\[\\s*120", Pattern.CASE_INSENSITIVE),
                    "the description, in the order of i (ascending)"),
            "probe-resumability", new ConsistencyHint(
                    Pattern.compile("(?<!in)formal", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("informal|casual", Pattern.CASE_INSENSITIVE),
                    "formal"));

    /**
     * @param fixtureId a fixture id
     * @return its consistency hint, empty for a fixture without one
     */
    static Optional<ConsistencyHint> hint(String fixtureId) {
        return Optional.ofNullable(CONSISTENCY_HINTS.get(fixtureId));
    }

    /**
     * How the answer to one part was composed ({@link #composeAnswer}).
     *
     * @param questionId  the part's question id
     * @param branchKey   the branch the answer chose, {@code own} for the operator's own option
     * @param label       the label of the chosen option, empty for {@code own}
     * @param hinted      whether the fixture has a consistency hint
     * @param hintMatched whether the chosen option reads as the hint's reference direction
     * @param fallbackOwn whether no option of the asker matched the hint, so the reply went as the operator's own option
     */
    record PartNote(String questionId, String branchKey, String label, boolean hinted, boolean hintMatched,
    boolean fallbackOwn) {
    }

    /**
     * An operator's answer with how it was composed.
     *
     * @param parts one answer per part
     * @param notes one note per part, in the same order
     */
    record Composition(List<PartAnswer> parts, List<PartNote> notes) {

        /** @return whether any part fell back to the operator's own option */
        boolean fallbackOwn() {
            return notes.stream().anyMatch(PartNote::fallbackOwn);
        }
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
     * @param localClone the local clone of the scenario's repository, read only; it must hold the start commit, which
     *                   need not be contained in any of its refs
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

    /** A git command that failed: its command line and what git wrote to {@code stderr}. */
    static final class GitFailure extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        /** What git wrote to {@code stderr}, stripped. */
        private final String output;

        GitFailure(String command, String output) {
            super(command + " failed: " + output);
            this.output = output;
        }

        /** @return the first line of git's output, empty when it wrote none */
        String firstLine() {
            return output.lines().findFirst().orElse("");
        }
    }

    /**
     * The fixture or workspace setup of a run failed before any worker launched ({@link #prepare}): the scenario's
     * local clone is missing, or a git step of its checkout failed, for example a start commit that neither the
     * clone's object store nor the worker directory's holds. The run is recorded as {@value QuestionResults#SETUP_FAILED} with
     * {@link #reason()}, never as {@code no_ask}, timed out or launched.
     */
    static final class SetupFailure extends IOException {

        private static final long serialVersionUID = 1L;

        /** The first line of git's output, or the missing clone. */
        private final String reason;

        SetupFailure(String reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        /** @return the reason: the first line of git's output, or the missing clone */
        String reason() {
            return reason;
        }
    }

    /**
     * Runs git and returns its output.
     *
     * @param directory the repository, {@code null} for none
     * @param args      the arguments
     * @return the standard output
     * @throws IOException if git cannot be run
     * @throws GitFailure  if git fails
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
                throw new GitFailure(String.join(" ", command), err.strip());
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
     * Checks a scenario's repository out into an empty worker directory: a new repository that reads the objects of
     * the local clone through git's alternates ({@code .git/objects/info/alternates} names the clone's object
     * directory, {@code git rev-parse --git-path objects} in the clone) and checks out the start commit detached. A start
     * commit the clone holds is reached so even when no ref of the clone contains it (a commit known on the remote only
     * as the head of a merged pull request whose branch is gone), which fetching the clone's refs never brings.
     * <p>
     * The clone is only read: nothing is fetched into it, no ref, remote or configuration of it is written, and the
     * worker directory has no remote, so no push reaches it. Every object the worker writes (a commit, an index, a
     * stash) goes to the worker directory's own object store, never into the clone's. The worker directory is
     * otherwise a self-contained checkout, but it depends on the clone's object store while it exists: the clone must
     * not be garbage collected ({@code git gc}, {@code git prune}) during a run, or the worker directory loses the
     * objects of the start commit.
     *
     * @param checkout  the scenario's repository
     * @param directory the worker directory
     * @throws SetupFailure if there is no local clone
     * @throws GitFailure   if a git step fails, for example a start commit no object store holds
     * @throws IOException  if git cannot be run or the alternates file cannot be written
     */
    static void checkout(Checkout checkout, Path directory) throws IOException {
        var clone = checkout.localClone();
        if (!Files.exists(clone.resolve(".git"))) {
            var reason = "no local clone at " + clone;
            throw new SetupFailure(reason, reason + ": set -Dpm.experiment.clones to the directory of the local clones",
                    null);
        }
        var objects = clone.toAbsolutePath().resolve(git(clone, "rev-parse", "--git-path", "objects").strip())
                .normalize();
        git(null, "init", "--quiet", directory.toString());
        var info = directory.toAbsolutePath()
                .resolve(git(directory, "rev-parse", "--git-path", "objects/info").strip()).normalize();
        Files.createDirectories(info);
        Files.writeString(info.resolve("alternates"), objects + "\n", StandardCharsets.UTF_8);
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
     * Prepares a worker directory: checks out the fixture's repository, if it has one, and writes a fresh canary. A
     * checkout that fails (the local clone missing, a git step failed) is a {@link SetupFailure} whose reason is the
     * first line of git's output.
     *
     * @param fixture   the fixture
     * @param directory the worker directory
     * @return the canary token
     * @throws SetupFailure if the fixture's checkout fails
     * @throws IOException  if the directory cannot be written
     */
    static String prepare(Fixture fixture, Path directory) throws IOException {
        Files.createDirectories(directory);
        if (fixture.checkout() != null) {
            try {
                checkout(fixture.checkout(), directory);
            } catch (GitFailure e) {
                throw new SetupFailure(e.firstLine(), e.getMessage(), e);
            }
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
     * Composes the operator's answer to a decision ({@link #composeAnswer}).
     *
     * @param fixture  the fixture
     * @param decision the decision's snapshot ({@link QuestionStub#decision(String)})
     * @param reply    how the operator answers
     * @return one answer per part
     */
    static List<PartAnswer> answer(Fixture fixture, JsonNode decision, Reply reply) {
        return composeAnswer(fixture, decision, reply).parts();
    }

    /**
     * Composes the operator's answer to a decision so that it never contradicts itself: the reply text of an
     * apply-answer fixture asserts the reference direction, so a chosen branch must state the same direction.
     * <ul>
     * <li>{@link Reply#OPTION} ({@code chosen}) on a fixture with a consistency hint ({@link #hint(String)}): the asker's
     * recommended option when it reads as the reference direction, else the first of its options that does, with the
     * fixture's reply as the note; when none does, the reply goes as the operator's own option ({@code own},
     * {@code own_option}) and the part is marked {@link PartNote#fallbackOwn()}.</li>
     * <li>{@link Reply#DEFAULT} ({@code delegated}: the operator accepts the asker's recommendation) on such a fixture:
     * the recommendation when it reads as the reference direction; otherwise no delegation can carry the reply without
     * contradicting it, and the reply goes as the operator's own option, marked {@link PartNote#fallbackOwn()}. A
     * neutral delegation text with the reference taken from the recommendation is not used, because the evaluation
     * scores every run, {@code delegated} included, against the fixture's reference ({@code followsReference} of
     * {@link QuestionResults}): a delegation to a contradicting recommendation would be scored as not following.</li>
     * <li>A part without options (a value part) and every fixture without a hint: answered as before, the recommended
     * branch with the reply.</li>
     * <li>{@link Reply#TEXT}, {@link Reply#FIRST_ONLY} and {@link Reply#RESTATE_OPTIONS}: as before.</li>
     * </ul>
     * Where the recommended option reads as the reference direction, the answer is the one composed before the hint.
     *
     * @param fixture  the fixture
     * @param decision the decision's snapshot ({@link QuestionStub#decision(String)})
     * @param reply    how the operator answers
     * @return the answer per part and how each was composed
     */
    static Composition composeAnswer(Fixture fixture, JsonNode decision, Reply reply) {
        var answers = new ArrayList<PartAnswer>();
        var notes = new ArrayList<PartNote>();
        var parts = decision.path("envelope").path("parts");
        var ids = decision.path("items");
        var hint = hint(fixture.id());
        for (var index = 0; index < parts.size(); index++) {
            var part = parts.get(index);
            var questionId = questionId(decision, index, ids);
            var recommended = part.path("recommendation").path("branch_key").asText(OWN);
            var first = index == 0;
            var fallback = false;
            PartAnswer answer;
            switch (reply) {
                case OPTION, DEFAULT -> {
                    var delegated = reply == Reply.DEFAULT;
                    var choice = hint.isEmpty() || part.path(OPTIONS).isEmpty() ? Optional.of(recommended)
                            : consistentChoice(part, hint.get(), recommended, delegated);
                    fallback = choice.isEmpty();
                    answer = choice.map(key -> new PartAnswer(questionId, key, fixture.reply(),
                            delegated ? "delegated" : "chosen"))
                            .orElseGet(() -> new PartAnswer(questionId, OWN, fixture.reply(), OWN_OPTION));
                }
                case TEXT -> answer = new PartAnswer(questionId, OWN, fixture.reply(), OWN_OPTION);
                case FIRST_ONLY -> answer = first ? new PartAnswer(questionId, recommended,
                        "This settles what you asked; nothing else is settled.", "chosen")
                        : new PartAnswer(questionId, OWN, "Not settled by this answer.", OWN_OPTION);
                case RESTATE_OPTIONS -> answer = new PartAnswer(questionId, OWN, restate(part), OWN_OPTION);
                default -> throw new IllegalArgumentException("unknown reply " + reply);
            }
            answers.add(answer);
            var option = option(part, answer.branchKey());
            var matched = option.isPresent() && hint.map(value -> value.consistent(option.get())).orElse(false);
            notes.add(new PartNote(questionId, answer.branchKey(), option.map(value -> value.path("label").asText())
                    .orElse(""), hint.isPresent(), matched, fallback));
        }
        return new Composition(List.copyOf(answers), List.copyOf(notes));
    }

    /**
     * The branch an {@link Reply#OPTION} or {@link Reply#DEFAULT} answer chooses on a fixture with a consistency hint:
     * the recommended option when it reads as the reference direction; for {@link Reply#OPTION} otherwise the first
     * option in the asker's order that does; empty when none qualifies.
     *
     * @param part        the part, with options
     * @param hint        the fixture's hint
     * @param recommended the recommended branch
     * @param delegated   whether the operator delegates to the recommendation ({@link Reply#DEFAULT})
     * @return the branch, empty when the reply must go as the operator's own option
     */
    static Optional<String> consistentChoice(JsonNode part, ConsistencyHint hint, String recommended,
            boolean delegated) {
        var recommendedOption = option(part, recommended);
        if (recommendedOption.isPresent() && hint.consistent(recommendedOption.get())) {
            return Optional.of(recommended);
        }
        if (delegated) {
            return Optional.empty();
        }
        for (var option : part.path(OPTIONS)) {
            if (hint.consistent(option)) {
                return Optional.of(option.path(BRANCH).asText());
            }
        }
        return Optional.empty();
    }

    private static Optional<JsonNode> option(JsonNode part, String branchKey) {
        for (var option : part.path(OPTIONS)) {
            if (branchKey.equals(option.path(BRANCH).asText())) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
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
