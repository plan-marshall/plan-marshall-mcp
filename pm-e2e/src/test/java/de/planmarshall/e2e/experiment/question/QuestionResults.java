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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


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
 * {@value #NOT_AT_SCALE}. Every group also counts its runs that timed out, did not ask, ended early, ended early in a
 * continuation generation ({@value #CONTINUATION_EARLY_EXIT}), met a quota error of the harness, asked, continued and
 * submitted, figures no verdict reads; where a group has quota errors, it also reports its verdict without them
 * ({@code verdict_without_quota_error}), and where its verdict without its continuation early exits reads differently,
 * that verdict ({@value #VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT}): additional figures that do not replace the
 * verdict. The limits come from every invocation it aggregates ({@link #result}).
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

    /** The items whose fixtures force the ask: the apply-answer and two-flaw fixtures. */
    static final Set<String> FORCED_ITEMS = Set.of("Q1", "Q2", "Q3", "Q4", "Q5", "Q6", "Q7", "R3");

    /** The model of a run on the harness's default model, which the launch does not name. */
    static final String HARNESS_DEFAULT = "harness default";
    /** The field of an invocation record with its run limit ({@code pm.experiment.run-timeout}, an ISO duration). */
    static final String RUN_TIMEOUT = "run_timeout";
    /** The field of an invocation record whose run limit was supplied at re-aggregation, not recorded by it. */
    static final String RUN_TIMEOUT_SUPPLIED = "run_timeout_supplied";
    /** The field of a run record of a run whose worker exited without a single call to the stub. */
    static final String EARLY_EXIT = "early_exit";
    /**
     * The field of a run record, and the group column, of a run whose continuation generation (a generation of the
     * asking worker after the one that asked) connected and exited without a single call to the stub
     * ({@link #continuationExit(RunView)}).
     */
    static final String CONTINUATION_EARLY_EXIT = "continuation_early_exit";
    /**
     * The verdict of a group or an item without its {@value #CONTINUATION_EARLY_EXIT} runs, reported only where it
     * reads differently from the verdict (and, for a group, where runs remain to judge without them): an additional
     * figure, not the verdict.
     */
    static final String VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT = "verdict_without_continuation_early_exit";
    /** The exit codes of a process the driver ended ({@code SIGTERM}, {@code SIGKILL}): no exit of its own. */
    static final Set<Integer> TERMINATED_EXITS = Set.of(143, 137);
    /** The field of a run record of a run that waited out its run limit. */
    static final String TIMED_OUT = "timed_out";
    /**
     * The field of a run record, and the group column, of a run whose harness answered with a quota or limit error
     * ({@link #QUOTA_PATTERN}).
     */
    static final String QUOTA_ERROR = "quota_error";
    /**
     * A harness's quota or limit error in its own output or {@code stderr}: the text {@code quota} in any case.
     * Antigravity answered {@code error: Individual quota reached. Please upgrade your subscription to increase your
     * limits. Resets in 42h26m24s.} on {@code stderr} and as the {@code error} of its result object in the last three
     * Antigravity runs of the Q3 smoke pass (2026-10-22). Lines of {@code pm-exec}'s own do not count.
     */
    static final Pattern QUOTA_PATTERN = Pattern.compile("quota", Pattern.CASE_INSENSITIVE);
    /** The per-run figure of Q3: the milliseconds from the ask to the asking generation's next {@code pm_wait}. */
    static final String ASK_TO_NEXT_PM_WAIT_MS = "ask_to_next_pm_wait_ms";
    /** The per-run figure of Q3: the milliseconds from the ask to the exit of the asking generation's process. */
    static final String ASK_TO_EXIT_MS = "ask_to_exit_ms";
    /** The verdict of a group or an item without its quota-error runs: an additional figure, not the verdict. */
    static final String VERDICT_WITHOUT_QUOTA_ERROR = "verdict_without_quota_error";
    /**
     * The verdict of a Q3 group with the stop read as the criterion names it, warm or fresh: the asking generation's
     * next {@code pm_wait}, or the end of its turn (its process exit before the answer was sent); an additional
     * figure, not the verdict.
     */
    static final String VERDICT_BY_NEXT_PM_WAIT = "verdict_by_next_pm_wait_or_end";

    private static final String LIMITS = "limits";
    private static final String NOT_RECORDED = "not recorded";
    private static final String OPENCODE = "opencode";
    private static final String MODEL = "model";
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
     * The verdict itself is not changed. An invocation with fewer run records than it planned (killed or stopped) is
     * flagged {@code incomplete: true} with its {@code planned_runs}, and a limit of the result names it.
     *
     * @param item        the item
     * @param result      its result ({@link #evaluate(String, List)})
     * @param runs        the run records it was evaluated from
     * @param invocations the invocation records of the item
     */
    static void scale(String item, ObjectNode result, List<JsonNode> runs, List<JsonNode> invocations) {
        var counts = recordsPerInvocation(runs);
        var known = new HashMap<String, JsonNode>();
        invocations.forEach(invocation -> known.put(invocation.path(INVOCATION).asText(), invocation));
        var array = result.putArray("invocations");
        var atScale = !counts.isEmpty();
        var small = false;
        var large = false;
        var incomplete = new ArrayList<String>();
        for (var entry : counts.entrySet()) {
            var recorded = known.get(entry.getKey());
            var node = array.addObject();
            node.put(INVOCATION, entry.getKey());
            node.put("run_records", entry.getValue());
            if (recorded == null) {
                node.put("settings", NOT_RECORDED);
                atScale = false;
                continue;
            }
            var planned = recorded.path("planned_runs").asInt(-1);
            if (planned >= 0 && entry.getValue() < planned) {
                node.put("incomplete", true);
                node.put("planned_runs", planned);
                incomplete.add(entry.getKey() + " recorded " + entry.getValue() + " of its " + planned
                        + " planned runs");
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
        if (!incomplete.isEmpty()) {
            limit(result, "Not every invocation recorded all the runs it planned; it was killed or stopped before the "
                    + "rest ran, and the missing runs are in no figure: " + String.join("; ", incomplete) + ".");
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

    /** The count of the scenario recipes R4 does not use, read only when the item is R4. */
    @FunctionalInterface
    interface UnusedScenarios {
        int count() throws IOException;
    }

    /**
     * The whole result of an item from its run records and the records of every invocation of it, as the measurement
     * and the re-aggregation write it: the figures and verdict ({@link #evaluate(String, List)}), the limits of all
     * invocations that contributed run records (the fixture wording, OpenCode's data home, the run limits, the early
     * exits waited out, the continuation early exits, for R4 its own), and the bookkeeping of the invocations ({@link #scale}). The limits come from
     * the union of the invocations, so a later invocation without OpenCode does not drop the limit of an earlier one
     * with it.
     *
     * @param item        the item
     * @param runs        its run records
     * @param invocations its invocation records
     * @param unused      the count of unused scenario recipes, asked for R4 only
     * @return the result
     * @throws IOException if a run's files or the scenario recipes cannot be read
     */
    static ObjectNode result(String item, List<JsonNode> runs, List<JsonNode> invocations, UnusedScenarios unused)
            throws IOException {
        var result = evaluate(item, runs);
        var contributing = contributing(runs, invocations);
        if ("R4".equals(item)) {
            limitsOfR4(result, unused.count(), max(contributing.values(), "large_runs"), max(contributing.values(),
                    RUNS));
        }
        limitsOfFixtures(result, item);
        var openCodeModels = openCodeModels(runs, contributing);
        if (!openCodeModels.isEmpty()) {
            limitOfOpenCodeDataHome(result, openCodeModels);
        }
        limitOfRunLimits(result, runs, contributing);
        limitOfEarlyExitsWaitedOut(result, runs);
        limitOfContinuationEarlyExits(result, runs);
        limitOfQuotaErrors(result, runs);
        if ("Q3".equals(item)) {
            limitOfQ3Times(result);
        }
        scale(item, result, runs, invocations);
        return result;
    }

    /**
     * Adds a limit naming the runs whose harness answered with a quota or limit error ({@link #quotaError(RunView)}):
     * they count as {@value #QUOTA_ERROR}, not as timed out, early exit or no ask; the verdict reads them as before,
     * and {@code verdict_without_quota_error} is the verdict without them.
     *
     * @param result the result
     * @param runs   its run records
     * @throws IOException if a run's files cannot be read
     */
    static void limitOfQuotaErrors(ObjectNode result, List<JsonNode> runs) throws IOException {
        var affected = new ArrayList<String>();
        for (var run : runs) {
            if (quotaError(view(run))) {
                affected.add(runId(run));
            }
        }
        if (affected.isEmpty()) {
            return;
        }
        limit(result, "Runs whose harness answered with a quota or limit error (the text 'quota' in its own output or "
                + "stderr) count as quota_error, not as timed_out, early_exit or no_ask. Their records were written "
                + "before the error ended the wait at once, as timed_out where the worker had called the stub, as "
                + "early_exit where it had not. The verdict reads them as before; verdict_without_quota_error is the "
                + "verdict without them, an additional figure: " + String.join(", ", affected) + ".");
    }

    /**
     * Adds the limit of the time figures of Q3: what each one measures, and that the verdict still reads the stop
     * event ({@code ask_to_stop_event_ms_*}, formerly {@code ask_to_end_ms_*}).
     *
     * @param result the result of Q3
     */
    static void limitOfQ3Times(ObjectNode result) {
        limit(result, "ask_to_stop_event_ms (formerly ask_to_end_ms) is the time from the ask to the event the "
                + "verdict reads as the stop: for a warm worker its next wait_started, for a fresh worker the exit of "
                + "the asking generation's process. A fresh worker that called pm_wait after the ask and kept calling "
                + "it on wait_again did not end its turn; the driver answered only after its silence limit (bounded "
                + "wait plus grace), the answer fenced the generation, and the exit followed: for such a run the "
                + "figure is a silence-limit wait, not the time to the end of the turn. ask_to_next_pm_wait_ms is the "
                + "time from the ask to the asking generation's first pm_wait after it (null when none), and "
                + "ask_to_exit_ms the time to its process exit where one is observed, with exits_before_answer "
                + "counting those before the answer was sent. verdict_by_next_pm_wait_or_end reads as the stop the "
                + "next pm_wait or an exit before the answer, warm or fresh (stopped_by_next_pm_wait_or_end runs), an "
                + "additional figure that does not replace the verdict.");
    }

    /**
     * Supplies the run limit of invocations recorded before the run limit was part of their settings, for a
     * re-aggregation: each named invocation without {@value #RUN_TIMEOUT} gets it, marked
     * {@value #RUN_TIMEOUT_SUPPLIED}; a recorded run limit is kept.
     *
     * @param invocations the invocation records
     * @param supplied    {@code <invocation>=<ISO duration>}, comma-separated; empty for none
     * @return the invocation records, copied where a run limit was supplied
     * @throws IllegalArgumentException if an entry has no {@code =}, its duration does not parse, or it names no
     *                                  invocation of the records
     */
    static List<JsonNode> supplyRunTimeouts(List<JsonNode> invocations, String supplied) {
        var values = new LinkedHashMap<String, String>();
        for (var entry : supplied.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            var parts = entry.strip().split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("run limit without '=': " + entry);
            }
            values.put(parts[0].strip(), Duration.parse(parts[1].strip()).toString());
        }
        var copies = new ArrayList<JsonNode>();
        for (var invocation : invocations) {
            var id = invocation.path(INVOCATION).asText();
            var value = values.remove(id);
            if (value == null || invocation.has(RUN_TIMEOUT)) {
                copies.add(invocation);
                continue;
            }
            var copy = invocation.deepCopy();
            ((ObjectNode) copy).put(RUN_TIMEOUT, value);
            ((ObjectNode) copy).put(RUN_TIMEOUT_SUPPLIED, true);
            copies.add(copy);
        }
        if (!values.isEmpty()) {
            throw new IllegalArgumentException("run limit supplied for no invocation of the records: "
                    + values.keySet());
        }
        return copies;
    }

    /**
     * @param run a run record
     * @return its model, {@value #HARNESS_DEFAULT} when the launch named none (recorded as {@code null} before)
     */
    static String model(JsonNode run) {
        var model = run.path(MODEL).asText("");
        return model.isEmpty() || "null".equals(model) ? HARNESS_DEFAULT : model;
    }

    private static Map<String, Integer> recordsPerInvocation(List<JsonNode> runs) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var run : runs) {
            counts.merge(invocationOf(run), 1, Integer::sum);
        }
        return counts;
    }

    private static String invocationOf(JsonNode run) {
        var id = run.path(INVOCATION).asText("");
        return id.isEmpty() ? UNKNOWN_INVOCATION : id;
    }

    /** @return the recorded invocations that contributed run records, by id, in the order of their records */
    private static Map<String, JsonNode> contributing(List<JsonNode> runs, List<JsonNode> invocations) {
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

    private static int max(Collection<JsonNode> invocations, String field) {
        return invocations.stream().mapToInt(invocation -> invocation.path(field).asInt()).max().orElse(0);
    }

    /**
     * The OpenCode models of the invocations on the global data home whose run records start OpenCode: as asker, as
     * continuing host, or as a voter; the invocation's {@code opencode_models} where it recorded them, else the models
     * of its OpenCode run records. The harness default is {@code null}.
     */
    private static Set<String> openCodeModels(List<JsonNode> runs, Map<String, JsonNode> contributing) {
        var models = new HashSet<String>();
        for (var entry : contributing.entrySet()) {
            var invocation = entry.getValue();
            if (!"global".equals(invocation.path("opencode_data_home").asText())) {
                continue;
            }
            var own = runs.stream().filter(run -> entry.getKey().equals(invocationOf(run))).toList();
            var startsOpenCode = own.stream().anyMatch(run -> OPENCODE.equals(run.path(HOST).asText())
                    || OPENCODE.equals(run.path("continue_host").asText()) || "vote".equals(run.path("kind").asText()));
            if (!startsOpenCode) {
                continue;
            }
            if (invocation.path("opencode_models").isArray()) {
                invocation.path("opencode_models").forEach(model -> models.add(model.isNull() ? null : model.asText()));
            } else {
                own.stream().filter(run -> OPENCODE.equals(run.path(HOST).asText())).map(QuestionResults::model)
                        .forEach(model -> models.add(HARNESS_DEFAULT.equals(model) ? null : model));
            }
        }
        return models;
    }

    /**
     * Adds the run limits to a result when its invocations ran with different ones, or did not record theirs: the run
     * limit per host of the run records ({@code pm.experiment.run-timeout}; a run waits that long plus four times its
     * hold), and which of them were supplied at re-aggregation.
     *
     * @param result       the result
     * @param runs         its run records
     * @param contributing the recorded invocations that contributed them, by id
     */
    static void limitOfRunLimits(ObjectNode result, List<JsonNode> runs, Map<String, JsonNode> contributing) {
        var perHost = new TreeMap<String, Set<String>>();
        var values = new TreeSet<String>();
        var supplied = new TreeSet<String>();
        for (var run : runs) {
            var invocation = contributing.get(invocationOf(run));
            var value = invocation == null ? NOT_RECORDED : invocation.path(RUN_TIMEOUT).asText(NOT_RECORDED);
            perHost.computeIfAbsent(run.path(HOST).asText(), _ -> new TreeSet<>()).add(value);
            values.add(value);
            if (invocation != null && invocation.path(RUN_TIMEOUT_SUPPLIED).asBoolean()) {
                supplied.add(invocationOf(run));
            }
        }
        if (values.size() < 2 && !values.contains(NOT_RECORDED)) {
            return;
        }
        var named = perHost.entrySet().stream().map(entry -> entry.getKey() + " " + String.join(" and ",
                entry.getValue())).toList();
        var text = new StringBuilder(values.size() < 2 ? "The run limit of the invocations is not recorded"
                : "The invocations ran with different run limits (pm.experiment.run-timeout; a run waits that long "
                + "plus four times its hold): " + String.join(", ", named));
        text.append('.');
        if (!supplied.isEmpty()) {
            text.append(" The run limits of ").append(String.join(", ", supplied)).append(" were not recorded by the "
                    + "invocation and are supplied at re-aggregation.");
        }
        limit(result, text.toString());
    }

    /**
     * Adds a limit when runs whose worker exited without a single call to the stub ({@value #EARLY_EXIT}) were waited
     * out to their run limit and recorded as {@value #TIMED_OUT} too: the records of invocations before the early exit
     * ended the wait at once.
     *
     * @param result the result
     * @param runs   its run records
     * @throws IOException if a run's files cannot be read
     */
    private static void limitOfEarlyExitsWaitedOut(ObjectNode result, List<JsonNode> runs) throws IOException {
        var waitedOut = new TreeMap<String, Integer>();
        for (var run : runs) {
            var view = view(run);
            if (run.path(TIMED_OUT).asBoolean() && earlyExit(view) && !quotaError(view)) {
                waitedOut.merge(run.path(HOST).asText(), 1, Integer::sum);
            }
        }
        if (waitedOut.isEmpty()) {
            return;
        }
        var named = waitedOut.entrySet().stream().map(entry -> entry.getKey() + " " + entry.getValue()).toList();
        limit(result, "Runs whose worker exited without a single call to the stub count as early_exit; those of "
                + "invocations before the early exit ended the wait at once waited out their run limit and count as "
                + "timed_out too: " + String.join(", ", named) + ".");
    }

    /**
     * Adds a limit when runs have a continuation generation that connected and exited without a call
     * ({@link #continuationExit(RunView)}, quota-error runs aside): they count as {@value #CONTINUATION_EARLY_EXIT},
     * not as early_exit or timed_out. The limit names the runs of records written before the driver ended the wait
     * on such an exit, which waited out their run limit and were recorded as timed_out: reclassified where the stub
     * log shows the generation's own exit ({@link ContinuationExit#EXITED}), kept as timed_out and marked as
     * waited-out continuation exits where it does not ({@link ContinuationExit#UNCLEAR}). It also states how the
     * verdict reads them: as before, the verdict logic being unchanged, with
     * {@value #VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT} where a group's verdict without them reads differently.
     *
     * @param result the result, its groups evaluated
     * @param runs   its run records
     * @throws IOException if a run's files cannot be read
     */
    static void limitOfContinuationEarlyExits(ObjectNode result, List<JsonNode> runs) throws IOException {
        var counted = new TreeMap<String, Integer>();
        var recorded = new TreeMap<String, Integer>();
        var reclassified = new TreeMap<String, List<String>>();
        var waitedOut = new TreeMap<String, List<String>>();
        for (var run : runs) {
            var view = view(run);
            if (quotaError(view)) {
                continue;
            }
            var host = run.path(HOST).asText();
            var exit = continuationExit(view);
            if (exit == ContinuationExit.EXITED) {
                counted.merge(host, 1, Integer::sum);
            }
            if (run.path(CONTINUATION_EARLY_EXIT).asBoolean()) {
                recorded.merge(host, 1, Integer::sum);
            } else if (exit == ContinuationExit.EXITED && run.path(TIMED_OUT).asBoolean()) {
                reclassified.computeIfAbsent(host, _ -> new ArrayList<>()).add(runId(run));
            } else if (exit == ContinuationExit.UNCLEAR) {
                waitedOut.computeIfAbsent(host, _ -> new ArrayList<>()).add(runId(run));
            }
        }
        if (counted.isEmpty() && waitedOut.isEmpty()) {
            return;
        }
        var text = new StringBuilder("A continuation generation (a generation of the asking worker after the one "
                + "that asked) that connected and exited without a single call to the stub counts as "
                + "continuation_early_exit, not as early_exit and not as timed_out; its run keeps its ask, counts in "
                + "continued (the stub continued the task after the answer), and not in submitted");
        if (!counted.isEmpty()) {
            text.append(": ").append(String.join(", ", counted.entrySet().stream()
                    .map(entry -> entry.getKey() + " " + entry.getValue()).toList()));
        }
        text.append(". The driver ends the wait at once on such an exit");
        if (!recorded.isEmpty()) {
            text.append(" (recorded so: ").append(String.join(", ", recorded.entrySet().stream()
                    .map(entry -> entry.getKey() + " " + entry.getValue()).toList())).append(')');
        }
        text.append('.');
        if (!reclassified.isEmpty()) {
            text.append(" Records written before it waited out their run limit and were recorded as timed_out; where "
                    + "the stub log shows the generation's own exit (worker_exited with an exit code other than the "
                    + "driver's termination, 143 or 137) they are reclassified as continuation_early_exit: ")
                    .append(namedRuns(reclassified)).append('.');
        }
        if (!waitedOut.isEmpty()) {
            text.append(" Records whose stub log shows no such exit of the continuation generation stay timed_out and "
                    + "are marked here as waited-out continuation exits: ").append(namedRuns(waitedOut)).append('.');
        }
        var differing = new ArrayList<String>();
        result.path("groups").forEach(group -> {
            if (group.has(VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT)) {
                differing.add(group.path("group").asText());
            }
        });
        text.append(" The verdict logic is unchanged and reads these runs as before; ").append(differing.isEmpty()
                ? "no group's verdict reads differently without them."
                : VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT + " is the verdict without them, an additional figure, "
                        + "where it reads differently: " + String.join("; ", differing) + ".");
        limit(result, text.toString());
    }

    private static String namedRuns(Map<String, List<String>> perHost) {
        return String.join("; ", perHost.entrySet().stream().map(entry -> entry.getKey() + " "
                + entry.getValue().size() + " (" + String.join(", ", entry.getValue()) + ")").toList());
    }

    /** How a run's continuation generation ended ({@link #continuationExit(RunView)}). */
    enum ContinuationExit {
        /** No continuation generation connected and ended without a call. */
        NONE,
        /** A continuation generation connected and exited without a call, by its own exit. */
        EXITED,
        /**
         * A continuation generation connected and made no call, but the stub log shows no exit of its own: no
         * {@code worker_exited}, or one with the exit code of the driver's termination ({@link #TERMINATED_EXITS}).
         * Counted only for a run recorded as timed out.
         */
        UNCLEAR
    }

    /**
     * How a run's continuation generation ended: {@link ContinuationExit#EXITED} when its record says
     * {@value #CONTINUATION_EARLY_EXIT}, or when a generation of a worker after the generation in which that worker
     * first asked, and the newest generation of that worker, connected, made no call to the stub, had no fault
     * injected, and exited by itself ({@code worker_exited} with an exit code not in {@link #TERMINATED_EXITS});
     * {@link ContinuationExit#UNCLEAR} when such a generation shows no exit of its own and the run timed out;
     * {@link ContinuationExit#NONE} otherwise, and always for the negative resume of R2, which continues no task. The
     * second reading also finds the continuation exits of records written before the driver ended the wait on them.
     *
     * @param view a run
     * @return how its continuation generation ended
     */
    static ContinuationExit continuationExit(RunView view) {
        if (view.run().path(CONTINUATION_EARLY_EXIT).asBoolean()) {
            return ContinuationExit.EXITED;
        }
        if (view.run().path("negative").asBoolean()) {
            return ContinuationExit.NONE;
        }
        var firstAsk = new HashMap<String, Integer>();
        view.named(ASKED).forEach(event -> firstAsk.merge(event.path(WORKER).asText(),
                event.path(GENERATION).asInt(), Math::min));
        var latest = new HashMap<String, Integer>();
        view.named("worker_started").forEach(event -> latest.merge(event.path(WORKER).asText(),
                event.path(GENERATION).asInt(), Math::max));
        var unclear = false;
        for (var entry : latest.entrySet()) {
            var worker = entry.getKey();
            var generation = entry.getValue();
            var asked = firstAsk.get(worker);
            if (asked == null || generation <= asked) {
                continue;
            }
            var own = view.events().stream().filter(event -> same(event, worker, generation)).toList();
            var names = own.stream().map(event -> event.path(EVENT).asText()).collect(Collectors.toSet());
            if (!names.contains(QuestionDriver.CONNECTED) || names.contains(QuestionDriver.CALL)
                    || names.contains(QuestionDriver.FAULT_INJECTED)) {
                continue;
            }
            var exitedByItself = own.stream().filter(event -> "worker_exited".equals(event.path(EVENT).asText()))
                    .anyMatch(event -> !TERMINATED_EXITS.contains(event.path("exit").asInt(0)));
            if (exitedByItself) {
                return ContinuationExit.EXITED;
            }
            unclear = true;
        }
        return unclear && view.run().path(TIMED_OUT).asBoolean() ? ContinuationExit.UNCLEAR : ContinuationExit.NONE;
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
        var quotaErrors = false;
        var passWithoutQuota = true;
        var judgedWithoutQuota = false;
        var passWithoutContinuationExits = !groups.isEmpty();
        for (var entry : groups.entrySet()) {
            var group = evaluateGroup(item, entry.getValue());
            group.put("group", entry.getKey());
            array.add(group);
            pass &= !"fail".equals(group.path(VERDICT).asText());
            quotaErrors |= group.path(QUOTA_ERROR).asInt() > 0;
            var without = group.path(VERDICT_WITHOUT_QUOTA_ERROR).asText(group.path(VERDICT).asText());
            passWithoutQuota &= !"fail".equals(without);
            judgedWithoutQuota |= !"none".equals(without);
            passWithoutContinuationExits &= !"fail".equals(group.path(VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT)
                    .asText(group.path(VERDICT).asText()));
        }
        result.put(VERDICT, "R4".equals(item) ? "none" : pass ? "pass" : "fail");
        if (quotaErrors) {
            result.put(VERDICT_WITHOUT_QUOTA_ERROR, "R4".equals(item) || !judgedWithoutQuota ? "none"
                    : passWithoutQuota ? "pass" : "fail");
        }
        if (!"R4".equals(item) && passWithoutContinuationExits != pass) {
            result.put(VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT, passWithoutContinuationExits ? "pass" : "fail");
        }
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
        for (var field : List.of(HOST, MODEL, "form", "continuation")) {
            key.append(field).append('=').append(MODEL.equals(field) ? model(run) : run.path(field).asText())
                    .append(' ');
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
        for (var field : List.of(HOST, MODEL, "form", "continuation", "duration_s", "age_s", "fault", "size")) {
            if (MODEL.equals(field)) {
                group.put(MODEL, model(first));
            } else if (first.has(field)) {
                group.set(field, first.get(field));
            }
        }
        if ("R4".equals(item)) {
            group.set("fixture", first.get("fixture"));
        }
        counts(views, group);
        var pass = judge(item, views, group);
        group.put(VERDICT, "R4".equals(item) ? "none" : pass ? "pass" : "fail");
        var withoutQuota = new ArrayList<RunView>();
        for (var view : views) {
            if (!quotaError(view)) {
                withoutQuota.add(view);
            }
        }
        if (withoutQuota.size() < views.size()) {
            var scratch = QuestionStub.JSON.createObjectNode();
            var verdict = withoutQuota.isEmpty() || "R4".equals(item) ? "none"
                    : judge(item, withoutQuota, scratch) ? "pass" : "fail";
            group.put(VERDICT_WITHOUT_QUOTA_ERROR, verdict);
            group.put("runs_without_quota_error", withoutQuota.size());
            if (scratch.has(VERDICT_BY_NEXT_PM_WAIT)) {
                group.set(VERDICT_BY_NEXT_PM_WAIT + "_without_quota_error", scratch.get(VERDICT_BY_NEXT_PM_WAIT));
            }
        }
        var withoutContinuationExits = views.stream()
                .filter(view -> continuationExit(view) != ContinuationExit.EXITED).toList();
        if (withoutContinuationExits.size() < views.size() && !withoutContinuationExits.isEmpty()
                && !"R4".equals(item)) {
            var verdict = judge(item, withoutContinuationExits, QuestionStub.JSON.createObjectNode()) ? "pass" : "fail";
            if (!verdict.equals(group.path(VERDICT).asText())) {
                group.put(VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT, verdict);
            }
        }
        return group;
    }

    private static boolean judge(String item, List<RunView> views, ObjectNode group) {
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
            case "R4" -> r4(views, group);
            default -> throw new IllegalArgumentException("unknown item " + item);
        };
    }

    /**
     * Adds the counts every group reports beside its item's figures, so that no run leaves the result without a
     * trace: {@code runs}, {@code timed_out} (the run waited out its run limit), {@code no_ask} (no {@code asked}
     * event), {@code early_exit} ({@link #earlyExit(RunView)}), {@value #CONTINUATION_EARLY_EXIT}
     * ({@link #continuationExit(RunView)} is {@link ContinuationExit#EXITED}), {@value #QUOTA_ERROR}
     * ({@link #quotaError(RunView)}), {@code asked}, {@code continued} and {@code submitted} (runs with at least one
     * such event). A quota-error run counts in {@value #QUOTA_ERROR} only, not in {@code timed_out}, {@code no_ask},
     * {@code early_exit} or {@value #CONTINUATION_EARLY_EXIT}; a continuation early exit not in {@code timed_out}, even
     * where its record, written before the driver ended the wait on it, says timed out. They are reported figures: no
     * verdict reads them.
     *
     * @param views  the group's runs
     * @param group  the group
     * @throws IOException if a run's files cannot be read
     */
    private static void counts(List<RunView> views, ObjectNode group) throws IOException {
        var timedOut = 0;
        var noAsk = 0;
        var asked = 0;
        var earlyExits = 0;
        var continuationEarlyExits = 0;
        var quotaErrors = 0;
        var continued = 0;
        var submitted = 0;
        for (var view : views) {
            var askedHere = !view.named(ASKED).isEmpty();
            asked += askedHere ? 1 : 0;
            if (quotaError(view)) {
                quotaErrors++;
            } else {
                var continuationExited = continuationExit(view) == ContinuationExit.EXITED;
                continuationEarlyExits += continuationExited ? 1 : 0;
                timedOut += view.run().path(TIMED_OUT).asBoolean() && !continuationExited ? 1 : 0;
                noAsk += askedHere ? 0 : 1;
                earlyExits += earlyExit(view) ? 1 : 0;
            }
            continued += view.named("continued").isEmpty() ? 0 : 1;
            submitted += view.named(SUBMITTED).isEmpty() ? 0 : 1;
        }
        group.put(RUNS, views.size());
        group.put(TIMED_OUT, timedOut);
        group.put("no_ask", noAsk);
        group.put(EARLY_EXIT, earlyExits);
        group.put(CONTINUATION_EARLY_EXIT, continuationEarlyExits);
        group.put(QUOTA_ERROR, quotaErrors);
        group.put(ASKED, asked);
        group.put("continued", continued);
        group.put(SUBMITTED, submitted);
    }

    /**
     * Whether a run ended early: its record says so ({@value #EARLY_EXIT}), or a worker generation of it connected
     * and exited without a single call to the stub, was not ended by an injected fault, was not followed by a newer
     * generation of the same worker, and belongs to a worker that did not ask (the generation of a worker that asked
     * before is a continuation generation, read by {@link #continuationExit(RunView)}, as
     * {@link QuestionDriver#earlyExit(QuestionDriver.Worker)} marks it). The second reading also finds the early exits
     * of records written before the driver detected them, whose runs waited out their run limit.
     *
     * @param view a run
     * @return whether it ended early
     */
    static boolean earlyExit(RunView view) {
        if (view.run().path(EARLY_EXIT).asBoolean()) {
            return true;
        }
        var latest = new HashMap<String, Integer>();
        view.named("worker_started").forEach(event -> latest.merge(event.path(WORKER).asText(),
                event.path(GENERATION).asInt(), Math::max));
        for (var exited : view.named("worker_exited")) {
            var worker = exited.path(WORKER).asText();
            var generation = exited.path(GENERATION).asInt();
            if (latest.getOrDefault(worker, generation) > generation || view.named(ASKED).stream()
                    .anyMatch(asked -> worker.equals(asked.path(WORKER).asText()))) {
                continue;
            }
            var own = view.events().stream().filter(event -> same(event, worker, generation))
                    .map(event -> event.path(EVENT).asText()).collect(Collectors.toSet());
            if (own.contains(QuestionDriver.CONNECTED) && !own.contains(QuestionDriver.CALL)
                    && !own.contains(QuestionDriver.FAULT_INJECTED)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a run met a quota or limit error of its harness: its record says so ({@value #QUOTA_ERROR}), or a line of
     * its workers' own output or {@code stderr} matches {@link #QUOTA_PATTERN} ({@link #quotaDetail(String, List)}).
     * The second reading also finds the quota errors of records written before the driver detected them.
     *
     * @param view a run
     * @return whether its harness answered with a quota error
     * @throws IOException if a worker's {@code stderr} cannot be read
     */
    static boolean quotaError(RunView view) throws IOException {
        if (view.run().path(QUOTA_ERROR).asBoolean()) {
            return true;
        }
        var stderr = new StringBuilder();
        for (var worker : view.run().path("workers")) {
            var file = stderrOf(worker);
            if (file != null && Files.exists(file)) {
                stderr.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return quotaDetail(stderr.toString(), view.outputs()).isPresent();
    }

    /**
     * @param stderr  what a worker wrote to {@code stderr}
     * @param outputs its arrival-stamped output lines
     * @return the first line of {@code stderr} that is not a line of {@code pm-exec}'s own and matches
     *         {@link #QUOTA_PATTERN}, else the first such output line; empty when none
     */
    static Optional<String> quotaDetail(String stderr, List<JsonNode> outputs) {
        return stderr.lines().filter(line -> !QuestionDriver.pmExecLine(line))
                .filter(line -> QUOTA_PATTERN.matcher(line).find()).findFirst()
                .or(() -> outputs.stream().map(line -> line.path("line").asText())
                        .filter(line -> QUOTA_PATTERN.matcher(line).find()).findFirst());
    }

    /**
     * @param worker a worker of a run record
     * @return its {@code stderr} file: the recorded one, else the one beside its output
     *         ({@link QuestionDriver#stderrFile(Path, String, int)}); {@code null} when the record names neither
     */
    private static Path stderrOf(JsonNode worker) {
        if (worker.path("stderr").isTextual()) {
            return Path.of(worker.path("stderr").asText());
        }
        var output = worker.path("output").isTextual() ? Path.of(worker.path("output").asText()) : null;
        if (output == null || output.getParent() == null) {
            return null;
        }
        return QuestionDriver.stderrFile(output.getParent(), worker.path(WORKER).asText(),
                worker.path(GENERATION).asInt());
    }

    /**
     * @param run a run record
     * @return the id of the run: the name of the directory of its stub
     */
    static String runId(JsonNode run) {
        var stub = Path.of(run.path("stub_dir").asText());
        return stub.getParent() == null ? stub.toString() : stub.getParent().getFileName().toString();
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

    /**
     * Q3: the verdict reads, per run, the stop after the first ask (a warm worker's next {@code wait_started}, a fresh
     * worker's process exit) and an unchanged worktree. Beside it the group reports, per run and in total, the time to
     * the asking generation's next {@code pm_wait} ({@link #nextPmWait}), the time to its process exit where one is
     * observed, and {@value #VERDICT_BY_NEXT_PM_WAIT}, the verdict with the next {@code pm_wait} or an exit before the
     * answer read as the stop: additional figures, not the verdict.
     */
    private static boolean q3(List<RunView> views, ObjectNode group) {
        var stopped = 0;
        var unchanged = 0;
        var times = new ArrayList<Long>();
        var waits = new ArrayList<Long>();
        var exits = new ArrayList<Long>();
        var exitsBeforeAnswer = 0;
        var stoppedByWaitOrEnd = 0;
        var perRun = QuestionStub.JSON.createArrayNode();
        for (var view : views) {
            var asked = view.named(ASKED).stream().findFirst();
            var figures = perRun.addObject();
            figures.put("run", runId(view.run()));
            if (asked.isEmpty()) {
                figures.putNull(ASK_TO_NEXT_PM_WAIT_MS);
                figures.putNull(ASK_TO_EXIT_MS);
                continue;
            }
            var wait = nextPmWait(view, asked.get());
            wait.ifPresent(waits::add);
            putOrNull(figures, ASK_TO_NEXT_PM_WAIT_MS, wait.orElse(null));
            var exit = askToExit(view, asked.get());
            exit.ifPresent(exits::add);
            putOrNull(figures, ASK_TO_EXIT_MS, exit.orElse(null));
            var answerAt = view.named("answer_sent").stream().mapToLong(event -> event.path("at").asLong()).min();
            var beforeAnswer = exit.isPresent() && (answerAt.isEmpty()
                    || asked.get().path("at").asLong() + exit.get() < answerAt.getAsLong());
            figures.put("exit_before_answer", beforeAnswer);
            exitsBeforeAnswer += beforeAnswer ? 1 : 0;
            stoppedByWaitOrEnd += wait.isPresent() || beforeAnswer ? 1 : 0;
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
        group.put("ask_to_stop_event_ms_median", median(times));
        group.put("ask_to_stop_event_ms_max", max(times));
        group.put("asked_then_pm_wait", waits.size());
        group.put(ASK_TO_NEXT_PM_WAIT_MS + "_median", median(waits));
        group.put(ASK_TO_NEXT_PM_WAIT_MS + "_max", max(waits));
        group.put("exits_observed", exits.size());
        group.put("exits_before_answer", exitsBeforeAnswer);
        group.put(ASK_TO_EXIT_MS + "_median", median(exits));
        group.put(ASK_TO_EXIT_MS + "_max", max(exits));
        group.put("stopped_by_next_pm_wait_or_end", stoppedByWaitOrEnd);
        group.put(VERDICT_BY_NEXT_PM_WAIT, stoppedByWaitOrEnd == views.size() && unchanged == views.size() ? "pass"
                : "fail");
        group.set("per_run", perRun);
        return stopped == views.size() && unchanged == views.size();
    }

    /**
     * @param view  a run
     * @param asked its first ask
     * @return the milliseconds from the ask to the asking generation's first {@code pm_wait} at or after it (its
     *         {@code call} of {@code pm_wait} or its {@code wait_started}), empty when it called none
     */
    static Optional<Long> nextPmWait(RunView view, JsonNode asked) {
        var at = asked.path("at").asLong();
        var worker = asked.path(WORKER).asText();
        var generation = asked.path(GENERATION).asInt();
        return view.events().stream()
                .filter(event -> event.path("at").asLong() >= at && same(event, worker, generation))
                .filter(event -> "wait_started".equals(event.path(EVENT).asText())
                        || QuestionDriver.CALL.equals(event.path(EVENT).asText())
                        && "pm_wait".equals(event.path("tool").asText()))
                .map(event -> event.path("at").asLong() - at).findFirst();
    }

    private static Optional<Long> askToExit(RunView view, JsonNode asked) {
        var at = asked.path("at").asLong();
        var worker = asked.path(WORKER).asText();
        var generation = asked.path(GENERATION).asInt();
        return view.named("worker_exited").stream()
                .filter(event -> event.path("at").asLong() >= at && same(event, worker, generation))
                .map(event -> event.path("at").asLong() - at).findFirst();
    }

    private static void putOrNull(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static long max(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).max().orElse(-1);
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
     * failed nor hung, it worked on a conversation of its own (Antigravity, OpenCode:
     * {@link #startedNewSession(Host, String, String, boolean)}), however long that took. Otherwise a
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

    /**
     * Whether the resume of a missing session (R2) started a new session, the bookkeeping behind
     * {@link #NEGATIVE_NEW_SESSION}: the harness warned that the named session was not found (Antigravity), or it
     * reports a session other than the one named, or it is OpenCode and reports any session. OpenCode 2.1.2 creates a
     * session under a well-formed id that names none ({@code ses_} and 26 characters), runs the prompt and exits 0
     * (verified 2026-10-21), so the id it reports is the one named and still a new session.
     *
     * @param host     the host
     * @param named    the session id the generation was told to resume
     * @param reported the session id the generation reports, empty when none
     * @param warned   whether it warned that the named session was not found
     * @return whether it started a new session
     */
    static boolean startedNewSession(Host host, String named, String reported, boolean warned) {
        if (warned) {
            return true;
        }
        if (reported == null || reported.isEmpty()) {
            return false;
        }
        return !named.equals(reported) || host == Host.OPENCODE;
    }

    // --- limits ---

    /**
     * Appends one limit to a result's {@code limits}.
     *
     * @param result the result
     * @param text   the limit
     */
    static void limit(ObjectNode result, String text) {
        var limits = result.get(LIMITS) instanceof ArrayNode array ? array : result.putArray(LIMITS);
        limits.add(text);
    }

    /**
     * Adds the limit of the fixture wording to the result of an item whose fixtures force the ask (Q1 to Q7, R3): the
     * forcing sentences, the closed question introduced as the one to submit after the answer, the evidence rule of the
     * contract, all added before any measured run; for Q2 also that its controls stay unforced.
     *
     * @param result the result
     * @param item   the item
     */
    static void limitsOfFixtures(ObjectNode result, String item) {
        if (!FORCED_ITEMS.contains(item)) {
            return;
        }
        limit(result, "Asking is forced by the fixture text, added before any measured run after a smoke pass of Q1 "
                + "in which the workers rarely asked. The apply-answer task says: \"" + QuestionFixtures.FORCE_ASK
                + "\" Its envelope plan task TASK-001 says: \"" + QuestionFixtures.FORCE_ASK_PLAN_TASK + "\" The "
                + "two-flaw task of Q5 says: \"" + QuestionFixtures.FORCE_ASK_EACH + "\" Before the ask the facts no "
                + "longer claim that the operator has answered: the closed question begins \""
                + QuestionFixtures.CHECK_AFTER_ANSWER + "\" instead of the corpus's \""
                + QuestionFixtures.ANSWERED_FRAMING.strip() + "\"");
        limit(result, "The worker's contract states the evidence rule of the decision envelope, added before any "
                + "measured run: \"" + QuestionDriver.EVIDENCE_RULE + "\" Its example envelope carries the evidence "
                + "item " + QuestionDriver.EVIDENCE_EXAMPLE + ".");
        if ("Q2".equals(item)) {
            limit(result, "The controls of Q2 keep their original, unforced task text: no forcing sentence.");
        }
    }

    /**
     * Adds the limit of OpenCode on the operator's global data home.
     *
     * @param result the result
     * @param models the OpenCode models of the runs, the harness default as {@code null}
     */
    static void limitOfOpenCodeDataHome(ObjectNode result, Set<String> models) {
        var named = models.stream().map(model -> model == null ? "the harness default" : model).sorted().toList();
        limit(result, "OpenCode ran on the global data home with " + String.join(", ", named) + ", a departure from "
                + "the per-worker data home of the package, accepted by the operator. Its runs ran one at a time, "
                + "because every OpenCode worker writes the one SQLite database of that data home; their sessions "
                + "stay in the operator's history.");
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
        var follows = 0;
        for (var view : views) {
            tokens.add(tokens(view.outputs()));
            times.add(workerTime(view));
            if (!view.named(ASKED).isEmpty()) {
                follows += followsReference(view) ? 1 : 0;
            }
        }
        group.put("tokens_median", median(tokens));
        group.put("worker_time_ms_median", median(times));
        group.set("tokens", QuestionStub.JSON.valueToTree(tokens));
        group.set("worker_time_ms", QuestionStub.JSON.valueToTree(times));
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
        limit(result, "Small work is the probe; large work only the scenarios "
                + String.join(" and ", QuestionFixtures.LARGE_SCENARIOS) + ". The other " + unused + " scenario "
                + "recipes are not used: they need tasks replayed from records whose commits are gone, or outside "
                + "state that has changed.");
        limit(result, "The large scenarios ran " + largeRuns + " runs per host and form in the continuation forms "
                + "hold and resume only, instead of " + runs + " runs in every form. R4 has no pass criterion; its "
                + "figures are tokens and time, and the answer check of the large scenarios is secondary.");
        limit(result, "Asking is not forced in the large scenarios: a run in which the worker did not ask is counted "
                + "as no_ask, not as a pass.");
        limit(result, "Both corpus items behind the large scenarios are drafts.");
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
