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

import static de.planmarshall.e2e.question.result.RunReadings.ACKNOWLEDGED;
import static de.planmarshall.e2e.question.result.RunReadings.ANSWER_DELIVERED;
import static de.planmarshall.e2e.question.result.RunReadings.ASKED;
import static de.planmarshall.e2e.question.result.RunReadings.SUBMITTED;
import static de.planmarshall.e2e.question.result.RunReadings.WORKER_EXITED;
import static de.planmarshall.e2e.question.result.RunReadings.max;
import static de.planmarshall.e2e.question.result.RunReadings.median;
import static de.planmarshall.e2e.question.result.RunReadings.share;
import static de.planmarshall.e2e.question.result.RunView.EVENT;
import static de.planmarshall.e2e.question.result.RunView.GENERATION;
import static de.planmarshall.e2e.question.result.RunView.WORKER;
import static de.planmarshall.e2e.question.result.RunView.normalize;
import static de.planmarshall.e2e.question.result.RunView.runId;
import static de.planmarshall.e2e.question.result.RunView.same;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.question.driver.Fault;
import de.planmarshall.e2e.question.driver.QuestionDriver;
import de.planmarshall.e2e.question.result.RunReadings.ContinuationExit;
import de.planmarshall.e2e.question.result.Scale.Verdict;
import de.planmarshall.e2e.question.stub.QuestionStub;

/**
 * The evaluation of the measurement: reads, per run, the stub's event log and question records, the relay's host-side
 * log and the worker's output ({@link RunView}), computes the figures per item, fixture, host, model, form and
 * continuation, and the verdict of each group against the item's criterion at the revised scale ({@link Scale}), and
 * writes them to {@code target/verification-results/question-loop/<item>.json}.
 * <p>
 * The criteria ({@link #CRITERIA}) are those of the work package {@code question-loop-follow-up} and the Evaluation
 * Reference, encoded before the first measured run and not changed by any result. Each criterion is judged per run,
 * and a group passes when every run passes; a group whose runs disagree is extended to {@value Scale#EXTENDED_RUNS}
 * runs and reported with its rate. No figure comes from what a model reports about itself. Every verdict is stated
 * with its run count and the client versions its runs recorded ({@value #VERDICT_STATEMENT}).
 * <p>
 * Beside the figures every group counts its runs that timed out, did not ask, ended early, ended early in a
 * continuation generation, had a voter that ended early, met a quota error of the harness, failed their setup, or lost
 * their stub log ({@value #EVIDENCE_MISSING}); figures no verdict reads. The limits come from every invocation the
 * result aggregates ({@link ResultLimits}), and the bookkeeping names each invocation with its record count, flagged
 * {@code incomplete} where it recorded fewer runs than it planned ({@link #bookkeeping}).
 * <p>
 * A run whose stub log is gone counts as {@value #EVIDENCE_MISSING}, never as a run that did not ask; a result with
 * such runs never replaces an existing result file without {@value ResultTarget#ALLOW_MISSING_EVIDENCE}
 * ({@link #write(ResultTarget, String, ObjectNode)}).
 */
public final class QuestionResults {

    /** The criterion of each item at the revised scale, as the work package and the Evaluation Reference state it. */
    public static final Map<String, String> CRITERIA = Map.ofEntries(
            Map.entry("Q1", "Every run passes: each of its asks accepted on the first submit or after one "
                    + "invalid_link_args refusal, and no ask lost."),
            Map.entry("Q2", "Every run passes: no host question tool call and no prose question; a run of a Q1 "
                    + "fixture asks and guesses no decision; a run of a control does not ask."),
            Map.entry("Q3", "Every run passes: the worker stops after its ask (a warm worker's next pm_wait, a fresh "
                    + "worker's end of turn) with its worktree unchanged; the time from the ask to the stop is "
                    + "recorded per host."),
            Map.entry("Q4", "Every run passes: its decision follows the answer (the reference, also stated as "
                    + "f(x) == <reference>), no answered question asked again, no submitted plan task done again."),
            Map.entry("Q5", "Every run passes: a second ask and never the same question twice; with the bound "
                    + "reached, the run ends in a submission that states the worker's judgment or an explicit "
                    + "infeasible report, with no lost task and no prose question. The bounds run sequentially from "
                    + Scale.Q5_FIRST_BOUND + "; the next bound only where a run reached the bound before."),
            Map.entry("Q6", "Every run passes: every vote valid within the task protocol's re-offer budget, the "
                    + "decided answer applied as in Q4, consultation and question never mixed up."),
            Map.entry("Q7", "Every run passes: no worker or slot held while the record is open, the warm worker "
                    + "recycled, the stub restarted while open, and the answer delivered exactly once after the "
                    + "restart."),
            Map.entry("R1", "Every run of a duration passes: continued in the same generation (answer acknowledged, "
                    + "canary named and not read again). Durations run longest first (3600 s), descending through 30, "
                    + "15 and 5 minutes only on a failure; the longest duration that passes is the host's "
                    + "max_hold_seconds."),
            Map.entry("R2", "Every run of an age passes: continued from the session (answer delivered to the resumed "
                    + "generation and acknowledged, canary named), time to the acknowledgement recorded, no hung "
                    + "resume. Ages run longest first from 1 h at most, descending only on a failure; the oldest age "
                    + "that passes is the host's max_age_seconds."),
            Map.entry("R3", "Every run passes: the fault detected by the missing answer acknowledgement within the "
                    + "host's acknowledgement deadline (by silence where the acknowledgement is informational), never "
                    + "earlier, and the task then resumed or offered again with facts.answers exactly once. A call cut "
                    + "the worker survives by waiting again is no lost continuation and passes."),
            Map.entry("R4", "No criterion: the figures of hold, resume and fallback for small and large work set the "
                    + "shipped questions.hold_seconds and resume_within_seconds per role, and whether a role "
                    + "continues at all."));

    /** The field of a run record and of an invocation record that names the invocation. */
    public static final String INVOCATION = "invocation";
    /** The field of a run record with the client version of its harness (its output, else the version probe). */
    public static final String CLIENT_VERSION = "client_version";
    /** The field of a run record with the number of runs the measurement ran at once (the parallel level). */
    public static final String PARALLEL = "parallel";
    /** The field of a group and of a result with its verdict stated with run count and client versions. */
    public static final String VERDICT_STATEMENT = "verdict_statement";
    /** The field of an invocation record with its run limit ({@code pm.experiment.run-timeout}, an ISO duration). */
    public static final String RUN_TIMEOUT = "run_timeout";
    /** The field of an invocation record with the runs it planned. */
    public static final String PLANNED_RUNS = "planned_runs";
    /** The field of an R3 invocation record that names the faults its runs inject, in the order of {@link Fault}. */
    public static final String R3_FAULTS = "r3_faults";
    /** The field of a run record of a run that waited out its run limit. */
    public static final String TIMED_OUT = "timed_out";
    /** The field of a run record of a Q5 run with its question bound. */
    public static final String QUESTION_BOUND = "question_bound";
    /** The field of an invocation record with the runs it did not start because a setup of the scenario failed. */
    public static final String SETUP_STOPPED_RUNS = "setup_stopped_runs";
    /**
     * The field of an invocation record with the runs it did not start because the account's usage limit refused an
     * earlier run of it.
     */
    public static final String USAGE_LIMIT_STOPPED_RUNS = "usage_limit_stopped_runs";
    /** The group column, and the result's total, of the runs whose stub log is missing. */
    public static final String EVIDENCE_MISSING = "evidence_missing";
    /** The mark of a verdict judged with runs whose stub log is missing. */
    public static final String VERDICT_EVIDENCE = "verdict_evidence";
    /** The verdict of a group or an item without its quota-error runs: an additional figure, not the verdict. */
    public static final String VERDICT_WITHOUT_QUOTA_ERROR = "verdict_without_quota_error";
    /** The verdict without the continuation early exits, reported where it reads differently. */
    public static final String VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT = "verdict_without_continuation_early_exit";
    /** The verdict of a Q3 group with the next {@code pm_wait} or an exit before the answer read as the stop. */
    public static final String VERDICT_BY_NEXT_PM_WAIT = "verdict_by_next_pm_wait_or_end";
    /** The per-run figure of Q3: the milliseconds from the ask to the asking generation's next {@code pm_wait}. */
    public static final String ASK_TO_NEXT_PM_WAIT_MS = "ask_to_next_pm_wait_ms";
    /** The per-run figure of Q3: the milliseconds from the ask to the exit of the asking generation's process. */
    public static final String ASK_TO_EXIT_MS = "ask_to_exit_ms";
    /** The group figure of its runs that passed the criterion. */
    public static final String PASSING_RUNS = "passing_runs";
    /** The group figure of the ids of its runs counted in {@code timed_out}. */
    public static final String TIMED_OUT_RUNS = "timed_out_runs";
    /** The group figure of the ids of its runs whose last submission does not follow the reference (Q4, Q6, R4). */
    public static final String NOT_FOLLOWING_REFERENCE_RUNS = "not_following_reference_runs";
    /** The group figure of its runs whose continuation got no worker. */
    public static final String CONTINUATION_NOT_STARTED = "continuation_not_started";
    /** The Q4 group figure of its runs whose continuing task ran on another host than the asker's. */
    public static final String CROSS_HOST_RUNS = "cross_host_runs";
    /** The suffix of the R1 and R2 figures per form. */
    public static final String PER_FORM = "_per_form";
    /** The R1 and R2 result figure of the next duration or age the descent runs ({@link Scale#nextDuration}). */
    public static final String DESCENT = "descent";
    /** The Q5 result figure of the bounds the sequential rule runs ({@link Scale#q5Bounds}). */
    public static final String Q5_BOUNDS = "q5_bounds";
    /** The R2 group figure that splits its runs by whether the stub was restarted while the record was open. */
    public static final String RESTART_BREAKDOWN = "restart_breakdown";
    /** The Q7 figure that the verdict reads as the slots held while the record is open. */
    public static final String LEASES_WHILE_OPEN = "leases_while_open";
    /** The Q7 figure of the generations other than the warm worker connected in the open time. */
    public static final String WORKERS_CONNECTED_WHILE_OPEN = "workers_connected_while_open";
    /** The Q7 figure of the milliseconds of {@code pm_wait} held in the open time by other generations. */
    public static final String HELD_WAIT_MS = "held_wait_ms";
    /** The Q7 figure of the generations other than the warm worker that held a {@code pm_wait} in the open time. */
    public static final String HELD_WAIT_GENERATIONS = "held_wait_generations";
    /** The value of a Q7 figure that the stub log of a run cannot give. */
    public static final String UNAVAILABLE = "unavailable";
    /** The per-run flag of Q7: an offer of the continuing task counted twice is one fenced before its acknowledgement. */
    public static final String OFFER_COUNTED_TWICE_FENCED = "offer_counted_twice_is_fenced_unacknowledged";
    /** The Q7 group figure of the runs whose continuing task was offered twice only because of a fenced offer. */
    public static final String FENCED_UNACKNOWLEDGED_OFFER_RUNS = "fenced_unacknowledged_offer_runs";
    /** The Q7 group array of the figures of each run. */
    public static final String Q7_RUNS = "q7_runs";
    /** The field of an R3 run record of {@link Fault#CALL_CUT}: whether the logs show the host's cut. */
    public static final String CALL_CUT_FIELD = "call_cut";
    /** The field of a run record whose stub did not lose a worker at its process exit. */
    public static final String STUB_LOSE_ON_EXIT = "stub_lose_on_exit";
    /** The R3 group figure of the exit observations. */
    public static final String EXIT_OBSERVATION = "exit_observation";
    /** The R3 {@link Fault#CALL_CUT} group figure of the runs whose held call the logs show cut by the host. */
    public static final String CUT_BY_HOST = "cut_by_host";
    /** The R3 {@link Fault#CALL_CUT} group figure of the runs whose cut the worker survived. */
    public static final String CUT_SURVIVED = "cut_survived";
    /** The R3 {@link Fault#CALL_CUT} figure of the milliseconds from the cut to the generation's next pm_wait. */
    public static final String RE_WAIT_AFTER_CUT_MS = "re_wait_after_cut_ms";

