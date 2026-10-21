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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.experiment.question.QuestionDriver.Host;

/**
 * The evaluation of the measurement (experiment only): reads, per run, the stub's event log and question records, the
 * relay's host-side log and the worker's output, computes the figures per item, host, model and form, and the verdict
 * against the item's pass criterion, and writes them to {@code target/verification-results/question-loop/<item>.json}.
 * <p>
 * The pass criteria are those of the work package {@code question-loop-verification} ({@link #CRITERIA}, quoted
 * verbatim), encoded before the first measured run and not changed by any result. No figure comes from what a model
 * reports about itself. Beside the figures, the result names the invocations whose run records it aggregates and
 * whether they ran at the specified scale ({@link #scale}); a smaller pass keeps its verdict, marked
 * {@value #NOT_AT_SCALE}.
 */
final class QuestionResults {

    /** The pass criterion of each item, verbatim from the work package. */
    static final Map<String, String> CRITERIA = Map.ofEntries(
            Map.entry("Q1", "At least 95 % of the asks accepted on the first submit or after one invalid_link_args "
                    + "refusal; no ask lost."),
            Map.entry("Q2", "No host question tool call and no prose question in any run; asks on at least 95 % of "
                    + "the Q1 fixtures; no guessed decision on any Q1 fixture; no ask on the control."),
            Map.entry("Q3", "Every run; time from the ask to the end or the next pm_wait recorded per host."),
            Map.entry("Q4", "The decision follows the answer in at least 95 % of the runs; no answered question "
                    + "asked again; no submitted plan task done again."),
            Map.entry("Q5", "A second ask on at least 90 % of the runs, never the same question twice; with the bound "
                    + "reached, every run ends in a submission that states the worker's judgment or an explicit "
                    + "infeasible report, with no lost task and no prose question."),
            Map.entry("Q6", "Every vote valid within the task protocol's re-offer budget; the decided answer applied "
                    + "as in Q4; consultation and question never mixed up."),
            Map.entry("Q7", "No worker or slot held while the record is open; the answer delivered exactly once "
                    + "after the restart."),
            Map.entry("R1", "Continued in the same generation in at least 95 % of the runs per measured duration; the "
                    + "longest duration that passes is the host's max_hold_seconds."),
            Map.entry("R2", "Continued from the session in at least 95 % of the runs per age; time to the "
                    + "acknowledgement recorded; the oldest age that passes is the host's max_age_seconds; no hung "
                    + "resume."),
            Map.entry("R3", "Every fault detected by the missing answer acknowledgement within the host's "
                    + "acknowledgement deadline (by silence where the acknowledgement is informational), never "
                    + "earlier; the task then resumed or offered again with facts.answers exactly once."),
            Map.entry("R4", "No criterion: the figures set the shipped questions.hold_seconds and "
                    + "resume_within_seconds per role, and whether a role continues at all."));

    /** The share of first-time or once-refused accepted asks (Q1), of asks on the Q1 fixtures (Q2). */
    static final double ASK_SHARE = 0.95;
    /** The share of decisions that follow the answer (Q4, Q6). */
    static final double FOLLOW_SHARE = 0.95;
    /** The share of runs with a second ask (Q5). */
    static final double SECOND_ASK_SHARE = 0.90;
    /** The share of continued runs per duration or age (R1, R2). */
    static final double CONTINUE_SHARE = 0.95;
    /** The re-offer budget of a vote task (Q6): refused submits a vote may take and still count valid. */
    static final int VOTE_REOFFER_BUDGET = 2;
    /** How late after the deadline a detection still counts within it: the supervisor's poll and termination. */
    static final long DETECTION_TOLERANCE_MS = 15_000;
    /** How long a resume of a missing or over-age session may take before it counts as hung (R2). */
    static final long HUNG_RESUME_MS = 60_000;
    /** A negative resume (R2) that started a new session instead of failing (Antigravity). */
    static final String NEGATIVE_NEW_SESSION = "negative_new_session";
    /** A negative resume (R2) that failed at once, as the specification expects. */
    static final String FAILED_AT_ONCE = "failed_at_once";
    /** A negative resume (R2) that neither ended nor started a new session within {@link #HUNG_RESUME_MS}. */
    static final String HUNG = "hung";
    /** A negative resume (R2) that ended at once with exit 0 and no new session. */
    static final String EXITED_WITHOUT_FAILURE = "exited_without_failure";

    /** The field of a run record and of an invocation record that names the invocation. */
    static final String INVOCATION = "invocation";
    /** The runs per fixture and host the items are specified with. */
    static final int SPECIFIED_RUNS = 20;
    /** The runs per large scenario, host and form R4 is specified with. */
    static final int SPECIFIED_LARGE_RUNS = 3;
    /** The mark of a verdict from a pass smaller than specified. */
    static final String NOT_AT_SCALE = "not at the specified scale";

    private static final String UNKNOWN_INVOCATION = "unknown";
    private static final String EVENT = "event";
    private static final String WORKER = "worker";
    private static final String GENERATION = "generation";
    private static final String HOST = "host";
    private static final String RUNS = "runs";
    private static final String ASKED = "asked";
    private static final String SUBMITTED = "submitted";
    private static final String DECISION = "decision";
    private static final String TASK = "task";
    private static final String VERDICT = "verdict";