    /** The items whose fixtures force the ask: the apply-answer, two-flaw and large-work fixtures. */
    public static final Set<String> FORCED_ITEMS = Set.of("Q1", "Q2", "Q3", "Q4", "Q5", "Q6", "Q7", "R3", "R4");
    /** The re-offer budget of a vote task (Q6): refused submits a vote may take and still count valid. */
    public static final int VOTE_REOFFER_BUDGET = 2;
    /** How late after the deadline a detection still counts within it: the supervisor's poll and termination. */
    public static final long DETECTION_TOLERANCE_MS = 15_000;

    static final String HOST = "host";
    static final String MODEL = "model";
    static final String RUNS = "runs";
    static final String VERDICT = "verdict";
    static final String GROUPS = "groups";
    static final String GROUP = "group";
    static final String CONTINUED = "continued";
    static final String OFFERED = "offered";
    static final String NONE = "none";

    private static final String RUNS_FILE = "runs.jsonl";
    private static final String INVOCATIONS_FILE = "invocations.jsonl";
    private static final String UNKNOWN_INVOCATION = "unknown";
    private static final String NOT_RECORDED = "not recorded";
    private static final String DECISION = "decision";
    private static final String TASK = "task";
    private static final String R4 = "R4";
    private static final String STUB_STOPPED_EVENT = "stub_stopped";
    private static final String ANSWER_RECORDED = "answer_recorded";

    private QuestionResults() {
    }

    // --- records ---

    /**
     * Appends the record of a run to {@value #RUNS_FILE} of the results directory.
     *
     * @param resultsDir the results directory of the measurement
     * @param run        the run record
     * @throws IOException if it cannot be written
     */
    public static void recordRun(Path resultsDir, ObjectNode run) throws IOException {
        append(resultsDir.resolve(RUNS_FILE), run);
    }

    /**
     * Appends the record of an invocation to {@value #INVOCATIONS_FILE} of the results directory.
     *
     * @param resultsDir the results directory of the measurement
     * @param invocation the invocation record
     * @throws IOException if it cannot be written
     */
    public static void recordInvocation(Path resultsDir, ObjectNode invocation) throws IOException {
        append(resultsDir.resolve(INVOCATIONS_FILE), invocation);
    }

    private static void append(Path file, ObjectNode node) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, QuestionStub.JSON.writeValueAsString(node) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * @param resultsDir the results directory of the measurement
     * @param item       an item
     * @return the run records of the item
     * @throws IOException if they cannot be read
     */
    public static List<JsonNode> runs(Path resultsDir, String item) throws IOException {
        return RunView.readJsonLines(resultsDir.resolve(RUNS_FILE)).stream()
                .filter(run -> item.equals(run.path("item").asText())).toList();
    }

    /**
     * @param resultsDir the results directory of the measurement
     * @param item       an item
     * @return the invocation records of the item, one per invocation: where an invocation appended its record again
     *         after its runs, the last record, in the place of its first
     * @throws IOException if they cannot be read
     */
    public static List<JsonNode> invocations(Path resultsDir, String item) throws IOException {
        var latest = new LinkedHashMap<String, JsonNode>();
        RunView.readJsonLines(resultsDir.resolve(INVOCATIONS_FILE)).stream()
                .filter(invocation -> item.equals(invocation.path("item").asText()))
                .forEach(invocation -> latest.put(invocation.path(INVOCATION).asText(), invocation));
        return List.copyOf(latest.values());
    }

    // --- the whole result ---

    /** The count of the scenario recipes R4 does not use, read only when the item is R4. */
    @FunctionalInterface
    public interface UnusedScenarios {

        /**
         * @return the count
         * @throws IOException if the recipes cannot be read
         */
        int count() throws IOException;
    }

    /**
     * The whole result of an item from its run records and the records of every invocation of it: the figures and
     * verdicts ({@link #evaluate(String, List)}), the limits of all invocations that contributed run records
     * ({@link ResultLimits#addAll}), and the bookkeeping of the invocations ({@link #bookkeeping}).
     *
     * @param item        the item
     * @param runs        its run records
     * @param invocations its invocation records
     * @param unused      the count of unused scenario recipes, asked for R4 only
     * @return the result
     * @throws IOException if a run's files or the scenario recipes cannot be read
     */
    public static ObjectNode result(String item, List<JsonNode> runs, List<JsonNode> invocations,
            UnusedScenarios unused) throws IOException {
        var result = evaluate(item, runs);
        var contributing = contributing(runs, invocations);
        ResultLimits.addAll(result, item, runs, contributing, R4.equals(item) ? unused.count() : 0);
        bookkeeping(result, runs, invocations);
        return result;
    }

    /**
     * Adds the bookkeeping of an item's result: the invocations whose run records it aggregates (with their settings
     * and record counts; a record without an invocation id counts under {@code unknown}), an invocation with fewer run
     * records than it planned flagged {@code incomplete} with a limit that names it, the number of run records and of
     * fixtures, and the client versions, models and parallel levels the runs recorded.
     *
     * @param result      the result, its verdict evaluated
     * @param runs        the run records it was evaluated from
     * @param invocations the invocation records of the item
     */
    public static void bookkeeping(ObjectNode result, List<JsonNode> runs, List<JsonNode> invocations) {
        var known = new HashMap<String, JsonNode>();
        invocations.forEach(invocation -> known.put(invocation.path(INVOCATION).asText(), invocation));
        var array = result.putArray("invocations");
        var incomplete = new ArrayList<String>();
        for (var entry : recordsPerInvocation(runs).entrySet()) {
            var recorded = known.get(entry.getKey());
            var node = array.addObject();
            node.put(INVOCATION, entry.getKey());
            node.put("run_records", entry.getValue());
            if (recorded == null) {
                node.put("settings", NOT_RECORDED);
                continue;
            }
            var planned = recorded.path(PLANNED_RUNS).asInt(-1);
            var stopped = recorded.path(SETUP_STOPPED_RUNS).asInt(0) + recorded.path(USAGE_LIMIT_STOPPED_RUNS).asInt(0);
            if (planned >= 0 && entry.getValue() + stopped < planned) {
                node.put("incomplete", true);
                node.put(PLANNED_RUNS, planned);
                incomplete.add(entry.getKey() + " recorded " + entry.getValue() + " of its " + planned
                        + " planned runs");
            }
            node.set("settings", recorded);
        }
        if (!incomplete.isEmpty()) {
            ResultLimits.limit(result, "Not every invocation recorded all the runs it planned; it was killed or "
                    + "stopped before the rest ran, and the missing runs are in no figure: "
                    + String.join("; ", incomplete) + ".");
        }
        result.put("run_records", runs.size());
        result.put("fixtures", runs.stream().map(run -> run.path("fixture").asText()).distinct().count());
        putSorted(result, "client_versions", runs, CLIENT_VERSION);
        var models = result.putArray("models");
        runs.stream().map(RunView::model).distinct().sorted().forEach(models::add);
        var levels = result.putArray("parallel_levels");
        runs.stream().filter(run -> run.path(PARALLEL).isInt()).map(run -> run.path(PARALLEL).asInt()).distinct()
                .sorted().forEach(levels::add);
    }

    private static void putSorted(ObjectNode node, String field, List<JsonNode> runs, String runField) {
        var array = node.putArray(field);
        runs.stream().map(run -> run.path(runField).asText("")).filter(value -> !value.isBlank()).distinct().sorted()
                .forEach(array::add);
    }