    private QuestionResults() {
    }

    /**
     * One run as the evaluation reads it.
     *
     * @param run     the run record
     * @param events  the stub's events of the run
     * @param records the stub's question records of the run
     * @param outputs the worker outputs of the run: each line with its arrival time ({@code at}) and its text
     *                ({@code line})
     */
    record RunView(JsonNode run, List<JsonNode> events, List<JsonNode> records, List<JsonNode> outputs) {

        List<JsonNode> named(String name) {
            return events.stream().filter(event -> name.equals(event.path(EVENT).asText())).toList();
        }

        Host host() {
            return Host.of(run.path(HOST).asText());
        }
    }

    /**
     * @param file a JSON Lines file
     * @return its lines, parsed; none when the file does not exist
     * @throws IOException if it cannot be read or a line is no JSON
     */
    static List<JsonNode> readJsonLines(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        var nodes = new ArrayList<JsonNode>();
        for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                nodes.add(QuestionStub.JSON.readTree(line));
            }
        }
        return nodes;
    }

    /**
     * Appends the record of a run to {@code runs.jsonl} of the results directory.
     *
     * @param resultsDir the results directory of the measurement
     * @param run        the run record
     * @throws IOException if it cannot be written
     */
    static void recordRun(Path resultsDir, ObjectNode run) throws IOException {
        Files.createDirectories(resultsDir);
        Files.writeString(resultsDir.resolve("runs.jsonl"), QuestionStub.JSON.writeValueAsString(run) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * @param resultsDir the results directory of the measurement
     * @param item       an item
     * @return the run records of the item
     * @throws IOException if they cannot be read
     */
    static List<JsonNode> runs(Path resultsDir, String item) throws IOException {
        return readJsonLines(resultsDir.resolve("runs.jsonl")).stream()
                .filter(run -> item.equals(run.path("item").asText())).toList();
    }

    /**
     * Appends the record of an invocation to {@code invocations.jsonl} of the results directory.
     *
     * @param resultsDir the results directory of the measurement
     * @param invocation the invocation record ({@link QuestionLoopExperiment#invocation})
     * @throws IOException if it cannot be written
     */
    static void recordInvocation(Path resultsDir, ObjectNode invocation) throws IOException {
        Files.createDirectories(resultsDir);
        Files.writeString(resultsDir.resolve("invocations.jsonl"),
                QuestionStub.JSON.writeValueAsString(invocation) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * @param resultsDir the results directory of the measurement
     * @param item       an item
     * @return the invocation records of the item
     * @throws IOException if they cannot be read
     */
    static List<JsonNode> invocations(Path resultsDir, String item) throws IOException {
        return readJsonLines(resultsDir.resolve("invocations.jsonl")).stream()
                .filter(invocation -> item.equals(invocation.path("item").asText())).toList();
    }

    /**
     * Adds the bookkeeping of an item's result: the invocations whose run records it aggregates (with their settings
     * and record counts; a record without an invocation id counts under {@code unknown}), the number of run records
     * and of fixtures, and {@code atSpecifiedScale}, true only when every aggregated invocation is recorded and ran at
     * the specified scale ({@link #atSpecifiedScale(String, JsonNode)}) and, for R4, its small and its large work were
     * both run so. A result that is not at the specified scale carries {@code verdict_scale}: {@value #NOT_AT_SCALE}.
     * The verdict itself is not changed.
     *
     * @param item        the item
     * @param result      its result ({@link #evaluate(String, List)})
     * @param runs        the run records it was evaluated from
     * @param invocations the invocation records of the item
     */
    static void scale(String item, ObjectNode result, List<JsonNode> runs, List<JsonNode> invocations) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var run : runs) {
            var id = run.path(INVOCATION).asText("");
            counts.merge(id.isEmpty() ? UNKNOWN_INVOCATION : id, 1, Integer::sum);
        }
        var known = new HashMap<String, JsonNode>();
        invocations.forEach(invocation -> known.put(invocation.path(INVOCATION).asText(), invocation));
        var array = result.putArray("invocations");
        var atScale = !counts.isEmpty();
        var small = false;
        var large = false;
        for (var entry : counts.entrySet()) {
            var recorded = known.get(entry.getKey());
            var node = array.addObject();
            node.put(INVOCATION, entry.getKey());
            node.put("run_records", entry.getValue());
            if (recorded == null) {
                node.put("settings", "not recorded");
                atScale = false;
                continue;
            }
            node.set("settings", recorded);
            var invocationAtScale = atSpecifiedScale(item, recorded);
            node.put("atSpecifiedScale", invocationAtScale);
            atScale &= invocationAtScale;
            small |= recorded.path(RUNS).asInt() == SPECIFIED_RUNS;
            large |= recorded.path("large_runs").asInt() == SPECIFIED_LARGE_RUNS;
        }
        if ("R4".equals(item)) {
            atScale &= small && large;
        }
        result.put("run_records", runs.size());
        result.put("fixtures", runs.stream().map(run -> run.path("fixture").asText()).distinct().count());
        result.put("atSpecifiedScale", atScale);
        if (!atScale) {
            result.put("verdict_scale", NOT_AT_SCALE);
        }
    }

    /**
     * Whether one invocation ran at the specified scale: no fixture limit, and {@value #SPECIFIED_RUNS} runs per
     * fixture and host; for R4, whose small and large work may run as separate invocations, each part either not run
     * ({@code 0}) or run at its specified count ({@value #SPECIFIED_RUNS} small, {@value #SPECIFIED_LARGE_RUNS} large).
     *
     * @param item       the item
     * @param invocation the invocation record
     * @return whether it ran at the specified scale
     */
    static boolean atSpecifiedScale(String item, JsonNode invocation) {
        if (!invocation.path("fixtures_limit").isNull() && !invocation.path("fixtures_limit").isMissingNode()) {
            return false;
        }
        var runs = invocation.path(RUNS).asInt(-1);
        if ("R4".equals(item)) {
            var largeRuns = invocation.path("large_runs").asInt(-1);
            return (runs == 0 || runs == SPECIFIED_RUNS) && (largeRuns == 0 || largeRuns == SPECIFIED_LARGE_RUNS)
                    && runs + largeRuns > 0;
        }
        return runs == SPECIFIED_RUNS;
    }

    /**
     * Evaluates an item: groups its runs by host, model, form, continuation and the item's measured parameter, and
     * computes each group's figures and verdict.
     *
     * @param item the item ({@code Q1} to {@code Q7}, {@code R1} to {@code R4})
     * @param runs its run records
     * @return the result: {@code item}, {@code criterion}, {@code groups[]}, {@code verdict} ({@code pass},
     *         {@code fail}, or {@code none} for R4), and the derived values of R1 and R2
     * @throws IOException if a run's files cannot be read
     */
    static ObjectNode evaluate(String item, List<JsonNode> runs) throws IOException {
        var groups = new TreeMap<String, List<RunView>>();
        for (var run : runs) {
            groups.computeIfAbsent(groupKey(item, run), _ -> new ArrayList<>()).add(view(run));
        }
        var result = QuestionStub.JSON.createObjectNode();
        result.put("item", item);
        result.put("criterion", CRITERIA.get(item));
        result.put("os", System.getProperty("os.name"));
        var array = result.putArray("groups");
        var pass = !groups.isEmpty();
        for (var entry : groups.entrySet()) {
            var group = evaluateGroup(item, entry.getValue());
            group.put("group", entry.getKey());
            array.add(group);
            pass &= !"fail".equals(group.path(VERDICT).asText());
        }
        result.put(VERDICT, "R4".equals(item) ? "none" : pass ? "pass" : "fail");
        if ("R1".equals(item) || "R2".equals(item)) {
            result.set("R1".equals(item) ? "max_hold_seconds" : "max_age_seconds", longestPassing(array,
                    "R1".equals(item) ? "duration_s" : "age_s"));
        }
        return result;
    }

    /**
     * Writes an item's result to {@code target/verification-results/question-loop/<item>.json}.
     *
     * @param item   the item
     * @param result its result
     * @return the file written
     * @throws IOException if it cannot be written
     */
    static Path write(String item, ObjectNode result) throws IOException {
        var directory = Files.createDirectories(Path.of("target", "verification-results", "question-loop"));
        var file = directory.resolve(item + ".json");
        Files.writeString(file, QuestionStub.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n",
                StandardCharsets.UTF_8);
        return file;
    }

    // --- groups ---

    private static String groupKey(String item, JsonNode run) {
        var key = new StringBuilder();
        for (var field : List.of(HOST, "model", "form", "continuation")) {
            key.append(field).append('=').append(run.path(field).asText()).append(' ');
        }
        var parameter = switch (item) {
            case "Q4" -> "reply";
            case "Q5" -> "bound";
            case "Q6" -> "kind";
            case "R1" -> "duration_s";
            case "R2" -> "age_s";
            case "R3" -> "fault";
            case "R4" -> "size";
            default -> null;
        };
        if (parameter != null) {
            key.append(parameter).append('=').append(run.path(parameter).asText());
        }
        if ("R4".equals(item)) {
            key.append(" fixture=").append(run.path("fixture").asText());
        }
        return key.toString().strip();
    }

    private static ObjectNode evaluateGroup(String item, List<RunView> views) throws IOException {
        var group = QuestionStub.JSON.createObjectNode();
        var first = views.getFirst().run();
        for (var field : List.of(HOST, "model", "form", "continuation", "duration_s", "age_s", "fault", "size")) {
            if (first.has(field)) {
                group.set(field, first.get(field));
            }
        }
        if ("R4".equals(item)) {
            group.set("fixture", first.get("fixture"));
        }
        group.put(RUNS, views.size());
        var pass = switch (item) {
            case "Q1" -> q1(views, group);
            case "Q2" -> q2(views, group);
            case "Q3" -> q3(views, group);
            case "Q4" -> q4(views, group);
            case "Q5" -> q5(views, group);
            case "Q6" -> q6(views, group);
            case "Q7" -> q7(views, group);
            case "R1" -> r1(views, group);
            case "R2" -> r2(views, group);
            case "R3" -> r3(views, group);
            case "R4" -> r4(views, group);
            default -> throw new IllegalArgumentException("unknown item " + item);
        };
        group.put(VERDICT, "R4".equals(item) ? "none" : pass ? "pass" : "fail");
        return group;
    }

    private static boolean q1(List<RunView> views, ObjectNode group) {
        var asks = 0;
        var accepted = 0;
        var afterOneRefusal = 0;
        var lost = 0;
        for (var view : views) {
            var refusals = 0;
            for (var event : view.events()) {
                var name = event.path(EVENT).asText();
                if ("ask_refused".equals(name)) {
                    refusals++;
                } else if (ASKED.equals(name)) {
                    asks++;
                    if (refusals == 0) {
                        accepted++;
                    } else if (refusals == 1) {
                        afterOneRefusal++;
                    }
                    refusals = 0;
                }
            }
            if (refusals > 0) {
                asks++;
            }
            var recorded = view.records().stream().filter(line -> ASKED.equals(line.path("line").asText())).count();
            lost += (int) Math.max(0, view.named(ASKED).size() - recorded);
        }
        var share = share(accepted + afterOneRefusal, asks);
        group.put("asks", asks);
        group.put("accepted_first", accepted);
        group.put("accepted_after_one_refusal", afterOneRefusal);
        group.put("accepted_share", share);
        group.put("asks_lost", lost);
        return asks > 0 && share >= ASK_SHARE && lost == 0;
    }

    private static boolean q2(List<RunView> views, ObjectNode group) {
        var hostToolCalls = 0;
        var prose = 0;
        var fixtures = 0;
        var asked = 0;
        var guesses = 0;
        var controls = 0;
        var controlAsks = 0;
        var behaviour = new TreeMap<String, Integer>();
        for (var view : views) {
            var calls = hostToolCalls(view);
            hostToolCalls += calls;
            if (calls > 0) {
                behaviour.merge(hostToolBehaviour(view), 1, Integer::sum);
            }
            if (finalText(view).strip().endsWith("?")) {
                prose++;
            }
            var askedHere = !view.named(ASKED).isEmpty();
            if (view.run().path("control").asBoolean()) {
                controls++;
                controlAsks += askedHere ? 1 : 0;
            } else {
                fixtures++;
                asked += askedHere ? 1 : 0;
                guesses += !askedHere && !view.named(SUBMITTED).isEmpty() ? 1 : 0;
            }
        }
        var share = share(asked, fixtures);
        group.put("host_question_tool_calls", hostToolCalls);
        group.set("host_question_tool_behaviour", QuestionStub.JSON.valueToTree(behaviour));
        group.put("prose_questions", prose);
        group.put("q1_fixture_runs", fixtures);
        group.put("ask_share", share);
        group.put("guessed_decisions", guesses);
        group.put("control_runs", controls);
        group.put("control_asks", controlAsks);
        return hostToolCalls == 0 && prose == 0 && fixtures > 0 && share >= ASK_SHARE && guesses == 0
                && controlAsks == 0;
    }

    private static boolean q3(List<RunView> views, ObjectNode group) {
        var stopped = 0;
        var unchanged = 0;
        var times = new ArrayList<Long>();
        for (var view : views) {
            var asked = view.named(ASKED).stream().findFirst();
            if (asked.isEmpty()) {
                continue;
            }
            var at = asked.get().path("at").asLong();
            var worker = asked.get().path(WORKER).asText();
            var generation = asked.get().path(GENERATION).asInt();
            var warm = "warm".equals(view.run().path("form").asText());
            var end = view.events().stream()
                    .filter(event -> event.path("at").asLong() >= at && same(event, worker, generation))
                    .filter(event -> warm ? "wait_started".equals(event.path(EVENT).asText())
                            : "worker_exited".equals(event.path(EVENT).asText()))
                    .findFirst();
            if (end.isPresent()) {
                stopped++;
                times.add(end.get().path("at").asLong() - at);
            }
            var digests = view.events().stream().filter(event -> "worktree_digest".equals(event.path(EVENT).asText()))
                    .filter(event -> same(event, worker, generation)).toList();
            if (digests.size() >= 2
                    && digests.getFirst().path("digest").asText().equals(digests.getLast().path("digest").asText())) {
                unchanged++;
            }
        }
        group.put("stopped", stopped);
        group.put("worktree_unchanged", unchanged);
        group.put("ask_to_end_ms_median", median(times));
        group.put("ask_to_end_ms_max", times.stream().mapToLong(Long::longValue).max().orElse(-1));
        return stopped == views.size() && unchanged == views.size();
    }

    private static boolean q4(List<RunView> views, ObjectNode group) {
        var follows = 0;
        var reasked = 0;
        var redone = 0;
        for (var view : views) {
            follows += followsReference(view) ? 1 : 0;
            reasked += reasked(view);
            redone += redone(view);
        }
        var share = share(follows, views.size());
        group.put("follows_answer_share", share);
        group.put("answered_question_asked_again", reasked);
        group.put("submitted_plan_task_done_again", redone);
        return share >= FOLLOW_SHARE && reasked == 0 && redone == 0;
    }

    private static boolean q5(List<RunView> views, ObjectNode group) {
        var bound = views.getFirst().run().path("bound").asBoolean();
        var second = 0;
        var repeated = 0;
        var judged = 0;
        var lost = 0;
        var prose = 0;
        for (var view : views) {
            var texts = new ArrayList<String>();
            view.named(ASKED).forEach(event -> event.path("question_texts").forEach(text -> texts
                    .add(normalize(text.asText()))));
            if (new HashSet<>(texts).size() < texts.size()) {
                repeated++;
            }
            if (new HashSet<>(texts).size() >= 2) {
                second++;
            }
            var lastSubmit = lastSubmitArgs(view);
            if (!lastSubmit.path("judgment").asText("").isBlank() || !view.named("infeasible").isEmpty()) {
                judged++;
            }
            lost += (int) view.named("closed").stream().filter(event -> "lost".equals(event.path("outcome").asText()))
                    .count();
            prose += finalText(view).strip().endsWith("?") ? 1 : 0;
        }
        group.put("bound_reached", bound);
        group.put("second_ask_share", share(second, views.size()));
        group.put("same_question_twice", repeated);
        group.put("ended_with_judgment_or_infeasible", judged);
        group.put("lost_tasks", lost);
        group.put("prose_questions", prose);
        if (bound) {
            return judged == views.size() && lost == 0 && prose == 0;
        }
        return share(second, views.size()) >= SECOND_ASK_SHARE && repeated == 0;
    }

    private static boolean q6(List<RunView> views, ObjectNode group) {
        var votes = 0;
        var validVotes = 0;
        var applied = 0;
        var decided = 0;
        var mixups = 0;
        for (var view : views) {
            var refusals = new HashMap<String, Integer>();
            view.named("vote_refused").forEach(event -> refusals.merge(event.path(TASK).asText(), 1, Integer::sum));
            for (var closed : view.named("closed")) {
                var task = closed.path(TASK).asText();
                if (task.startsWith("vote-")) {
                    votes++;
                    if (SUBMITTED.equals(closed.path("outcome").asText())
                            && refusals.getOrDefault(task, 0) <= VOTE_REOFFER_BUDGET) {
                        validVotes++;
                    }
                }
            }
            decided += (int) view.named("adjudicated").stream()
                    .filter(event -> "decided".equals(event.path("outcome").asText())).count();
            applied += followsReference(view) ? 1 : 0;
            mixups += mixups(view);
        }
        var share = share(applied, views.size());
        group.put("votes", votes);
        group.put("valid_votes", validVotes);
        group.put("decided_by_quorum", decided);
        group.put("applied_share", share);
        group.put("consultation_question_mixups", mixups);
        return validVotes == votes && share >= FOLLOW_SHARE && mixups == 0;
    }

    private static boolean q7(List<RunView> views, ObjectNode group) {
        var held = 0;
        var recycled = 0;
        var restarted = 0;
        var exactlyOnce = 0;
        for (var view : views) {
            var asked = view.named(ASKED).stream().findFirst();
            var answered = view.named("answer_recorded").stream().findFirst();
            if (asked.isEmpty() || answered.isEmpty()) {
                continue;
            }
            var from = asked.get().path("at").asLong();
            var to = answered.get().path("at").asLong();
            var entry = view.run().path("task_id").asText();
            held += (int) view.events().stream().filter(event -> within(event, from, to))
                    .filter(event -> "offered".equals(event.path(EVENT).asText())
                            || "answer_delivered".equals(event.path(EVENT).asText()))
                    .filter(event -> event.path(TASK).asText().startsWith(entry)).count();
            var warm = view.run().path("warm_worker").asText("");
            if (view.events().stream().filter(event -> within(event, from, to))
                    .anyMatch(event -> warm.equals(event.path(WORKER).asText())
                            && ("worker_exited".equals(event.path(EVENT).asText())
                            || "wait_end".equals(event.path(EVENT).asText())))) {
                recycled++;
            }
            if (view.events().stream().filter(event -> within(event, from, to))
                    .anyMatch(event -> "stub_restart".equals(event.path(EVENT).asText()))) {
                restarted++;
            }
            var decision = asked.get().path(DECISION).asText();
            var continued = view.named("continued").stream()
                    .filter(event -> decision.equals(event.path(DECISION).asText())).toList();
            if (continued.size() == 1) {
                var task = continued.getFirst().path(TASK).asText();
                var offers = view.named("offered").stream().filter(event -> task.equals(event.path(TASK).asText()))
                        .count();
                exactlyOnce += offers == 1 ? 1 : 0;
            }
        }
        group.put("leases_while_open", held);
        group.put("warm_worker_recycled", recycled);
        group.put("stub_restarted_while_open", restarted);
        group.put("answer_delivered_exactly_once", exactlyOnce);
        return held == 0 && recycled == views.size() && restarted == views.size() && exactlyOnce == views.size();
    }

    private static boolean r1(List<RunView> views, ObjectNode group) {
        var continued = 0;
        var reread = 0;
        for (var view : views) {
            var asked = view.named(ASKED).stream().findFirst();
            if (asked.isEmpty()) {
                continue;
            }
            var worker = asked.get().path(WORKER).asText();
            var generation = asked.get().path(GENERATION).asInt();
            var acknowledged = view.named("acknowledged").stream()
                    .anyMatch(event -> "answer".equals(event.path("kind").asText())
                            && same(event, worker, generation));
            var rereads = rereadsCanary(view, asked.get().path("at").asLong());
            reread += rereads ? 1 : 0;
            if (acknowledged && namesCanary(view) && !rereads) {
                continued++;
            }
        }
        var share = share(continued, views.size());
        group.put("continued_same_generation_share", share);
        group.put("canary_read_again", reread);
        return share >= CONTINUE_SHARE;
    }

    private static boolean r2(List<RunView> views, ObjectNode group) {
        var continued = 0;
        var positive = 0;
        var hung = 0;
        var outcomes = new TreeMap<String, Integer>();
        var ackTimes = new ArrayList<Long>();
        for (var view : views) {
            if (view.run().path("negative").asBoolean()) {
                var outcome = negativeOutcome(view);
                outcomes.merge(outcome, 1, Integer::sum);
                hung += HUNG.equals(outcome) ? 1 : 0;
                continue;
            }
            positive++;
            var acknowledged = view.named("acknowledged").stream()
                    .filter(event -> "answer".equals(event.path("kind").asText())).findFirst();
            var resumed = view.named("answer_delivered").stream().anyMatch(event -> event.path("resumed").asBoolean());
            if (resumed && acknowledged.isPresent() && namesCanary(view)) {
                continued++;
                ackTimes.add(acknowledged.get().path("after_ms").asLong());
            }
        }
        var share = share(continued, positive);
        group.put("continued_from_session_share", share);
        group.put("ack_after_ms_median", median(ackTimes));
        group.set("negative_outcomes", QuestionStub.JSON.valueToTree(outcomes));
        group.put("hung_resumes", hung);
        return (positive == 0 || share >= CONTINUE_SHARE) && hung == 0;
    }

    private static String negativeOutcome(RunView view) {
        var started = view.named("worker_started").stream().filter(event -> event.path("resumed").asBoolean())
                .findFirst();
        var exited = started.flatMap(start -> view.named("worker_exited").stream()
                .filter(event -> same(event, start.path(WORKER).asText(), start.path(GENERATION).asInt()))
                .findFirst());
        var exitAfter = exited.map(event -> event.path("at").asLong() - started.get().path("at").asLong())
                .orElse(-1L);
        return negativeOutcome(view.run().path(NEGATIVE_NEW_SESSION).asBoolean(), started.isPresent(), exitAfter,
                exited.map(event -> event.path("exit").asInt()).orElse(-1));
    }

    /**
     * Classifies the resume of a missing or over-age session (R2). A new session comes first: the harness neither
     * failed nor hung, it worked on a conversation of its own (Antigravity), however long that took. Otherwise a
     * non-zero exit within {@link #HUNG_RESUME_MS} failed at once; no exit, or one later than that, is hung; an exit 0
     * within it with no new session is recorded as such.
     *
     * @param newSession whether the generation started a new session
     * @param started    whether the resumed generation was started
     * @param exitAfter  the milliseconds from its start to its exit, {@code -1} when it did not exit
     * @param exitCode   its exit code, {@code -1} when it did not exit
     * @return {@link #NEGATIVE_NEW_SESSION}, {@link #FAILED_AT_ONCE}, {@link #HUNG} or {@link #EXITED_WITHOUT_FAILURE}
     */
    static String negativeOutcome(boolean newSession, boolean started, long exitAfter, int exitCode) {
        if (started && newSession) {
            return NEGATIVE_NEW_SESSION;
        }
        if (!started || exitAfter < 0 || exitAfter > HUNG_RESUME_MS) {
            return HUNG;
        }
        return exitCode == 0 ? EXITED_WITHOUT_FAILURE : FAILED_AT_ONCE;
    }

    /**
     * @param stderr    what a resumed generation wrote to {@code stderr}
     * @param sessionId the session id it was told to resume
     * @return whether it warned that the session was not found, as Antigravity does before it starts a new one
     *         ({@code warning: conversation "<id>" not found})
     */
    static boolean warnsMissingSession(String stderr, String sessionId) {
        return stderr.contains("conversation \"" + sessionId + "\" not found");
    }

    private static boolean r3(List<RunView> views, ObjectNode group) {
        var detected = 0;
        var early = 0;
        var once = 0;
        var times = new ArrayList<Long>();
        for (var view : views) {
            var host = view.host();
            var deadline = (host.ackInformational() ? host.silenceLimit() : host.ackDeadline()).toMillis();
            var detection = view.named("lost_detected").stream().findFirst();
            if (detection.isPresent()) {
                var after = detection.get().path("after_ms").asLong();
                times.add(after);
                if (after < deadline) {
                    early++;
                } else if (after <= deadline + DETECTION_TOLERANCE_MS
                        + (host.ackInformational() ? host.boundedWait().toMillis() : 0)) {
                    detected++;
                }
                var at = detection.get().path("at").asLong();
                var continuations = view.named("continued").stream().filter(event -> event.path("at").asLong() >= at)
                        .count() + view.named("answer_delivered").stream()
                        .filter(event -> event.path("at").asLong() >= at && event.path("resumed").asBoolean())
                        .count();
                once += continuations == 1 ? 1 : 0;
            }
        }
        group.put("detected_within_deadline", detected);
        group.put("detected_early", early);
        group.put("continued_exactly_once", once);
        group.put("detection_after_ms_median", median(times));
        return detected == views.size() && early == 0 && once == views.size();
    }

    private static boolean r4(List<RunView> views, ObjectNode group) {
        var tokens = new ArrayList<Long>();
        var times = new ArrayList<Long>();
        var asked = 0;
        var follows = 0;
        for (var view : views) {
            tokens.add(tokens(view.outputs()));
            times.add(workerTime(view));
            if (!view.named(ASKED).isEmpty()) {
                asked++;
                follows += followsReference(view) ? 1 : 0;
            }
        }
        group.put("tokens_median", median(tokens));
        group.put("worker_time_ms_median", median(times));
        group.set("tokens", QuestionStub.JSON.valueToTree(tokens));
        group.set("worker_time_ms", QuestionStub.JSON.valueToTree(times));
        group.put("asked", asked);
        group.put("no_ask", views.size() - asked);
        group.put("answer_follows_reference", follows);
        return true;
    }

    /**
     * Adds the limits of R4 to its result.
     *
     * @param result    the result of R4
     * @param unused    how many scenario recipes on the telemetry branch were not used
     * @param largeRuns the runs per large scenario, host, form and continuation
     * @param runs      the runs per fixture, host, form and continuation of the other items
     */
    static void limitsOfR4(ObjectNode result, int unused, int largeRuns, int runs) {
        var limits = result.putArray("limits");
        limits.add("Small work is the probe; large work only the scenarios "
                + String.join(" and ", QuestionFixtures.LARGE_SCENARIOS) + ". The other " + unused + " scenario "
                + "recipes are not used: they need tasks replayed from records whose commits are gone, or outside "
                + "state that has changed.");
        limits.add("The large scenarios ran " + largeRuns + " runs per host and form in the continuation forms hold "
                + "and resume only, instead of " + runs + " runs in every form. R4 has no pass criterion; its figures "
                + "are tokens and time, and the answer check of the large scenarios is secondary.");
        limits.add("Asking is not forced in the large scenarios: a run in which the worker did not ask is counted as "
                + "no_ask, not as a pass.");
        limits.add("Both corpus items behind the large scenarios are drafts.");
    }

    private static JsonNode longestPassing(ArrayNode groups, String parameter) {
        var longest = new TreeMap<String, Long>();
        for (var group : groups) {
            var key = group.path(HOST).asText() + " " + group.path("model").asText();
            var value = group.path(parameter).asLong(-1);
            if ("pass".equals(group.path(VERDICT).asText()) && value >= 0) {
                longest.merge(key, value, Math::max);
            }
        }
        return QuestionStub.JSON.valueToTree(longest);
    }

    // --- per run ---

    private static RunView view(JsonNode run) throws IOException {
        var stub = Path.of(run.path("stub_dir").asText());
        var outputs = new ArrayList<JsonNode>();
        for (var worker : run.path("workers")) {
            outputs.addAll(readJsonLines(Path.of(worker.path("output").asText())));
        }
        return new RunView(run, readJsonLines(stub.resolve("events.jsonl")), readJsonLines(stub.resolve(
                "questions.jsonl")), outputs);
    }

    private static boolean followsReference(RunView view) {
        var reference = normalize(view.run().path("reference").asText());
        return !reference.isEmpty() && reference.equals(normalize(lastSubmitArgs(view).path("answer").asText()));
    }

    private static JsonNode lastSubmitArgs(RunView view) {
        var submitted = view.named(SUBMITTED);
        return submitted.isEmpty() ? QuestionStub.JSON.createObjectNode() : submitted.getLast().path("args");
    }

    private static int reasked(RunView view) {
        var answered = new HashSet<String>();
        var count = 0;
        for (var event : view.events()) {
            var name = event.path(EVENT).asText();
            if ("answer_recorded".equals(name)) {
                event.path("items").forEach(item -> answered.add(normalize(item.path("question_text").asText())));
            } else if (ASKED.equals(name)) {
                for (var text : event.path("question_texts")) {
                    count += answered.contains(normalize(text.asText())) ? 1 : 0;
                }
            }
        }
        return count;
    }

    private static int redone(RunView view) {
        var done = new HashSet<String>();
        var count = 0;
        for (var event : view.named(SUBMITTED)) {
            var planTask = event.path("plan_task_id").asText("");
            if (!planTask.isEmpty() && !done.add(event.path(TASK).asText().replaceAll("-.*$", "") + planTask)) {
                count++;
            }
        }
        return count;
    }

    private static int mixups(RunView view) {
        var count = (int) view.named("link_refused").stream()
                .filter(event -> "consulting".equals(event.path("state").asText())).count();
        var consulted = new HashSet<String>();
        view.named("consulted").forEach(event -> consulted.add(normalize(event.path("question").asText())));
        for (var asked : view.named(ASKED)) {
            for (var text : asked.path("question_texts")) {
                count += consulted.contains(normalize(text.asText())) ? 1 : 0;
            }
        }
        if (!view.named("consulted").isEmpty() && view.named("consultation_delivered").isEmpty()) {
            count++;
        }
        return count;
    }

    private static boolean namesCanary(RunView view) {
        var canary = view.run().path("canary").asText("");
        return !canary.isEmpty() && view.named(SUBMITTED).stream()
                .anyMatch(event -> event.path("args").toString().contains(canary));
    }

    private static boolean rereadsCanary(RunView view, long askedAt) {
        return view.outputs().stream().filter(line -> line.path("at").asLong() > askedAt)
                .map(line -> line.path("line").asText())
                .anyMatch(text -> text.contains(QuestionFixtures.CANARY_FILE)
                        && (text.contains("\"tool_use\"") || text.contains("\"tool\"")));
    }

    private static int hostToolCalls(RunView view) {
        var tool = view.host().questionTool();
        return (int) view.outputs().stream().map(line -> line.path("line").asText())
                .filter(text -> text.contains("\"name\":\"" + tool + "\"") || text.contains("\"tool\":\"" + tool + "\""))
                .count();
    }

    private static String hostToolBehaviour(RunView view) {
        if (view.run().path("timed_out").asBoolean()) {
            return "hangs";
        }
        var failed = view.outputs().stream().map(line -> line.path("line").asText())
                .anyMatch(text -> text.contains("\"is_error\":true") || text.contains("\"status\":\"error\""));
        return failed ? "fails" : "answers_itself";
    }

    private static String finalText(RunView view) {
        return finalText(view.outputs());
    }

    /**
     * @param outputs a run's arrival-stamped output lines
     * @return the worker's final text: Claude Code's {@code result}, OpenCode's last text part, Antigravity's
     *         top-level {@code response}
     */
    static String finalText(List<JsonNode> outputs) {
        var text = "";
        for (var stamped : outputs) {
            JsonNode line;
            try {
                line = QuestionStub.JSON.readTree(stamped.path("line").asText());
            } catch (JsonProcessingException _) {
                continue;
            }
            if ("result".equals(line.path("type").asText()) && line.path("result").isTextual()) {
                text = line.path("result").asText();
            } else if ("text".equals(line.path("type").asText()) && line.path("part").path("text").isTextual()) {
                text = line.path("part").path("text").asText();
            } else if (antigravityResult(line) && line.path("response").isTextual()) {
                text = line.path("response").asText();
            }
        }
        return text;
    }

    /**
     * @param outputs a run's arrival-stamped output lines
     * @return the tokens the harness reported: Claude Code's result usage, OpenCode's step usage, Antigravity's
     *         top-level usage ({@link #usageTokens(JsonNode)})
     */
    static long tokens(List<JsonNode> outputs) {
        var total = 0L;
        for (var stamped : outputs) {
            JsonNode line;
            try {
                line = QuestionStub.JSON.readTree(stamped.path("line").asText());
            } catch (JsonProcessingException _) {
                continue;
            }
            if ("result".equals(line.path("type").asText())) {
                var usage = line.path("usage");
                for (var field : List.of("input_tokens", "output_tokens", "cache_read_input_tokens",
                        "cache_creation_input_tokens")) {
                    total += usage.path(field).asLong();
                }
            } else if (antigravityResult(line)) {
                total += usageTokens(line.path("usage"));
            }
            var steps = line.path("part").path("tokens");
            if (!steps.isMissingNode()) {
                total += steps.path("input").asLong() + steps.path("output").asLong()
                        + steps.path("reasoning").asLong() + steps.path("cache").path("read").asLong()
                        + steps.path("cache").path("write").asLong();
            }
        }
        return total;
    }

    /**
     * The tokens of Antigravity's top-level {@code usage}, whose field names the measurement has not pinned: a total
     * field when it has one ({@code total_tokens}, {@code totalTokens}, {@code total_token_count},
     * {@code totalTokenCount}), else the sum of its numeric fields whose names contain {@code token}.
     *
     * @param usage the usage object
     * @return its tokens, {@code 0} without usage
     */
    static long usageTokens(JsonNode usage) {
        for (var total : List.of("total_tokens", "totalTokens", "total_token_count", "totalTokenCount")) {
            if (usage.path(total).isNumber()) {
                return usage.path(total).asLong();
            }
        }
        var sum = 0L;
        for (var field : usage.properties()) {
            if (field.getValue().isNumber() && field.getKey().toLowerCase(Locale.ROOT).contains("token")) {
                sum += field.getValue().asLong();
            }
        }
        return sum;
    }

    /** Antigravity's {@code --output-format json} ends with one object that names its {@code conversation_id}. */
    private static boolean antigravityResult(JsonNode line) {
        return line.path("conversation_id").isTextual();
    }

    private static long workerTime(RunView view) {
        var started = new LinkedHashMap<String, Long>();
        var total = 0L;
        for (var event : view.events()) {
            var key = event.path(WORKER).asText() + "#" + event.path(GENERATION).asInt();
            switch (event.path(EVENT).asText()) {
                case "worker_started" -> started.put(key, event.path("at").asLong());
                case "worker_exited" -> {
                    var start = started.remove(key);
                    if (start != null) {
                        total += event.path("at").asLong() - start;
                    }
                }
                default -> {
                    // no time
                }
            }
        }
        return total;
    }

    // --- helpers ---

    private static boolean same(JsonNode event, String worker, int generation) {
        return worker.equals(event.path(WORKER).asText()) && generation == event.path(GENERATION).asInt();
    }

    private static boolean within(JsonNode event, long from, long to) {
        var at = event.path("at").asLong();
        return at > from && at < to;
    }

    static String normalize(String text) {
        var value = text.strip().toLowerCase(Locale.ROOT);
        while (value.length() > 1 && Set.of('"', '\'', '`').contains(value.charAt(0))
                && value.charAt(value.length() - 1) == value.charAt(0)) {
            value = value.substring(1, value.length() - 1).strip();
        }
        return value.replaceAll("\\s+", " ");
    }

    private static double share(int count, int population) {
        return population == 0 ? 0 : (double) count / population;
    }

    private static long median(List<Long> values) {
        if (values.isEmpty()) {
            return -1;
        }
        var sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }
}