    private static Map<String, Integer> recordsPerInvocation(List<JsonNode> runs) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var run : runs) {
            counts.merge(invocationOf(run), 1, Integer::sum);
        }
        return counts;
    }

    static String invocationOf(JsonNode run) {
        var id = run.path(INVOCATION).asText("");
        return id.isEmpty() ? UNKNOWN_INVOCATION : id;
    }

    /** @return the recorded invocations that contributed run records, by id, in the order of their records */
    static Map<String, JsonNode> contributing(List<JsonNode> runs, List<JsonNode> invocations) {
        var known = new HashMap<String, JsonNode>();
        invocations.forEach(invocation -> known.put(invocation.path(INVOCATION).asText(), invocation));
        var contributing = new LinkedHashMap<String, JsonNode>();
        for (var id : recordsPerInvocation(runs).keySet()) {
            if (known.containsKey(id)) {
                contributing.put(id, known.get(id));
            }
        }
        return contributing;
    }

    // --- evaluation ---

    /**
     * Evaluates an item: groups its runs by fixture, host, model, form, continuation and the item's measured parameter,
     * judges every run against the item's criterion, and states each group's verdict at the revised scale
     * ({@link Scale#verdict(Collection)}). The item's verdict is {@code fail} where a group fails, else
     * {@code extend to 10} where a group must be extended, else {@code pass}; R4 has none.
     *
     * @param item the item ({@code Q1} to {@code Q7}, {@code R1} to {@code R4})
     * @param runs its run records
     * @return the result: {@code item}, {@code criterion}, {@code groups[]}, {@code verdict} and
     *         {@value #VERDICT_STATEMENT}, and the derived values of Q5, R1 and R2
     * @throws IOException if a run's files cannot be read
     */
    public static ObjectNode evaluate(String item, List<JsonNode> runs) throws IOException {
        if (!CRITERIA.containsKey(item)) {
            throw new IllegalArgumentException("unknown item " + item);
        }
        var groups = new TreeMap<String, List<RunView>>();
        for (var run : runs) {
            groups.computeIfAbsent(groupKey(item, run), _ -> new ArrayList<>()).add(RunView.read(run));
        }
        var result = QuestionStub.JSON.createObjectNode();
        result.put("item", item);
        result.put("criterion", CRITERIA.get(item));
        result.put("scale", Scale.RUNS_PER_GROUP + " runs per group; a disagreeing group is extended to "
                + Scale.EXTENDED_RUNS + " runs and reported with its rate");
        result.put("os", System.getProperty("os.name"));
        var array = result.putArray(GROUPS);
        var verdicts = new ArrayList<Verdict>();
        var withoutQuota = new ArrayList<Verdict>();
        var withoutContinuationExits = new ArrayList<Verdict>();
        var quotaErrors = false;
        var missing = 0;
        var judged = 0;
        var passing = 0;
        for (var entry : groups.entrySet()) {
            var group = evaluateGroup(item, entry.getValue());
            group.put(GROUP, entry.getKey());
            array.add(group);
            var verdict = verdictOf(group.path(VERDICT).asText());
            verdicts.add(verdict);
            missing += group.path(EVIDENCE_MISSING).asInt(0);
            judged += group.path("judged_runs").asInt();
            passing += group.path(PASSING_RUNS).asInt();
            quotaErrors |= group.path(QuestionDriver.QUOTA_ERROR).asInt() > 0;
            withoutQuota.add(verdictOf(group.path(VERDICT_WITHOUT_QUOTA_ERROR).asText(verdict.text())));
            withoutContinuationExits.add(verdictOf(group.path(VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT)
                    .asText(verdict.text())));
        }
        var verdict = R4.equals(item) ? Verdict.NONE : combine(verdicts);
        result.put(VERDICT, verdict.text());
        result.put(VERDICT_STATEMENT, Scale.statement(verdict, judged, passing, versions(runs)));
        if (quotaErrors && !R4.equals(item)) {
            result.put(VERDICT_WITHOUT_QUOTA_ERROR, combine(withoutQuota).text());
        }
        var withoutExits = R4.equals(item) ? Verdict.NONE : combine(withoutContinuationExits);
        if (withoutExits != verdict) {
            result.put(VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT, withoutExits.text());
        }
        if (missing > 0) {
            result.put(EVIDENCE_MISSING, missing);
            result.put(VERDICT_EVIDENCE, missingEvidenceMark(missing, judged));
        }
        derived(item, result, array);
        return result;
    }

    private static void derived(String item, ObjectNode result, ArrayNode groups) {
        if ("R1".equals(item) || "R2".equals(item)) {
            var name = "R1".equals(item) ? "max_hold_seconds" : "max_age_seconds";
            var parameter = "R1".equals(item) ? "duration_s" : "age_s";
            result.set(name, longestPassing(groups, parameter));
            result.set(name + PER_FORM, longestPassingPerForm(groups, parameter));
            result.set(DESCENT, descent(groups, parameter));
        }
        if ("Q5".equals(item)) {
            var reached = new TreeSet<Integer>();
            groups.forEach(group -> {
                if (group.path("bound_reached").asInt() > 0) {
                    reached.add(group.path(QUESTION_BOUND).asInt(QuestionStub.QUESTION_BOUND));
                }
            });
            var bounds = result.putArray(Q5_BOUNDS);
            Scale.q5Bounds(reached).forEach(bounds::add);
        }
    }

    /**
     * @param verdicts the verdicts of the groups
     * @return {@link Verdict#FAIL} where one fails, else {@link Verdict#EXTEND} where one must be extended, else
     *         {@link Verdict#PASS} where one passes, else {@link Verdict#NONE}
     */
    public static Verdict combine(Collection<Verdict> verdicts) {
        if (verdicts.contains(Verdict.FAIL)) {
            return Verdict.FAIL;
        }
        if (verdicts.contains(Verdict.EXTEND)) {
            return Verdict.EXTEND;
        }
        return verdicts.contains(Verdict.PASS) ? Verdict.PASS : Verdict.NONE;
    }

    private static Verdict verdictOf(String text) {
        for (var verdict : Verdict.values()) {
            if (verdict.text().equals(text)) {
                return verdict;
            }
        }
        throw new IllegalArgumentException("no verdict: " + text);
    }

    private static Set<String> versions(Collection<JsonNode> runs) {
        return runs.stream().map(run -> run.path(CLIENT_VERSION).asText("")).collect(Collectors.toSet());
    }

    /**
     * @param missing the runs whose stub log is missing
     * @param judged  the runs the verdict read
     * @return the mark of a verdict judged with them ({@value #VERDICT_EVIDENCE})
     */
    public static String missingEvidenceMark(int missing, int judged) {
        return "evidence missing for " + missing + " of " + judged + " runs: the verdict reads them as runs without a "
                + "single stub event";
    }

    /**
     * @param item an item
     * @param run  one of its run records
     * @return the key of its group: fixture, host, model, form, continuation and the item's measured parameter
     */
    public static String groupKey(String item, JsonNode run) {
        var key = new StringBuilder();
        key.append("fixture=").append(run.path("fixture").asText()).append(' ');
        for (var field : List.of(HOST, MODEL, "form", "continuation")) {
            key.append(field).append('=').append(MODEL.equals(field) ? RunView.model(run) : run.path(field).asText())
                    .append(' ');
        }
        for (var parameter : parameters(item)) {
            key.append(parameter).append('=').append(run.path(parameter).asText()).append(' ');
        }
        return key.toString().strip();
    }

    private static List<String> parameters(String item) {
        return switch (item) {
            case "Q4" -> List.of("reply");
            case "Q5" -> List.of("bound", QUESTION_BOUND);
            case "Q6" -> List.of("kind");
            case "R1" -> List.of("duration_s");
            case "R2" -> List.of("age_s", "negative");
            case "R3" -> List.of("fault");
            case R4 -> List.of("size");
            default -> List.of();
        };
    }

    private static ObjectNode evaluateGroup(String item, List<RunView> views) throws IOException {
        var group = QuestionStub.JSON.createObjectNode();
        var first = views.getFirst().run();
        group.set("fixture", first.path("fixture"));
        for (var field : List.of(HOST, MODEL, "form", "continuation", "duration_s", "age_s", "fault", "size",
                QUESTION_BOUND)) {
            if (MODEL.equals(field)) {
                group.put(MODEL, RunView.model(first));
            } else if (first.has(field)) {
                group.set(field, first.get(field));
            }
        }
        counts(views, group);
        figures(item, views, group, first);
        var launched = views.stream().filter(view -> !RunView.excluded(view.run())).toList();
        var passes = launched.isEmpty() ? List.<Boolean>of() : judge(item, launched, group);
        var verdict = R4.equals(item) ? Verdict.NONE : Scale.verdict(passes);
        var passing = (int) passes.stream().filter(Boolean::booleanValue).count();
        group.put("judged_runs", passes.size());
        group.put(PASSING_RUNS, passing);
        group.put(VERDICT, verdict.text());
        group.put(VERDICT_STATEMENT, Scale.statement(verdict, passes.size(), passing,
                versions(views.stream().map(RunView::run).toList())));
        var missing = group.path(EVIDENCE_MISSING).asInt(0);
        if (missing > 0) {
            group.put(VERDICT_EVIDENCE, missingEvidenceMark(missing, launched.size()));
        }
        if (!R4.equals(item) && !launched.isEmpty()) {
            alternativeVerdicts(item, launched, group, verdict);
        }
        return group;
    }

    private static void figures(String item, List<RunView> views, ObjectNode group, JsonNode first) {
        notFollowingReference(item, views, group);
        switch (item) {
            case "Q4" -> crossHostRuns(views, group);
            case "R2" -> restartBreakdown(views, group);
            case "R3" -> {
                exitObservations(views, group);
                if (Fault.CALL_CUT.name().equals(first.path("fault").asText())) {
                    cutSurvival(views, group);
                }
            }
            default -> {
                // no figure of its own beside the judge's
            }
        }
    }

    private static void alternativeVerdicts(String item, List<RunView> launched, ObjectNode group, Verdict verdict)
            throws IOException {
        var withoutQuota = new ArrayList<RunView>();
        for (var view : launched) {
            if (!RunReadings.quotaError(view)) {
                withoutQuota.add(view);
            }
        }
        if (withoutQuota.size() < launched.size()) {
            var scratch = QuestionStub.JSON.createObjectNode();
            var without = withoutQuota.isEmpty() ? Verdict.NONE : Scale.verdict(judge(item, withoutQuota, scratch));
            group.put(VERDICT_WITHOUT_QUOTA_ERROR, without.text());
            group.put("runs_without_quota_error", withoutQuota.size());
        }
        var withoutExits = launched.stream()
                .filter(view -> RunReadings.continuationExit(view) != ContinuationExit.EXITED).toList();
        if (withoutExits.size() < launched.size() && !withoutExits.isEmpty()) {
            var without = Scale.verdict(judge(item, withoutExits, QuestionStub.JSON.createObjectNode()));
            if (without != verdict) {
                group.put(VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT, without.text());
            }
        }
    }

    private static List<Boolean> judge(String item, List<RunView> views, ObjectNode group) {
        return switch (item) {
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
            case R4 -> r4(views, group);
            default -> throw new IllegalArgumentException("unknown item " + item);
        };
    }

    /**
     * Adds the counts every group reports beside its item's figures, so that no run leaves the result without a trace:
     * {@code runs}, {@code timed_out}, {@code no_ask}, the early exits, {@code quota_error}, {@code setup_failed},
     * {@code asked}, {@code continued}, {@code submitted}, {@code answer_fallback_own}, {@value #TIMED_OUT_RUNS},
     * {@value #CONTINUATION_NOT_STARTED}, where a run lost its stub log {@value #EVIDENCE_MISSING}, and where the
     * usage limit refused a run {@value RunView#NOT_RUN}. A quota-error run counts in {@code quota_error} only, a run
     * whose setup failed in {@code setup_failed} only, a refused run in {@value RunView#NOT_RUN} only, and a run without
     * its stub log in {@value #EVIDENCE_MISSING} only. They are reported figures: no verdict reads them.
     *
     * @param views the group's runs
     * @param group the group
     */
    static void counts(List<RunView> views, ObjectNode group) {
        var tally = new LinkedHashMap<String, Integer>();
        for (var name : List.of(TIMED_OUT, "no_ask", QuestionDriver.EARLY_EXIT, QuestionDriver.CONTINUATION_EARLY_EXIT,
                QuestionDriver.VOTER_EARLY_EXIT, QuestionDriver.QUOTA_ERROR, RunView.SETUP_FAILED, ASKED, CONTINUED,
                SUBMITTED, AnswerAudit.ANSWER_FALLBACK_OWN, CONTINUATION_NOT_STARTED)) {
            tally.put(name, 0);
        }
        var evidenceMissing = 0;
        var notRun = 0;
        var timedOutRuns = QuestionStub.JSON.createArrayNode();
        for (var view : views) {
            if (RunView.notRun(view.run())) {
                notRun++;
                continue;
            }
            if (RunView.setupFailed(view.run())) {
                tally.merge(RunView.SETUP_FAILED, 1, Integer::sum);
                continue;
            }
            tally.merge(AnswerAudit.ANSWER_FALLBACK_OWN,
                    view.run().path(AnswerAudit.ANSWER_FALLBACK_OWN).asBoolean() ? 1 : 0, Integer::sum);
            if (view.evidenceMissing()) {
                evidenceMissing++;
                continue;
            }
            var askedHere = !view.named(ASKED).isEmpty();
            tally.merge(ASKED, askedHere ? 1 : 0, Integer::sum);
            if (quota(view)) {
                tally.merge(QuestionDriver.QUOTA_ERROR, 1, Integer::sum);
            } else {
                var continuationExited = RunReadings.continuationExit(view) == ContinuationExit.EXITED;
                var timedOut = view.run().path(TIMED_OUT).asBoolean() && !continuationExited;
                tally.merge(QuestionDriver.CONTINUATION_EARLY_EXIT, continuationExited ? 1 : 0, Integer::sum);
                tally.merge(TIMED_OUT, timedOut ? 1 : 0, Integer::sum);
                if (timedOut) {
                    timedOutRuns.add(runId(view.run()));
                }
                tally.merge(CONTINUATION_NOT_STARTED, RunReadings.continuationNotStarted(view) ? 1 : 0, Integer::sum);
                tally.merge("no_ask", askedHere ? 0 : 1, Integer::sum);
                tally.merge(QuestionDriver.EARLY_EXIT, RunReadings.earlyExit(view) ? 1 : 0, Integer::sum);
                tally.merge(QuestionDriver.VOTER_EARLY_EXIT, RunReadings.voterEarlyExit(view) ? 1 : 0, Integer::sum);
            }
            tally.merge(CONTINUED, view.named(CONTINUED).isEmpty() ? 0 : 1, Integer::sum);
            tally.merge(SUBMITTED, view.named(SUBMITTED).isEmpty() ? 0 : 1, Integer::sum);
        }
        group.put(RUNS, views.size());
        tally.forEach(group::put);
        group.set(TIMED_OUT_RUNS, timedOutRuns);
        if (evidenceMissing > 0) {
            group.put(EVIDENCE_MISSING, evidenceMissing);
        }
        if (notRun > 0) {
            group.put(RunView.NOT_RUN, notRun);
        }
    }

    private static boolean quota(RunView view) {
        try {
            return RunReadings.quotaError(view);
        } catch (IOException e) {
            throw new IllegalStateException("stderr of " + runId(view.run()) + " cannot be read", e);
        }
    }

    // --- per-item judges: one verdict per run ---

    private static List<Boolean> q1(List<RunView> views, ObjectNode group) {
        var asks = 0;
        var accepted = 0;
        var afterOneRefusal = 0;
        var lost = 0;
        var passes = new ArrayList<Boolean>();
        for (var view : views) {
            var runAsks = 0;
            var runAccepted = 0;
            var refusals = 0;
            for (var event : view.events()) {
                var name = event.path(EVENT).asText();
                if ("ask_refused".equals(name)) {
                    refusals++;
                } else if (ASKED.equals(name)) {
                    runAsks++;
                    if (refusals == 0) {
                        accepted++;
                        runAccepted++;
                    } else if (refusals == 1) {
                        afterOneRefusal++;
                        runAccepted++;
                    }
                    refusals = 0;
                }
            }
            if (refusals > 0) {
                runAsks++;
            }
            var recorded = view.records().stream().filter(line -> ASKED.equals(line.path("line").asText())).count();
            var runLost = (int) Math.max(0, view.named(ASKED).size() - recorded);
            asks += runAsks;
            lost += runLost;
            passes.add(runAsks > 0 && runAccepted == runAsks && runLost == 0);
        }
        group.put("asks", asks);
        group.put("accepted_first", accepted);
        group.put("accepted_after_one_refusal", afterOneRefusal);
        group.put("accepted_share", share(accepted + afterOneRefusal, asks));
        group.put("asks_lost", lost);
        return passes;
    }

    private static List<Boolean> q2(List<RunView> views, ObjectNode group) {
        var hostToolCalls = 0;
        var prose = 0;
        var fixtures = 0;
        var asked = 0;
        var guesses = 0;
        var controls = 0;
        var controlAsks = 0;
        var behaviour = new TreeMap<String, Integer>();
        var passes = new ArrayList<Boolean>();
        for (var view : views) {
            var calls = RunReadings.hostToolCalls(view);
            hostToolCalls += calls;
            if (calls > 0) {
                behaviour.merge(RunReadings.hostToolBehaviour(view), 1, Integer::sum);
            }
            var proseHere = RunReadings.proseQuestion(view.outputs());
            prose += proseHere ? 1 : 0;
            var askedHere = !view.named(ASKED).isEmpty();
            var pass = calls == 0 && !proseHere;
            if (view.run().path("control").asBoolean()) {
                controls++;
                controlAsks += askedHere ? 1 : 0;
                pass &= !askedHere;
            } else {
                fixtures++;
                asked += askedHere ? 1 : 0;
                var guessed = !askedHere && !view.named(SUBMITTED).isEmpty();
                guesses += guessed ? 1 : 0;
                pass &= askedHere && !guessed;
            }
            passes.add(pass);
        }
        group.put("host_question_tool_calls", hostToolCalls);
        group.set("host_question_tool_behaviour", QuestionStub.JSON.valueToTree(behaviour));
        group.put("prose_questions", prose);
        group.put("q1_fixture_runs", fixtures);
        group.put("ask_share", share(asked, fixtures));
        group.put("guessed_decisions", guesses);
        group.put("control_runs", controls);
        group.put("control_asks", controlAsks);
        return passes;
    }

    /**
     * Q3: a run passes when its asking generation stopped after the first ask (a warm worker's next
     * {@code wait_started}, a fresh worker's process exit) and its worktree is unchanged. Beside it the group reports
     * the time to the next {@code pm_wait}, to the process exit, and {@value #VERDICT_BY_NEXT_PM_WAIT}.
     */
    private static List<Boolean> q3(List<RunView> views, ObjectNode group) {
        var times = new ArrayList<Long>();
        var waits = new ArrayList<Long>();
        var exits = new ArrayList<Long>();
        var exitsBeforeAnswer = 0;
        var stopped = 0;
        var unchanged = 0;
        var passes = new ArrayList<Boolean>();
        var byWaitOrEnd = new ArrayList<Boolean>();
        var perRun = QuestionStub.JSON.createArrayNode();
        for (var view : views) {
            var figures = perRun.addObject();
            figures.put("run", runId(view.run()));
            var asked = view.named(ASKED).stream().findFirst();
            if (asked.isEmpty()) {
                figures.putNull(ASK_TO_NEXT_PM_WAIT_MS);
                figures.putNull(ASK_TO_EXIT_MS);
                passes.add(false);
                byWaitOrEnd.add(false);
                continue;
            }
            var wait = RunReadings.nextPmWait(view, asked.get());
            wait.ifPresent(waits::add);
            putOrNull(figures, ASK_TO_NEXT_PM_WAIT_MS, wait.orElse(null));
            var exit = RunReadings.askToExit(view, asked.get());
            exit.ifPresent(exits::add);
            putOrNull(figures, ASK_TO_EXIT_MS, exit.orElse(null));
            var at = asked.get().path("at").asLong();
            var answerAt = view.named("answer_sent").stream().mapToLong(event -> event.path("at").asLong()).min();
            var beforeAnswer = exit.isPresent() && (answerAt.isEmpty() || at + exit.get() < answerAt.getAsLong());
            figures.put("exit_before_answer", beforeAnswer);
            exitsBeforeAnswer += beforeAnswer ? 1 : 0;
            var worker = asked.get().path(WORKER).asText();
            var generation = asked.get().path(GENERATION).asInt();
            var warm = "warm".equals(view.run().path("form").asText());
            var end = view.events().stream()
                    .filter(event -> event.path("at").asLong() >= at && same(event, worker, generation))
                    .filter(event -> warm ? "wait_started".equals(event.path(EVENT).asText())
                            : WORKER_EXITED.equals(event.path(EVENT).asText()))
                    .findFirst();
            end.ifPresent(event -> times.add(event.path("at").asLong() - at));
            var digests = view.named("worktree_digest").stream().filter(event -> same(event, worker, generation))
                    .toList();
            var same = digests.size() >= 2
                    && digests.getFirst().path("digest").asText().equals(digests.getLast().path("digest").asText());
            stopped += end.isPresent() ? 1 : 0;
            unchanged += same ? 1 : 0;
            passes.add(end.isPresent() && same);
            byWaitOrEnd.add((wait.isPresent() || beforeAnswer) && same);
        }
        group.put("stopped", stopped);
        group.put("worktree_unchanged", unchanged);
        group.put("ask_to_stop_event_ms_median", median(times));
        group.put("ask_to_stop_event_ms_max", max(times));
        group.put("asked_then_pm_wait", waits.size());
        group.put(ASK_TO_NEXT_PM_WAIT_MS + "_median", median(waits));
        group.put(ASK_TO_NEXT_PM_WAIT_MS + "_max", max(waits));
        group.put("exits_observed", exits.size());
        group.put("exits_before_answer", exitsBeforeAnswer);
        group.put(ASK_TO_EXIT_MS + "_median", median(exits));
        group.put(ASK_TO_EXIT_MS + "_max", max(exits));
        group.put(VERDICT_BY_NEXT_PM_WAIT, Scale.verdict(byWaitOrEnd).text());
        group.set("per_run", perRun);
        return passes;
    }

    private static void putOrNull(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static List<Boolean> q4(List<RunView> views, ObjectNode group) {
        var follows = 0;
        var reasked = 0;
        var redone = 0;
        var form = 0;
        var passes = new ArrayList<Boolean>();
        for (var view : views) {
            var followsHere = RunReadings.followsReference(view);
            var reaskedHere = RunReadings.reasked(view);
            var redoneHere = RunReadings.redone(view);
            follows += followsHere ? 1 : 0;
            form += RunReadings.referenceForm(view) ? 1 : 0;
            reasked += reaskedHere;
            redone += redoneHere;
            passes.add(followsHere && reaskedHere == 0 && redoneHere == 0);
        }
        group.put("follows_answer_share", share(follows, views.size()));
        group.put("reference_form_runs", form);
        group.put("answered_question_asked_again", reasked);
        group.put("submitted_plan_task_done_again", redone);
        return passes;
    }

    /**
     * Q5: in the bound form (the replies only restate the options, so the flaw stays open until the bound) a run passes
     * when it ends in a submission with the worker's judgment or an infeasible report, with no lost task and no prose
     * question; otherwise when it asks a second question and never the same one twice. {@code bound_reached} counts
     * the runs whose asks reached their question bound, read from the stub log; it decides whether the next bound runs
     * ({@link Scale#q5Bounds}).
     */
    private static List<Boolean> q5(List<RunView> views, ObjectNode group) {
        var boundForm = views.getFirst().run().path("bound").asBoolean();
        var second = 0;
        var repeated = 0;
        var judged = 0;
        var lost = 0;
        var prose = 0;
        var reached = 0;
        var passes = new ArrayList<Boolean>();
        for (var view : views) {
            var texts = new ArrayList<String>();
            view.named(ASKED).forEach(event -> event.path("question_texts")
                    .forEach(text -> texts.add(normalize(text.asText()))));
            var distinct = new HashSet<>(texts).size();
            var repeatedHere = distinct < texts.size();
            var secondHere = distinct >= 2;
            var lastSubmit = RunReadings.lastSubmitArgs(view);
            var judgedHere = !lastSubmit.path("judgment").asText("").isBlank() || !view.named("infeasible").isEmpty();
            var lostHere = (int) view.named("closed").stream()
                    .filter(event -> "lost".equals(event.path("outcome").asText())).count();
            var proseHere = RunReadings.proseQuestion(view.outputs());
            var bound = view.run().path(QUESTION_BOUND).asInt(QuestionStub.QUESTION_BOUND);
            reached += texts.size() >= bound ? 1 : 0;
            second += secondHere ? 1 : 0;
            repeated += repeatedHere ? 1 : 0;
            judged += judgedHere ? 1 : 0;
            lost += lostHere;
            prose += proseHere ? 1 : 0;
            passes.add(boundForm ? judgedHere && lostHere == 0 && !proseHere : secondHere && !repeatedHere);
        }
        group.put("bound_form", boundForm);
        group.put("bound_reached", reached);
        group.put("second_ask_share", share(second, views.size()));
        group.put("same_question_twice", repeated);
        group.put("ended_with_judgment_or_infeasible", judged);
        group.put("lost_tasks", lost);
        group.put("prose_questions", prose);
        return passes;
    }

    /**
     * Q6: a run passes when its part of Q6 took place, every vote is valid within the re-offer budget, its last
     * submission follows the reference, and consultation and question were not mixed up. A run whose part never took
     * place ({@link #q6Exercised}) demonstrates nothing and is judged a failing run (it counts in {@code runs} and not
     * in {@value #PASSING_RUNS}, so the group fails or is extended as for any failing run).
     */
    private static List<Boolean> q6(List<RunView> views, ObjectNode group) {
        var votes = 0;
        var validVotes = 0;
        var applied = 0;
        var decided = 0;
        var mixups = 0;
        var passes = new ArrayList<Boolean>();
        for (var view : views) {
            var refusals = new HashMap<String, Integer>();
            view.named("vote_refused").forEach(event -> refusals.merge(event.path(TASK).asText(), 1, Integer::sum));
            var runVotes = 0;
            var runValid = 0;
            for (var closed : view.named("closed")) {
                var task = closed.path(TASK).asText();
                if (task.startsWith(QuestionStub.VOTE_PREFIX)) {
                    runVotes++;
                    if (SUBMITTED.equals(closed.path("outcome").asText())
                            && refusals.getOrDefault(task, 0) <= VOTE_REOFFER_BUDGET) {
                        runValid++;
                    }
                }
            }
            decided += (int) view.named("adjudicated").stream()
                    .filter(event -> "decided".equals(event.path("outcome").asText())).count();
            var appliedHere = RunReadings.followsReference(view);
            var mixupsHere = RunReadings.mixups(view);
            votes += runVotes;
            validVotes += runValid;
            applied += appliedHere ? 1 : 0;
            mixups += mixupsHere;
            passes.add(q6Exercised(view, runVotes) && runValid == runVotes && appliedHere && mixupsHere == 0);
        }
        group.put("votes", votes);
        group.put("valid_votes", validVotes);
        group.put("decided_by_quorum", decided);
        group.put("applied_share", share(applied, views.size()));
        group.put("consultation_question_mixups", mixups);
        return passes;
    }

    /**
     * Whether a Q6 run's part took place: a {@code vote} run had at least one vote task, a {@code consult} run
     * consulted at least once ({@code consulted} in its stub log). A run of another or no kind is not held to either.
     *
     * @param view  a Q6 run
     * @param votes its vote tasks
     * @return whether its part of Q6 took place
     */
    static boolean q6Exercised(RunView view, int votes) {
        return switch (view.run().path("kind").asText("")) {
            case "vote" -> votes > 0;
            case "consult" -> !view.named("consulted").isEmpty();
            default -> true;
        };
    }

    /**
     * Q7: a run passes when no slot was held while its record was open ({@value #LEASES_WHILE_OPEN} is 0), its warm
     * worker was recycled, the stub was restarted while the record was open, and the answer was delivered exactly once.
     */
    private static List<Boolean> q7(List<RunView> views, ObjectNode group) {
        var held = 0;
        var recycled = 0;
        var restarted = 0;
        var exactlyOnce = 0;
        var passes = new ArrayList<Boolean>();
        var perRun = QuestionStub.JSON.createArrayNode();
        for (var view : views) {
            var figures = q7Run(view);
            perRun.add(figures);
            var asked = view.named(ASKED).stream().findFirst();
            var answered = view.named(ANSWER_RECORDED).stream().findFirst();
            if (asked.isEmpty() || answered.isEmpty()) {
                passes.add(false);
                continue;
            }
            var from = asked.get().path("at").asLong();
            var to = answered.get().path("at").asLong();
            var heldHere = figures.path(LEASES_WHILE_OPEN).asInt();
            var warm = view.run().path("warm_worker").asText("");
            var recycledHere = view.events().stream().filter(event -> within(event, from, to))
                    .anyMatch(event -> warm.equals(event.path(WORKER).asText())
                            && (WORKER_EXITED.equals(event.path(EVENT).asText())
                            || "wait_end".equals(event.path(EVENT).asText())));
            var restartedHere = view.events().stream().filter(event -> within(event, from, to))
                    .anyMatch(event -> "stub_restart".equals(event.path(EVENT).asText()));
            var onceHere = figures.path("continuing_task_offers").asInt() == 1;
            held += heldHere;
            recycled += recycledHere ? 1 : 0;
            restarted += restartedHere ? 1 : 0;
            exactlyOnce += onceHere ? 1 : 0;
            passes.add(heldHere == 0 && recycledHere && restartedHere && onceHere);
        }
        group.put(LEASES_WHILE_OPEN, held);
        q7HeldFigures(perRun, group);
        group.put("warm_worker_recycled", recycled);
        group.put("stub_restarted_while_open", restarted);
        group.put("answer_delivered_exactly_once", exactlyOnce);
        group.set(Q7_RUNS, perRun);
        return passes;
    }

    private static List<Boolean> r1(List<RunView> views, ObjectNode group) {
        var continued = 0;
        var reread = 0;
        var passes = new ArrayList<Boolean>();
        for (var view : views) {
            var asked = view.named(ASKED).stream().findFirst();
            if (asked.isEmpty()) {
                passes.add(false);
                continue;
            }
            var worker = asked.get().path(WORKER).asText();
            var generation = asked.get().path(GENERATION).asInt();
            var acknowledged = view.named(ACKNOWLEDGED).stream()
                    .anyMatch(event -> "answer".equals(event.path("kind").asText()) && same(event, worker, generation));
            var rereads = RunReadings.rereadsCanary(view.outputs(), asked.get().path("at").asLong());
            reread += rereads ? 1 : 0;
            var pass = acknowledged && RunReadings.namesCanary(view) && !rereads;
            continued += pass ? 1 : 0;
            passes.add(pass);
        }
        group.put("continued_same_generation_share", share(continued, views.size()));
        group.put("canary_read_again", reread);
        return passes;
    }

    private static List<Boolean> r2(List<RunView> views, ObjectNode group) {
        var continued = 0;
        var positive = 0;
        var hung = 0;
        var outcomes = new TreeMap<String, Integer>();
        var ackTimes = new ArrayList<Long>();
        var passes = new ArrayList<Boolean>();
        for (var view : views) {
            if (view.run().path("negative").asBoolean()) {
                var outcome = RunReadings.negativeOutcome(view);
                outcomes.merge(outcome, 1, Integer::sum);
                hung += RunReadings.HUNG.equals(outcome) ? 1 : 0;
                passes.add(!RunReadings.HUNG.equals(outcome));
                continue;
            }
            positive++;
            var pass = RunReadings.continuedFromSession(view);
            if (pass) {
                continued++;
                view.named(ACKNOWLEDGED).stream().filter(event -> "answer".equals(event.path("kind").asText()))
                        .findFirst().ifPresent(event -> ackTimes.add(event.path("after_ms").asLong()));
            }
            passes.add(pass);
        }
        group.put("continued_from_session_share", share(continued, positive));
        group.put("ack_after_ms_median", median(ackTimes));
        group.set("negative_outcomes", QuestionStub.JSON.valueToTree(outcomes));
        group.put("hung_resumes", hung);
        return passes;
    }

    /**
     * R3: a run passes when its fault was detected within the host's deadline (by silence where the acknowledgement is
     * informational), never earlier, and its task then continued exactly once; under the changed criterion a
     * {@link Fault#CALL_CUT} run whose worker survived the host's cut ({@link RunReadings#cutSurvived}) is no lost
     * continuation and passes as well.
     */
    private static List<Boolean> r3(List<RunView> views, ObjectNode group) {
        var detected = 0;
        var early = 0;
        var once = 0;
        var survived = 0;
        var times = new ArrayList<Long>();
        var passes = new ArrayList<Boolean>();
        for (var view : views) {
            var cut = Fault.CALL_CUT.name().equals(view.run().path("fault").asText()) ? RunReadings.hostCut(view)
                    : Optional.<JsonNode>empty();
            if (cut.isPresent() && RunReadings.cutSurvived(view, cut.get())) {
                survived++;
                passes.add(true);
                continue;
            }
            var host = view.host();
            var deadline = (host.ackInformational() ? host.silenceLimit() : host.ackDeadline()).toMillis();
            var detection = view.named(QuestionDriver.LOST_DETECTED).stream().findFirst();
            if (detection.isEmpty()) {
                passes.add(false);
                continue;
            }
            var after = detection.get().path("after_ms").asLong();
            times.add(after);
            var earlyHere = after < deadline;
            var withinHere = !earlyHere && after <= deadline + DETECTION_TOLERANCE_MS
                    + (host.ackInformational() ? host.boundedWait().toMillis() : 0);
            var at = detection.get().path("at").asLong();
            var continuations = view.named(CONTINUED).stream().filter(event -> event.path("at").asLong() >= at).count()
                    + view.named(ANSWER_DELIVERED).stream()
                    .filter(event -> event.path("at").asLong() >= at && event.path("resumed").asBoolean())
                    .count();
            early += earlyHere ? 1 : 0;
            detected += withinHere ? 1 : 0;
            once += continuations == 1 ? 1 : 0;
            passes.add(withinHere && continuations == 1);
        }
        group.put("detected_within_deadline", detected);
        group.put("detected_early", early);
        group.put("continued_exactly_once", once);
        group.put("passed_by_surviving_cut", survived);
        group.put("detection_after_ms_median", median(times));
        return passes;
    }

    private static List<Boolean> r4(List<RunView> views, ObjectNode group) {
        var tokens = new ArrayList<Long>();
        var times = new ArrayList<Long>();
        var follows = 0;
        for (var view : views) {
            tokens.add(RunReadings.tokens(view.outputs()));
            times.add(RunReadings.workerTime(view));
            if (!view.named(ASKED).isEmpty()) {
                follows += RunReadings.followsReference(view) ? 1 : 0;
            }
        }
        group.put("tokens_median", median(tokens));
        group.put("worker_time_ms_median", median(times));
        group.set("tokens", QuestionStub.JSON.valueToTree(tokens));
        group.set("worker_time_ms", QuestionStub.JSON.valueToTree(times));
        group.put("answer_follows_reference", follows);
        return views.stream().map(_ -> Boolean.TRUE).toList();
    }

    // --- figures beside the judges ---

    /**
     * Adds the ids of a group's runs whose last submission does not follow the reference
     * ({@value #NOT_FOLLOWING_REFERENCE_RUNS}) for Q4 and Q6 over all runs, and R4 over its runs that asked.
     *
     * @param item  the item
     * @param views the group's runs
     * @param group the group
     */
    static void notFollowingReference(String item, List<RunView> views, ObjectNode group) {
        if (!Set.of("Q4", "Q6", R4).contains(item)) {
            return;
        }
        var ids = group.putArray(NOT_FOLLOWING_REFERENCE_RUNS);
        for (var view : views) {
            if (R4.equals(item) && view.named(ASKED).isEmpty()) {
                continue;
            }
            if (!RunReadings.followsReference(view)) {
                ids.add(runId(view.run()));
            }
        }
    }

    /**
     * Adds Q4's {@value #CROSS_HOST_RUNS} to a group: its runs whose continuing task ran on another host than the
     * asker's, with the hosts they continued on and their asked, continued and submitted counts.
     *
     * @param views the group's runs
     * @param group the group
     */
    static void crossHostRuns(List<RunView> views, ObjectNode group) {
        var cross = views.stream().filter(view -> RunReadings.crossHost(view.run())).toList();
        var node = group.putObject(CROSS_HOST_RUNS);
        node.put(RUNS, cross.size());
        var hosts = node.putArray("continue_hosts");
        cross.stream().map(view -> view.run().path("continue_host").asText()).distinct().sorted().forEach(hosts::add);
        node.put(ASKED, cross.stream().filter(view -> !view.named(ASKED).isEmpty()).count());
        node.put(CONTINUED, cross.stream().filter(view -> !view.named(CONTINUED).isEmpty()).count());
        node.put(SUBMITTED, cross.stream().filter(view -> !view.named(SUBMITTED).isEmpty()).count());
    }

    /**
     * Adds {@value #RESTART_BREAKDOWN} to an R2 group: its runs split by their record's {@code restart}, each part with
     * {@code runs}, {@code asked}, {@code continued_from_session}, {@code submitted} and {@code timed_out}.
     *
     * @param views the group's runs
     * @param group the group
     */
    static void restartBreakdown(List<RunView> views, ObjectNode group) {
        var breakdown = group.putObject(RESTART_BREAKDOWN);
        for (var restart : List.of(true, false)) {
            var part = views.stream().filter(view -> view.run().path("restart").asBoolean() == restart).toList();
            var counted = QuestionStub.JSON.createObjectNode();
            counts(part, counted);
            var node = breakdown.putObject(String.valueOf(restart));
            node.put(RUNS, part.size());
            node.put(ASKED, counted.path(ASKED).asInt());
            node.put("continued_from_session", part.stream().filter(RunReadings::continuedFromSession).count());
            node.put(SUBMITTED, counted.path(SUBMITTED).asInt());
            node.put(TIMED_OUT, counted.path(TIMED_OUT).asInt());
        }
    }

    /**
     * Adds {@value #EXIT_OBSERVATION} to an R3 group: per run, for the generation detected as lost (else the one the
     * fault was injected into), the time from the delivery of its answer to its observed exit and the detection's time
     * and kind; the group sums the exits observed and before the detection, with medians.
     *
     * @param views the group's runs
     * @param group the group
     */
    static void exitObservations(List<RunView> views, ObjectNode group) {
        var node = group.putObject(EXIT_OBSERVATION);
        var perRun = QuestionStub.JSON.createArrayNode();
        var exitTimes = new ArrayList<Long>();
        var detectionsWithExit = new ArrayList<Long>();
        var exitsBeforeDetection = 0;
        for (var view : views) {
            var figures = perRun.addObject();
            figures.put("run", runId(view.run()));
            var detection = view.named(QuestionDriver.LOST_DETECTED).stream().findFirst();
            var subject = detection.or(() -> view.named(QuestionDriver.FAULT_INJECTED).stream().findFirst());
            if (subject.isEmpty()) {
                figures.putNull(WORKER);
                figures.putNull("exit_observed_after_delivery_ms");
                figures.putNull("detection_after_ms");
                continue;
            }
            var worker = subject.get().path(WORKER).asText();
            var generation = subject.get().path(GENERATION).asInt();
            var observation = RunReadings.exitObservation(view.events(), worker, generation);
            figures.put(WORKER, worker);
            figures.put(GENERATION, generation);
            putOrNull(figures, "exit_observed_after_delivery_ms", observation.exitAfterDeliveryMs());
            putOrNull(figures, "detection_after_ms", detection.map(event -> event.path("after_ms").asLong())
                    .orElse(null));
            if (detection.isPresent()) {
                figures.put("detection_by", detection.get().path("by").asText());
            } else {
                figures.putNull("detection_by");
            }
            if (observation.exitAfterDeliveryMs() != null) {
                exitTimes.add(observation.exitAfterDeliveryMs());
            }
            if (observation.exitAt() != null && detection.isPresent()) {
                detectionsWithExit.add(detection.get().path("after_ms").asLong());
                var before = observation.exitAt() < detection.get().path("at").asLong();
                figures.put("exit_before_detection", before);
                exitsBeforeDetection += before ? 1 : 0;
            } else {
                figures.putNull("exit_before_detection");
            }
        }
        node.put("exits_observed", exitTimes.size());
        node.put("exits_before_detection", exitsBeforeDetection);
        node.put("exit_observed_after_delivery_ms_median", median(exitTimes));
        node.put("detection_after_ms_median_with_exit", median(detectionsWithExit));
        node.set(RUNS, perRun);
    }

    /**
     * Adds the figures of a survived call cut to an R3 {@link Fault#CALL_CUT} group: {@value #CUT_BY_HOST},
     * {@value #CUT_SURVIVED}, and {@value #RE_WAIT_AFTER_CUT_MS} median and max.
     *
     * @param views the group's runs
     * @param group the group
     */
    static void cutSurvival(List<RunView> views, ObjectNode group) {
        var cuts = 0;
        var survived = 0;
        var reWaits = new ArrayList<Long>();
        for (var view : views) {
            var cut = RunReadings.hostCut(view);
            if (cut.isEmpty()) {
                continue;
            }
            cuts++;
            RunReadings.reWaitAfterCut(view, cut.get()).ifPresent(reWaits::add);
            survived += RunReadings.cutSurvived(view, cut.get()) ? 1 : 0;
        }
        group.put(CUT_BY_HOST, cuts);
        group.put(CUT_SURVIVED, survived);
        group.put(RE_WAIT_AFTER_CUT_MS + "_median", median(reWaits));
        group.put(RE_WAIT_AFTER_CUT_MS + "_max", max(reWaits));
    }

    // --- Q7 ---

    /**
     * One connection of a worker generation, from the stub log ({@link #connections(List)}).
     *
     * @param worker     the worker
     * @param generation its generation
     * @param start      its {@code connected} (epoch milliseconds)
     * @param end        its {@code disconnected}, its process's {@code worker_exited} or the stub's stop, whichever is
     *                   first; {@link Long#MAX_VALUE} while the log shows none
     */
    public record Link(String worker, int generation, long start, long end) {
    }

    /**
     * The open time of a Q7 run: from the driver's {@value QuestionDriver#OPEN_TIME_STARTED} (the asker stopped) to the
     * answer ({@code answer_recorded}).
     *
     * @param from its start (epoch milliseconds)
     * @param to   its end (epoch milliseconds)
     */
    public record OpenTime(long from, long to) {
    }

    /**
     * @param events the stub log
     * @return the connections, in the order they ended: each {@code connected} of a generation, ended by its
     *         {@code disconnected}, its process's {@code worker_exited} or the stub's stop
     */
    public static List<Link> connections(List<JsonNode> events) {
        var open = new LinkedHashMap<String, Long>();
        var links = new ArrayList<Link>();
        for (var event : events) {
            var name = event.path(EVENT).asText();
            var at = event.path("at").asLong();
            if (STUB_STOPPED_EVENT.equals(name)) {
                open.forEach((key, start) -> links.add(link(key, start, at)));
                open.clear();
                continue;
            }
            var worker = event.path(WORKER).asText("");
            if (worker.isEmpty()) {
                continue;
            }
            var key = worker + "#" + event.path(GENERATION).asInt();
            if (QuestionDriver.CONNECTED.equals(name)) {
                open.putIfAbsent(key, at);
            } else if ("disconnected".equals(name) || WORKER_EXITED.equals(name)) {
                var start = open.remove(key);
                if (start != null) {
                    links.add(link(key, start, at));
                }
            }
        }
        open.forEach((key, start) -> links.add(link(key, start, Long.MAX_VALUE)));
        return links;
    }

    private static Link link(String key, long start, long end) {
        var cut = key.lastIndexOf('#');
        return new Link(key.substring(0, cut), Integer.parseInt(key.substring(cut + 1)), start, end);
    }

    /**
     * @param view a Q7 run
     * @return its open time, empty when it did not ask, was not answered, or its stub log shows no start of the open
     *         time before the answer
     */
    public static Optional<OpenTime> openTime(RunView view) {
        var answered = view.named(ANSWER_RECORDED).stream().findFirst();
        var started = view.named(QuestionDriver.OPEN_TIME_STARTED).stream().findFirst();
        if (view.named(ASKED).isEmpty() || answered.isEmpty() || started.isEmpty()) {
            return Optional.empty();
        }
        var from = started.get().path("at").asLong();
        var to = answered.get().path("at").asLong();
        return from <= to ? Optional.of(new OpenTime(from, to)) : Optional.empty();
    }

    /**
     * The figures of one Q7 run: {@code offers_while_open} (the offers and deliveries of the entry's tasks between the
     * ask and the answer), and in the open time, for every generation but the warm worker's,
     * {@value #HELD_WAIT_GENERATIONS}, {@value #HELD_WAIT_MS} and {@value #WORKERS_CONNECTED_WHILE_OPEN} (each
     * {@value #UNAVAILABLE} without an open time); {@value #LEASES_WHILE_OPEN} is the offers plus the held
     * generations. Beside them whether the driver ended the asker and the offers of the continuing task.
     *
     * @param view a Q7 run
     * @return its figures
     */
    public static ObjectNode q7Run(RunView view) {
        var node = QuestionStub.JSON.createObjectNode();
        node.put("run", runId(view.run()));
        var asked = view.named(ASKED).stream().findFirst();
        var answered = view.named(ANSWER_RECORDED).stream().findFirst();
        var offers = 0L;
        if (asked.isPresent() && answered.isPresent()) {
            var from = asked.get().path("at").asLong();
            var to = answered.get().path("at").asLong();
            var entry = view.run().path("task_id").asText();
            offers = view.events().stream().filter(event -> within(event, from, to))
                    .filter(event -> OFFERED.equals(event.path(EVENT).asText())
                            || ANSWER_DELIVERED.equals(event.path(EVENT).asText()))
                    .filter(event -> event.path(TASK).asText().startsWith(entry)).count();
        }
        node.put("offers_while_open", offers);
        var warm = view.run().path("warm_worker").asText("");
        var open = openTime(view);
        var heldGenerations = 0L;
        if (open.isPresent()) {
            var from = open.get().from();
            var to = open.get().to();
            var held = QuestionDriver.waits(view.events()).stream().filter(wait -> !warm.equals(wait.worker()))
                    .filter(wait -> wait.start() < to && wait.end() > from).toList();
            heldGenerations = held.stream().map(wait -> wait.worker() + "#" + wait.generation()).distinct().count();
            node.put("open_time_ms", to - from);
            node.put(HELD_WAIT_GENERATIONS, heldGenerations);
            node.put(HELD_WAIT_MS, held.stream().mapToLong(wait -> Math.min(wait.end(), to)
                    - Math.max(wait.start(), from)).sum());
            node.put(WORKERS_CONNECTED_WHILE_OPEN, connections(view.events()).stream()
                    .filter(link -> !warm.equals(link.worker())).filter(link -> link.start() < to && link.end() > from)
                    .map(link -> link.worker() + "#" + link.generation()).distinct().count());
        } else {
            node.put(HELD_WAIT_GENERATIONS, UNAVAILABLE);
            node.put(HELD_WAIT_MS, UNAVAILABLE);
            node.put(WORKERS_CONNECTED_WHILE_OPEN, UNAVAILABLE);
        }
        node.put(LEASES_WHILE_OPEN, offers + heldGenerations);
        var ended = QuestionDriver.ASKER_ENDED_BY_DRIVER;
        node.put(ended, view.run().path(ended).asBoolean());
        continuingOffers(view, asked.map(event -> event.path(DECISION).asText()).orElse(""), node);
        return node;
    }

    private static void continuingOffers(RunView view, String decision, ObjectNode node) {
        var continued = view.named(CONTINUED).stream().filter(event -> decision.equals(event.path(DECISION).asText()))
                .toList();
        if (continued.size() != 1) {
            node.put("continuing_task_offers", 0);
            node.put("fenced_unacknowledged_offers", 0);
            node.putNull(OFFER_COUNTED_TWICE_FENCED);
            return;
        }
        var task = continued.getFirst().path(TASK).asText();
        var offers = view.named(OFFERED).stream().filter(event -> task.equals(event.path(TASK).asText())).toList();
        var fenced = offers.stream().filter(offer -> {
            var worker = offer.path(WORKER).asText();
            var generation = offer.path(GENERATION).asInt();
            var acknowledged = view.named(ACKNOWLEDGED).stream().anyMatch(event -> same(event, worker, generation)
                    && task.equals(event.path(TASK).asText()));
            var released = view.named("released").stream().anyMatch(event -> same(event, worker, generation)
                    && task.equals(event.path(TASK).asText()) && "fenced".equals(event.path("reason").asText()));
            return !acknowledged && released;
        }).count();
        node.put("continuing_task_offers", offers.size());
        node.put("fenced_unacknowledged_offers", fenced);
        if (offers.size() > 1) {
            node.put(OFFER_COUNTED_TWICE_FENCED, offers.size() - fenced == 1);
        } else {
            node.putNull(OFFER_COUNTED_TWICE_FENCED);
        }
    }

    private static void q7HeldFigures(ArrayNode perRun, ObjectNode group) {
        var available = 0;
        var connected = 0L;
        var heldMs = 0L;
        var heldGenerations = 0L;
        var endedByDriver = 0;
        var fencedOffers = 0;
        for (var run : perRun) {
            if (run.path(HELD_WAIT_MS).isNumber()) {
                available++;
                connected += run.path(WORKERS_CONNECTED_WHILE_OPEN).asLong();
                heldMs += run.path(HELD_WAIT_MS).asLong();
                heldGenerations += run.path(HELD_WAIT_GENERATIONS).asLong();
            }
            endedByDriver += run.path(QuestionDriver.ASKER_ENDED_BY_DRIVER).asBoolean() ? 1 : 0;
            fencedOffers += run.path(OFFER_COUNTED_TWICE_FENCED).asBoolean() ? 1 : 0;
        }
        if (available == 0) {
            group.put(WORKERS_CONNECTED_WHILE_OPEN, UNAVAILABLE);
            group.put(HELD_WAIT_MS, UNAVAILABLE);
            group.put(HELD_WAIT_GENERATIONS, UNAVAILABLE);
        } else {
            group.put(WORKERS_CONNECTED_WHILE_OPEN, connected);
            group.put(HELD_WAIT_MS, heldMs);
            group.put(HELD_WAIT_GENERATIONS, heldGenerations);
        }
        group.put("open_time_unavailable", perRun.size() - available);
        group.put(QuestionDriver.ASKER_ENDED_BY_DRIVER, endedByDriver);
        group.put(FENCED_UNACKNOWLEDGED_OFFER_RUNS, fencedOffers);
    }

    private static boolean within(JsonNode event, long from, long to) {
        var at = event.path("at").asLong();
        return at > from && at < to;
    }

    // --- R1 and R2 ---

    private static JsonNode longestPassing(ArrayNode groups, String parameter) {
        var longest = new TreeMap<String, Long>();
        for (var group : groups) {
            var key = group.path(HOST).asText() + " " + group.path(MODEL).asText();
            var value = group.path(parameter).asLong(-1);
            if (Verdict.PASS.text().equals(group.path(VERDICT).asText()) && value >= 0) {
                longest.merge(key, value, Math::max);
            }
        }
        return QuestionStub.JSON.valueToTree(longest);
    }

    /**
     * The longest passing value of a parameter per form and host and model; a form without a passing group maps to an
     * empty object.
     *
     * @param groups    the groups of R1 or R2
     * @param parameter {@code duration_s} or {@code age_s}
     * @return the figure per form
     */
    static ObjectNode longestPassingPerForm(ArrayNode groups, String parameter) {
        var forms = new TreeMap<String, ArrayNode>();
        for (var group : groups) {
            forms.computeIfAbsent(group.path("form").asText(), _ -> QuestionStub.JSON.createArrayNode()).add(group);
        }
        var perForm = QuestionStub.JSON.createObjectNode();
        forms.forEach((form, members) -> perForm.set(form, longestPassing(members, parameter)));
        return perForm;
    }

    /**
     * The state of the longest-first descent of R1 or R2 per host, model and form: the durations run, the verdict of
     * the shortest of them, and the next duration to run ({@link Scale#nextDuration}): none after a pass or after the
     * shortest, {@code extend to 10} where the shortest one's runs disagree. The descent goes to a shorter duration
     * only after a measured failure: a shortest duration without a judged run (verdict {@code none}, as a usage-limit
     * refusal leaves it) is no failure and is run again, so its own duration is the next.
     *
     * @param groups    the groups of R1 or R2
     * @param parameter {@code duration_s} or {@code age_s}
     * @return the descent per {@code <host> <model> <form>}
     */
    static ObjectNode descent(ArrayNode groups, String parameter) {
        var verdicts = new TreeMap<String, TreeMap<Long, List<Verdict>>>();
        for (var group : groups) {
            var value = group.path(parameter).asLong(-1);
            if (value < 0 || group.path("negative").asBoolean()) {
                continue;
            }
            var key = group.path(HOST).asText() + " " + group.path(MODEL).asText() + " " + group.path("form").asText();
            verdicts.computeIfAbsent(key, _ -> new TreeMap<>()).computeIfAbsent(value, _ -> new ArrayList<>())
                    .add(verdictOf(group.path(VERDICT).asText()));
        }
        var descent = QuestionStub.JSON.createObjectNode();
        verdicts.forEach((key, perValue) -> {
            var node = descent.putObject(key);
            var run = node.putArray("run_s");
            perValue.descendingKeySet().forEach(run::add);
            var shortest = perValue.firstKey();
            var verdict = combine(perValue.get(shortest));
            node.put("shortest_verdict", verdict.text());
            if (verdict == Verdict.EXTEND) {
                node.put("next", verdict.text());
            } else if (verdict == Verdict.NONE) {
                node.put("next", shortest);
            } else if (Scale.R1_R2_DURATIONS.contains(shortest)) {
                var next = Scale.nextDuration(shortest, verdict == Verdict.PASS);
                if (next.isPresent()) {
                    node.put("next", next.get());
                } else {
                    node.put("next", NONE);
                }
            } else {
                node.put("next", NONE);
            }
        });
        return descent;
    }

    // --- writing ---

    /**
     * Where a result is written and which run records it aggregates, from the system properties: {@value #SUFFIX}
     * writes {@code <item>-<suffix>.json} instead of {@code <item>.json}; {@value #INVOCATIONS} ({@code <id>,<id>,...},
     * only with a suffix) aggregates only the records of the named invocations; and {@value #ALLOW_MISSING_EVIDENCE}
     * lets a result with missing evidence replace an existing file.
     *
     * @param directory            the results directory
     * @param suffix               the suffix of the file name, {@code null} for none
     * @param invocations          the invocations to aggregate, empty for all
     * @param allowMissingEvidence whether a result with missing evidence may replace an existing file
     */
    public record ResultTarget(Path directory, String suffix, Set<String> invocations, boolean allowMissingEvidence) {

        /** The property of the suffix of the result file. */
        public static final String SUFFIX = "pm.experiment.result.suffix";
        /** The property of the invocations a suffixed result aggregates. */
        public static final String INVOCATIONS = "pm.experiment.result.invocations";
        /** The property that lets a result with missing evidence replace an existing file. */
        public static final String ALLOW_MISSING_EVIDENCE = "pm.experiment.result.allow-missing-evidence";
        /** The results directory of the items. */
        public static final Path DIRECTORY = Path.of("target", "verification-results", "question-loop");

        private static final Pattern SUFFIX_FORM = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

        /**
         * @param directory            the results directory
         * @param suffix               the suffix of the file name, {@code null} for none
         * @param invocations          the invocations to aggregate, empty for all
         * @param allowMissingEvidence whether a result with missing evidence may replace an existing file
         * @throws IllegalArgumentException for a suffix of other characters, or invocations without a suffix
         */
        public ResultTarget {
            if (suffix != null && !SUFFIX_FORM.matcher(suffix).matches()) {
                throw new IllegalArgumentException("-D" + SUFFIX + "=" + suffix + ": letters, digits, '.', '_' and '-' "
                        + "only, starting with a letter or digit");
            }
            if (suffix == null && !invocations.isEmpty()) {
                throw new IllegalArgumentException("-D" + INVOCATIONS + " needs -D" + SUFFIX + ": a result of some "
                        + "invocations never replaces the item's result file");
            }
            invocations = Set.copyOf(invocations);
        }

        /** @return the target of the system properties, in {@link #DIRECTORY} */
        public static ResultTarget fromProperties() {
            var suffix = System.getProperty(SUFFIX, "").strip();
            var invocations = new TreeSet<String>();
            for (var id : System.getProperty(INVOCATIONS, "").split(",")) {
                if (!id.isBlank()) {
                    invocations.add(id.strip());
                }
            }
            return new ResultTarget(DIRECTORY, suffix.isEmpty() ? null : suffix, invocations,
                    Boolean.getBoolean(ALLOW_MISSING_EVIDENCE));
        }

        /**
         * @param item the item
         * @return the result file of the item: {@code <item>.json}, or {@code <item>-<suffix>.json}
         */
        public Path file(String item) {
            return directory.resolve(suffix == null ? item + ".json" : item + "-" + suffix + ".json");
        }

        /**
         * @param records run or invocation records of an item
         * @return those of the named invocations, all without a name
         * @throws IllegalArgumentException if a named invocation has no record among them
         */
        public List<JsonNode> select(List<JsonNode> records) {
            if (invocations.isEmpty()) {
                return records;
            }
            var selected = records.stream().filter(node -> invocations.contains(node.path(INVOCATION).asText()))
                    .toList();
            var found = selected.stream().map(node -> node.path(INVOCATION).asText()).collect(Collectors.toSet());
            var absent = new TreeSet<>(invocations);
            absent.removeAll(found);
            if (!absent.isEmpty()) {
                throw new IllegalArgumentException("-D" + INVOCATIONS + " names invocations without a record: "
                        + absent);
            }
            return selected;
        }
    }

    /**
     * The refusal to replace an existing result file with a result whose evidence is missing without
     * {@value ResultTarget#ALLOW_MISSING_EVIDENCE}.
     */
    public static final class MissingEvidenceRefusal extends IOException {

        private static final long serialVersionUID = 1L;

        MissingEvidenceRefusal(String message) {
            super(message);
        }
    }

    /**
     * @param result a result
     * @return its JSON as every result file carries it: pretty printed, with a final line break
     * @throws IOException if it cannot be rendered
     */
    public static String render(ObjectNode result) throws IOException {
        return QuestionStub.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n";
    }

    /**
     * Writes an item's result to its target's file. A result with runs whose stub log is missing never replaces an
     * existing file unless the target allows it: the existing file was written from that evidence, and its loss must
     * not rewrite it.
     *
     * @param target where the result goes
     * @param item   the item
     * @param result its result
     * @return the file written
     * @throws IOException if it cannot be written, or the guard refuses it ({@link MissingEvidenceRefusal})
     */
    public static Path write(ResultTarget target, String item, ObjectNode result) throws IOException {
        var file = target.file(item);
        var missing = result.path(EVIDENCE_MISSING).asInt(0);
        if (missing > 0 && Files.exists(file) && !target.allowMissingEvidence()) {
            throw new MissingEvidenceRefusal("not rewritten: " + file + " exists, and " + missing + " run records of "
                    + item + " have no stub log (" + EVIDENCE_MISSING + "); -D" + ResultTarget.ALLOW_MISSING_EVIDENCE
                    + "=true writes it with them counted apart, -D" + ResultTarget.SUFFIX + "=<text> writes a file of "
                    + "its own");
        }
        Files.createDirectories(target.directory());
        Files.writeString(file, render(result), StandardCharsets.UTF_8);
        return file;
    }
}
