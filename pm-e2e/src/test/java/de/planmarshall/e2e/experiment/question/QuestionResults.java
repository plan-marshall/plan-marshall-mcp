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
import java.nio.ByteBuffer;
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
import de.planmarshall.e2e.experiment.question.QuestionDriver.Fault;
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
 * continuation generation ({@value #CONTINUATION_EARLY_EXIT}), had a voter that ended early
 * ({@value #VOTER_EARLY_EXIT}), met a quota error of the harness, failed its fixture setup before any worker launched
 * ({@value #SETUP_FAILED}), asked, continued and submitted, and a Q4 group its
 * runs that continued on another host ({@value #CROSS_HOST_RUNS}), figures no verdict reads; it names the runs that
 * timed out ({@value #TIMED_OUT_RUNS}), counts the runs whose continuation got no worker
 * ({@value #CONTINUATION_NOT_STARTED}), and a group of Q4, Q6 or R4 names the runs that did not follow the reference
 * ({@value #NOT_FOLLOWING_REFERENCE_RUNS}), likewise figures no verdict reads; where a group has quota
 * errors, it also reports its verdict without them
 * ({@code verdict_without_quota_error}), and where its verdict without its continuation early exits reads differently,
 * that verdict ({@value #VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT}): additional figures that do not replace the
 * verdict. The limits come from every invocation it aggregates ({@link #result}).
 * <p>
 * A run whose stub log is gone ({@link #evidenceMissing(JsonNode)}) is counted as {@value #EVIDENCE_MISSING}, never as
 * a run that did not ask; its verdict is marked {@value #VERDICT_EVIDENCE}, and a result with such runs never replaces
 * an existing result file without {@value ResultTarget#ALLOW_MISSING_EVIDENCE}
 * ({@link #write(ResultTarget, String, ObjectNode)}). {@link ResultTarget} writes a result under a suffix of its own,
 * from the records of named invocations only.
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
     * The field of a run record, and the group column, of a run in which a voter (the worker of a vote task of Q6)
     * connected and exited without a single call to the stub ({@link #voterEarlyExit(RunView)}): the vote of its role
     * is missing. Such a voter counts neither as {@value #EARLY_EXIT} nor as {@value #CONTINUATION_EARLY_EXIT}, and its
     * run keeps its ask.
     */
    static final String VOTER_EARLY_EXIT = "voter_early_exit";
    /** The Q4 group figure of its runs whose continuing task ran on another host than the asker's. */
    static final String CROSS_HOST_RUNS = "cross_host_runs";
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
     * A harness's quota or limit error: the text {@code quota} in any case, matched only in the harness's own error
     * output ({@link #quotaDetail(String, List)}), never in a tool's input or result, assistant text or a file the
     * worker read. Antigravity answered {@code error: Individual quota reached. Please upgrade your subscription to
     * increase your limits. Resets in 42h26m24s.} on {@code stderr} and as the {@code error} of its result object in
     * the last three Antigravity runs of the Q3 smoke pass (2026-10-22). Lines of {@code pm-exec}'s own do not count.
     */
    static final Pattern QUOTA_PATTERN = Pattern.compile("quota", Pattern.CASE_INSENSITIVE);
    /**
     * The field of a run record that names the rule its {@value #QUOTA_ERROR} was detected by; a record without it was
     * written under the earlier rule, which matched {@link #QUOTA_PATTERN} in every output line, and is read again
     * ({@link #quotaError(RunView)}).
     */
    static final String QUOTA_RULE = "quota_rule";
    /** The rule of {@link #quotaDetail(String, List)}: the harness's own error output only. */
    static final String HARNESS_ERROR_OUTPUT = "harness_error_output";
    /**
     * The field of a run record, and the group column, of a run whose fixture or workspace setup failed before any
     * worker launched ({@link #setupFailed(JsonNode)}): it is counted in no other column and in no figure.
     */
    static final String SETUP_FAILED = "setup_failed";
    /** The field of a run record with the reason of its {@value #SETUP_FAILED}: the first line of git's output. */
    static final String SETUP_FAILED_REASON = "setup_failed_reason";
    /**
     * The field of an invocation record with the runs it did not start, because an earlier run of the same scenario
     * failed its setup ({@value #SETUP_FAILED}); they are no run record and not counted as missing.
     */
    static final String SETUP_STOPPED_RUNS = "setup_stopped_runs";
    /**
     * The group column, and the result's total, of the runs whose stub log is missing ({@link #evidenceMissing}): such
     * a run counts here only, never as {@code no_ask}, {@code timed_out} or in any other column; both appear only
     * where a run is so.
     */
    static final String EVIDENCE_MISSING = "evidence_missing";
    /**
     * The mark of a group's and of a result's verdict judged with runs whose stub log is missing: the verdict logic is
     * unchanged and reads them as runs without a single stub event; the mark says how many runs it read so.
     */
    static final String VERDICT_EVIDENCE = "verdict_evidence";
    /** An old run record's error of a failed git step of the setup: {@code <git command> failed: <git's output>}. */
    private static final Pattern GIT_SETUP_ERROR = Pattern.compile("^git .* failed: (.*)$", Pattern.DOTALL);
    /** An old run record's error of a missing local clone ({@link QuestionFixtures#checkout}). */
    private static final String NO_LOCAL_CLONE = "no local clone at ";
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
    /** The tools of the stub: a call of one of them is no read of a file of the worktree (R1). */
    static final Set<String> STUB_TOOLS = Set.of("pm_state", "pm_wait", "pm_do");
    /**
     * The suffix of the figures of R1 and R2 per form ({@code max_hold_seconds_per_form},
     * {@code max_age_seconds_per_form}): beside the pooled figure, which takes the longest passing duration or age of
     * either form, each form's own, so that a duration that passed fresh and failed warm reads as such.
     */
    static final String PER_FORM = "_per_form";
    /**
     * The R2 group figure that splits its runs by whether the stub was restarted while the record was open:
     * {@code true} and {@code false}, each with {@code runs}, {@code asked}, {@code continued_from_session} (as R2
     * counts it), {@code submitted} and {@code timed_out}. The group key has no restart field; the figure does not
     * change the verdict.
     */
    static final String RESTART_BREAKDOWN = "restart_breakdown";
    /** The limit of R2 when its invocations did not run the age of 24 hours. */
    static final String R2_NO_DAY_AGE = "the age 24 hours was not run, operator decision; the criterion text is "
            + "unchanged";

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
    private static final String QUESTION_ID_FIELD = "question_id";

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
     * @param evidenceMissing whether the run's stub log is gone ({@link #evidenceMissing(JsonNode)}): its events are
     *                then empty because nothing could be read, not because nothing happened
     */
    record RunView(JsonNode run, List<JsonNode> events, List<JsonNode> records, List<JsonNode> outputs,
            boolean evidenceMissing) {

        /**
         * A run whose stub log was read.
         *
         * @param run     the run record
         * @param events  the stub's events of the run
         * @param records the stub's question records of the run
         * @param outputs the worker outputs of the run
         */
        RunView(JsonNode run, List<JsonNode> events, List<JsonNode> records, List<JsonNode> outputs) {
            this(run, events, records, outputs, false);
        }

        List<JsonNode> named(String name) {
            return events.stream().filter(event -> name.equals(event.path(EVENT).asText())).toList();
        }

        Host host() {
            return Host.of(run.path(HOST).asText());
        }
    }

    /**
     * The lines of a file read while it may still be written ({@link #writtenLines(Path)}).
     *
     * @param complete the lines that end with a line break, without it
     * @param cutOff   whether the file ends in a line without a line break that is not blank: one still being written
     */
    record WrittenLines(List<String> complete, boolean cutOff) {
    }

    /**
     * The JSON lines of a file read while it may still be written ({@link #readJsonLinesSoFar(Path)}).
     *
     * @param nodes   the complete lines, parsed
     * @param skipped the final lines not read: none or one
     */
    record JsonLinesSoFar(List<JsonNode> nodes, int skipped) {
    }

    /**
     * Reads a file that a process or thread may be appending to at the same moment: its writer appends a line and then
     * its line break, but a line longer than a write's buffer reaches the file in parts, so a reader may see the start
     * of a line without its end. Only the lines up to the last line break are decoded (strictly as UTF-8, a cut may
     * split a character); what follows it is the line still being written and is reported as {@code cutOff}, not read.
     *
     * @param file a file
     * @return its complete lines and whether a cut-off line follows them; none when the file does not exist
     * @throws IOException if it cannot be read, or its complete lines are no UTF-8
     */
    static WrittenLines writtenLines(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new WrittenLines(List.of(), false);
        }
        var bytes = Files.readAllBytes(file);
        var end = bytes.length;
        while (end > 0 && bytes[end - 1] != '\n' && bytes[end - 1] != '\r') {
            end--;
        }
        var cutOff = false;
        for (var index = end; index < bytes.length && !cutOff; index++) {
            cutOff = bytes[index] != ' ' && bytes[index] != '\t';
        }
        var text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, end)).toString();
        return new WrittenLines(text.lines().toList(), cutOff);
    }

    /**
     * Reads a JSON Lines file while its writer may still append to it, as the driver does during a run (the worker's
     * output, the stub's event log): a final line still being written is skipped and counted, never an error. That is
     * a final line without its line break ({@link #writtenLines(Path)}), or a final line that is no JSON. A line that
     * is no JSON anywhere before it still fails, as in {@link #readJsonLines(Path)}, which reads a file written to the
     * end and stays strict, so that a corrupt completed file is still reported.
     *
     * @param file a JSON Lines file
     * @return its complete lines, parsed, and the number of final lines skipped; none when the file does not exist
     * @throws IOException if it cannot be read, or a line before the last is no JSON
     */
    static JsonLinesSoFar readJsonLinesSoFar(Path file) throws IOException {
        var written = writtenLines(file);
        var lines = written.complete().stream().filter(line -> !line.isBlank()).toList();
        var nodes = new ArrayList<JsonNode>();
        var skipped = written.cutOff() ? 1 : 0;
        for (var index = 0; index < lines.size(); index++) {
            try {
                nodes.add(QuestionStub.JSON.readTree(lines.get(index)));
            } catch (JsonProcessingException e) {
                if (written.cutOff() || index < lines.size() - 1) {
                    throw e;
                }
                skipped++;
            }
        }
        return new JsonLinesSoFar(List.copyOf(nodes), skipped);
    }

    /**
     * @param file a JSON Lines file, read while its writer may still append to it
     * @return its complete lines, parsed ({@link #readJsonLinesSoFar(Path)})
     * @throws IOException if it cannot be read, or a line before the last is no JSON
     */
    static List<JsonNode> jsonLinesSoFar(Path file) throws IOException {
        return readJsonLinesSoFar(file).nodes();
    }

    /**
     * Reads a JSON Lines file written to the end: every line must be JSON. A file still being written is read with
     * {@link #readJsonLinesSoFar(Path)} instead.
     *
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
     * @return the invocation records of the item, one per invocation: where an invocation appended its record again
     *         after its runs (with the setup failures it met, {@link QuestionLoopExperiment#invocation}), the last
     *         record, in the place of its first
     * @throws IOException if they cannot be read
     */
    static List<JsonNode> invocations(Path resultsDir, String item) throws IOException {
        var latest = new LinkedHashMap<String, JsonNode>();
        readJsonLines(resultsDir.resolve("invocations.jsonl")).stream()
                .filter(invocation -> item.equals(invocation.path("item").asText()))
                .forEach(invocation -> latest.put(invocation.path(INVOCATION).asText(), invocation));
        return List.copyOf(latest.values());
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
            var stopped = recorded.path(SETUP_STOPPED_RUNS).asInt(0);
            if (planned >= 0 && entry.getValue() + stopped < planned) {
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
        limitOfContinuationsByHold(result, runs);
        limitOfQuotaErrors(result, runs);
        limitOfSetupFailures(result, runs, contributing.values());
        limitOfMissingEvidence(result, runs);
        limitOfReidentifiedGenerations(result, runs);
        limitOfAnswerFallbacks(result, runs);
        limitOfContinuationGaps(result, runs);
        limitOfReferenceFormat(result, item, runs);
        switch (item) {
            case "Q3" -> limitOfQ3Times(result);
            case "Q4" -> limitOfQ4CrossHost(result, runs);
            case "Q5" -> limitOfQ5Bound(result);
            case "Q6" -> limitOfQ6Votes(result, runs);
            case "Q7" -> limitOfQ7(result, runs);
            case "R2" -> limitOfR2Ages(result, runs, contributing.values());
            case "R3" -> limitsOfR3(result, runs);
            default -> {
                // no limit of its own
            }
        }
        scale(item, result, runs, invocations);
        return result;
    }

    /**
     * Adds the limit of R2's ages ({@value #R2_NO_DAY_AGE}) when the age of 24 hours was not run: the ages its
     * contributing invocations recorded ({@code r2_ages_s}), or, where none recorded them, the ages of its runs, do not
     * include 86400 seconds.
     *
     * @param result       the result of R2
     * @param runs         its run records
     * @param contributing the invocations that contributed run records
     */
    static void limitOfR2Ages(ObjectNode result, List<JsonNode> runs, Collection<JsonNode> contributing) {
        var ages = new TreeSet<Long>();
        contributing.forEach(invocation -> invocation.path("r2_ages_s").forEach(age -> ages.add(age.asLong())));
        if (ages.isEmpty()) {
            runs.forEach(run -> ages.add(run.path("age_s").asLong()));
        }
        if (!ages.contains(Duration.ofHours(24).toSeconds())) {
            limit(result, R2_NO_DAY_AGE);
        }
    }

    /**
     * Adds the limit of Q4's continuation on another host: it is no group of its own but the figure
     * {@value #CROSS_HOST_RUNS} of each group, and at smoke scale it is mixed with the reply, both being set by the run
     * index; the limit names the replies of the cross-host and the same-host runs.
     *
     * @param result the result of Q4
     * @param runs   its run records
     */
    static void limitOfQ4CrossHost(ObjectNode result, List<JsonNode> runs) {
        var cross = new TreeMap<String, Integer>();
        var same = new TreeMap<String, Integer>();
        var hosts = new TreeSet<String>();
        for (var run : runs) {
            if (crossHost(run)) {
                cross.merge(run.path("reply").asText(), 1, Integer::sum);
                hosts.add(run.path(HOST).asText() + "->" + run.path("continue_host").asText());
            } else {
                same.merge(run.path("reply").asText(), 1, Integer::sum);
            }
        }
        if (cross.isEmpty()) {
            return;
        }
        limit(result, "The continuation on another host (pm.experiment.q4.other-host) is no group of its own: the "
                + "groups are keyed by host, model, form, continuation and reply, and " + CROSS_HOST_RUNS + " reports "
                + "a group's runs whose continuing task ran on another host than the asker's, with their asked, "
                + "continued and submitted counts. The plan continues on the other host at the odd run indices and "
                + "sets the reply by the run index modulo 3, so at smoke scale the other host and the reply are mixed: "
                + "the cross-host runs (" + String.join(", ", hosts) + ") had the replies " + counted(cross)
                + ", the same-host runs " + counted(same) + ".");
    }

    /**
     * Adds the limit of Q5's {@code bound_reached}: the planned flag of the run's form, not a measurement.
     *
     * @param result the result of Q5
     */
    static void limitOfQ5Bound(ObjectNode result) {
        limit(result, "bound_reached is the planned flag of the group's form (the bound form, whose replies only "
                + "restate the question's options so that the flaw stays open until the question bound), set when "
                + "the run was planned, not a measurement: no figure reads from the stub log whether a run reached the "
                + "bound (the ask link withdrawn).");
    }

    /**
     * Adds the limit of Q6's votes: how the stub decides a vote whose voter is missing, and the runs in which a voter
     * exited without a call ({@value #VOTER_EARLY_EXIT}).
     *
     * @param result the result of Q6
     * @param runs   its run records
     * @throws IOException if a run's files cannot be read
     */
    static void limitOfQ6Votes(ObjectNode result, List<JsonNode> runs) throws IOException {
        var affected = new ArrayList<String>();
        for (var run : runs) {
            if (voterEarlyExit(view(run))) {
                affected.add(runId(run));
            }
        }
        limit(result, "A vote task is served by one voter and never offered again: the stub has no re-offer budget "
                + "and the driver does not restart a voter. When a voter's process exits without a vote (among them a "
                + "voter that connected and exited without a single call, counted as " + VOTER_EARLY_EXIT + "), its "
                + "vote task closes lost at once, and the decision rule of the specification decides the set: a listed "
                + "role without a vote escalates it with the reason role_unavailable, never as agreement. The driver "
                + "then answers the decision as the operator, and the task continues as after an operator's answer; a "
                + "decided set is taken without waiting for an operator. Such a vote counts in votes and not in "
                + "valid_votes, and its run keeps its ask" + (affected.isEmpty() ? "." : ": " + String.join(", ",
                        affected) + "."));
    }

    /**
     * Adds a limit naming the runs in which a generation of a worker connected twice while an older generation of the
     * same worker still ran ({@link #reidentified(RunView)}): the older harness reached the stub with the newer
     * generation's identity, so the stub log attributes its calls to the newer generation.
     *
     * @param result the result
     * @param runs   its run records
     * @throws IOException if a run's files cannot be read
     */
    static void limitOfReidentifiedGenerations(ObjectNode result, List<JsonNode> runs) throws IOException {
        var affected = new TreeMap<String, List<String>>();
        for (var run : runs) {
            if (reidentified(view(run))) {
                affected.computeIfAbsent(run.path(HOST).asText(), _ -> new ArrayList<>()).add(runId(run));
            }
        }
        if (affected.isEmpty()) {
            return;
        }
        limit(result, "In these runs a worker generation connected twice while an older generation of the same worker "
                + "still ran. That is the signature of a defect of the driver established for "
                + "Q4-claude-fresh-question_apply-answer_he-0-001-d799cea0 from OpenCode's session ids and the relay "
                + "log: the older OpenCode process, fenced but not ended, restarted its MCP server when the launch of "
                + "the newer generation rewrote the shared worker directory's opencode.json, and reached the stub with "
                + "the newer generation's identity, so the stub log attributes the older generation's calls to the "
                + "newer one; the other runs are read by the same signature. Their figures of the newer generation "
                + "(continued, submitted, continuation_early_exit, timed_out) may count the older generation's calls; "
                + "the records are kept as written. The driver now ends the older generations of a worker before it "
                + "launches a newer one: " + namedRuns(affected) + ".");
    }

    /**
     * Whether a generation of a worker connected more than once while an older generation of the same worker still ran
     * (its {@code worker_exited} after the newer generation's first {@code connected}, or none).
     *
     * @param view a run
     * @return whether a generation of it was re-identified
     */
    static boolean reidentified(RunView view) {
        var connections = new HashMap<String, List<Long>>();
        view.named(QuestionDriver.CONNECTED).forEach(event -> connections.computeIfAbsent(event.path(WORKER).asText()
                + "#" + event.path(GENERATION).asInt(), _ -> new ArrayList<>()).add(event.path("at").asLong()));
        for (var entry : connections.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            var worker = entry.getKey().substring(0, entry.getKey().lastIndexOf('#'));
            var generation = Integer.parseInt(entry.getKey().substring(entry.getKey().lastIndexOf('#') + 1));
            var first = entry.getValue().stream().mapToLong(Long::longValue).min().orElseThrow();
            var olderRunning = view.named("worker_started").stream()
                    .filter(event -> worker.equals(event.path(WORKER).asText())
                            && event.path(GENERATION).asInt() < generation)
                    .anyMatch(started -> view.named("worker_exited").stream()
                            .filter(exited -> same(exited, worker, started.path(GENERATION).asInt()))
                            .noneMatch(exited -> exited.path("at").asLong() < first));
            if (olderRunning) {
                return true;
            }
        }
        return false;
    }

    private static boolean crossHost(JsonNode run) {
        var other = run.path("continue_host").asText("");
        return !other.isEmpty() && !other.equals(run.path(HOST).asText());
    }

    private static String counted(Map<String, Integer> counts) {
        return counts.isEmpty() ? "none" : String.join(", ", counts.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue()).toList());
    }

    /**
     * Adds Q4's {@value #CROSS_HOST_RUNS} to a group: its runs whose continuing task ran on another host than the
     * asker's, with the hosts they continued on and their asked, continued and submitted counts; a reported figure,
     * no verdict reads it.
     *
     * @param views the group's runs
     * @param group the group
     */
    static void crossHostRuns(List<RunView> views, ObjectNode group) {
        var cross = views.stream().filter(view -> crossHost(view.run())).toList();
        var node = group.putObject(CROSS_HOST_RUNS);
        node.put(RUNS, cross.size());
        var hosts = node.putArray("continue_hosts");
        cross.stream().map(view -> view.run().path("continue_host").asText()).distinct().sorted().forEach(hosts::add);
        node.put(ASKED, cross.stream().filter(view -> !view.named(ASKED).isEmpty()).count());
        node.put("continued", cross.stream().filter(view -> !view.named("continued").isEmpty()).count());
        node.put(SUBMITTED, cross.stream().filter(view -> !view.named(SUBMITTED).isEmpty()).count());
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
        var reclassified = new ArrayList<String>();
        for (var run : runs) {
            var view = view(run);
            if (quotaError(view)) {
                affected.add(runId(run));
            } else if (quotaReclassified(view)) {
                reclassified.add(runId(run));
            }
        }
        if (!affected.isEmpty()) {
            limit(result, "Runs whose harness answered with a quota or limit error (the text 'quota' in the "
                    + "harness's own error output: its stderr without pm-exec's lines, and the error lines of its "
                    + "structured output, never a tool's input or result, assistant text or a file the worker read) "
                    + "count as quota_error, not as timed_out, early_exit or no_ask. Their records were written "
                    + "before the error ended the wait at once, as timed_out where the worker had called the stub, as "
                    + "early_exit where it had not. The verdict reads them as before; verdict_without_quota_error is "
                    + "the verdict without them, an additional figure: " + String.join(", ", affected) + ".");
        }
        if (!reclassified.isEmpty()) {
            limit(result, "The records of these runs say quota_error, detected by the earlier rule, which matched "
                    + "'quota' in every output line, the results of the worker's own tool calls among them; the "
                    + "harness's own error output of their workers shows no quota error, so they are read as the runs "
                    + "they were, not as quota_error. The driver had ended them at once on that match: "
                    + String.join(", ", reclassified) + ".");
        }
    }

    /**
     * Adds a limit naming the runs whose fixture or workspace setup failed before any worker launched
     * ({@link #setupFailed(JsonNode)}), with their reasons, and the runs their invocations did not start because of
     * it ({@value #SETUP_STOPPED_RUNS}): such a run counts as {@value #SETUP_FAILED} only, never as no_ask,
     * timed_out or launched, and is in no figure of its group.
     *
     * @param result       the result
     * @param runs         its run records
     * @param contributing the invocations that contributed run records
     */
    static void limitOfSetupFailures(ObjectNode result, List<JsonNode> runs, Collection<JsonNode> contributing) {
        var failed = new ArrayList<String>();
        for (var run : runs) {
            if (setupFailed(run)) {
                failed.add(runId(run) + " (" + run.path("fixture").asText() + ": " + setupFailedReason(run) + ")");
            }
        }
        var stopped = new ArrayList<String>();
        for (var invocation : contributing) {
            var count = invocation.path(SETUP_STOPPED_RUNS).asInt(0);
            if (count > 0) {
                stopped.add(invocation.path(INVOCATION).asText() + " " + count);
            }
        }
        if (failed.isEmpty() && stopped.isEmpty()) {
            return;
        }
        var text = new StringBuilder("A run whose fixture or workspace setup failed before any worker launched counts "
                + "as " + SETUP_FAILED + " only, not as no_ask, timed_out or launched, and is in no figure of its "
                + "group; its reason is the first line of git's output");
        text.append(failed.isEmpty() ? "." : ": " + String.join("; ", failed) + ".");
        text.append(" A failed setup stops the invocation's remaining runs of that scenario");
        text.append(stopped.isEmpty() ? "." : ", not run and not counted as missing: " + String.join(", ", stopped)
                + ".");
        limit(result, text.toString());
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

    /**
     * Adds a limit naming the runs whose continuation was made by hold within the decision
     * ({@value QuestionDriver#CONTINUED_BY_HOLD} in the run record, {@link QuestionDriver#continuedByHold(String)}):
     * the asking worker took the continuing task over its held {@code pm_wait} and closed it before the driver would
     * have started a generation for it, so none was started. The limit states how such a run is counted; the verdict
     * logic is unchanged.
     *
     * @param result the result
     * @param runs   its run records
     */
    static void limitOfContinuationsByHold(ObjectNode result, List<JsonNode> runs) {
        var affected = new TreeMap<String, List<String>>();
        for (var run : runs) {
            if (run.path(QuestionDriver.CONTINUED_BY_HOLD).asBoolean()) {
                affected.computeIfAbsent(run.path(HOST).asText(), _ -> new ArrayList<>()).add(runId(run));
            }
        }
        if (affected.isEmpty()) {
            return;
        }
        limit(result, "In these runs the decision was answered while the asking worker still held pm_wait (in a vote "
                + "run the quorum decided before the asker ended its turn), and the stub offered the continuing task "
                + "to that generation within the hold; it took the task and closed it before the driver took the "
                + "answer. The driver started no new generation for it and recorded it as " + QuestionDriver.CONTINUED_BY_HOLD
                + ", with the generation and the times of the close and the submission. Such a run counts as a "
                + "continuation by hold: in continued and, with its submission, in submitted, from the stub's events "
                + "as every run; it ends when its entry is done, a hold still kept being ended with wait: end, and "
                + "not at its run limit. The verdict logic is unchanged and reads it as any other run: "
                + namedRuns(affected) + ".");
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
        var missing = 0;
        var judged = 0;
        for (var entry : groups.entrySet()) {
            var group = evaluateGroup(item, entry.getValue());
            group.put("group", entry.getKey());
            array.add(group);
            missing += group.path(EVIDENCE_MISSING).asInt(0);
            judged += group.path(RUNS).asInt() - group.path(SETUP_FAILED).asInt();
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
        if (missing > 0) {
            result.put(EVIDENCE_MISSING, missing);
            result.put(VERDICT_EVIDENCE, missingEvidenceMark(missing, judged));
        }
        if ("R1".equals(item) || "R2".equals(item)) {
            var name = "R1".equals(item) ? "max_hold_seconds" : "max_age_seconds";
            var parameter = "R1".equals(item) ? "duration_s" : "age_s";
            result.set(name, longestPassing(array, parameter));
            result.set(name + PER_FORM, longestPassingPerForm(array, parameter));
        }
        return result;
    }

    /**
     * @param missing the runs whose stub log is missing
     * @param judged  the runs the verdict read
     * @return the mark of a verdict judged with them ({@value #VERDICT_EVIDENCE})
     */
    static String missingEvidenceMark(int missing, int judged) {
        return "evidence missing for " + missing + " of " + judged + " runs: the verdict reads them as runs without a "
                + "single stub event";
    }

    /**
     * Adds a limit naming the runs whose stub log is missing ({@link #evidenceMissing(JsonNode)}), per host, and how
     * they are counted and read: {@value #EVIDENCE_MISSING} only, never {@code no_ask}; the verdict logic unchanged.
     *
     * @param result the result
     * @param runs   its run records
     */
    static void limitOfMissingEvidence(ObjectNode result, List<JsonNode> runs) {
        var missing = new TreeMap<String, List<String>>();
        for (var run : runs) {
            if (evidenceMissing(run)) {
                missing.computeIfAbsent(run.path(HOST).asText(), _ -> new ArrayList<>()).add(runId(run));
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        limit(result, "The stub log of these runs is missing: the stub directory their record names, or its "
                + "events.jsonl, no longer exists (the OS temp directory that held the stub logs before 2026-10-25 was "
                + "wiped by a reboot on 2026-10-24). They count as " + EVIDENCE_MISSING + " only, not as no_ask, "
                + "timed_out, early_exit, quota_error or in any other column, and their figures are not measured. The "
                + "verdict logic is unchanged and reads them as runs without a single stub event: a criterion every "
                + "run must meet, or a share over the runs, counts them against the group; Q1 takes its share over "
                + "the asks of the stub log and reads only the runs with evidence. " + VERDICT_EVIDENCE + " marks each "
                + "verdict judged so: " + namedRuns(missing) + ".");
    }

    /**
     * Where a result is written and which run records it aggregates, from the system properties:
     * {@value #SUFFIX} writes {@code <item>-<suffix>.json} instead of {@code <item>.json}, so a new measurement can be
     * written beside an intact result; {@value #INVOCATIONS} ({@code <id>,<id>,...}, only with a suffix) aggregates
     * only the run and invocation records of the named invocations, all of them by default; and
     * {@value #ALLOW_MISSING_EVIDENCE} lets a result with missing evidence replace an existing file
     * ({@link QuestionResults#write(ResultTarget, String, ObjectNode)}).
     *
     * @param directory            the results directory ({@code target/verification-results/question-loop})
     * @param suffix               the suffix of the file name, {@code null} for none
     * @param invocations          the invocations to aggregate, empty for all
     * @param allowMissingEvidence whether a result with missing evidence may replace an existing file
     */
    record ResultTarget(Path directory, String suffix, Set<String> invocations, boolean allowMissingEvidence) {

        /** The property of the suffix of the result file. */
        static final String SUFFIX = "pm.experiment.result.suffix";
        /** The property of the invocations a suffixed result aggregates. */
        static final String INVOCATIONS = "pm.experiment.result.invocations";
        /** The property that lets a result with missing evidence replace an existing file. */
        static final String ALLOW_MISSING_EVIDENCE = "pm.experiment.result.allow-missing-evidence";
        /** The results directory of the items. */
        static final Path DIRECTORY = Path.of("target", "verification-results", "question-loop");

        private static final Pattern SUFFIX_FORM = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

        ResultTarget {
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
        static ResultTarget fromProperties() {
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
        Path file(String item) {
            return directory.resolve(suffix == null ? item + ".json" : item + "-" + suffix + ".json");
        }

        /**
         * @param records run or invocation records of an item
         * @return those of the named invocations, all without a name
         * @throws IllegalArgumentException if a named invocation has no record among them
         */
        List<JsonNode> select(List<JsonNode> records) {
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
     * The refusal to replace an existing result file with a result whose evidence is missing
     * ({@value #EVIDENCE_MISSING}) without {@value ResultTarget#ALLOW_MISSING_EVIDENCE}.
     */
    static final class MissingEvidenceRefusal extends IOException {

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
    static String render(ObjectNode result) throws IOException {
        return QuestionStub.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n";
    }

    /**
     * Writes an item's result to {@code target/verification-results/question-loop/<item>.json}, with the suffix and
     * the guard of the system properties ({@link ResultTarget#fromProperties()}).
     *
     * @param item   the item
     * @param result its result
     * @return the file written
     * @throws IOException if it cannot be written, or the guard refuses it ({@link MissingEvidenceRefusal})
     */
    static Path write(String item, ObjectNode result) throws IOException {
        return write(ResultTarget.fromProperties(), item, result);
    }

    /**
     * Writes an item's result to its target's file. A result with runs whose stub log is missing
     * ({@value #EVIDENCE_MISSING} above 0) never replaces an existing file unless the target allows it: the existing
     * file was written from that evidence, and its loss must not rewrite it. A new file, or one allowed, is written
     * with the {@value #EVIDENCE_MISSING} counts and the limit that names the runs ({@link #limitOfMissingEvidence}).
     *
     * @param target where the result goes
     * @param item   the item
     * @param result its result
     * @return the file written
     * @throws IOException if it cannot be written, or the guard refuses it ({@link MissingEvidenceRefusal})
     */
    static Path write(ResultTarget target, String item, ObjectNode result) throws IOException {
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
        if ("Q4".equals(item)) {
            crossHostRuns(views, group);
        }
        if ("R2".equals(item)) {
            restartBreakdown(views, group);
        }
        if ("R3".equals(item)) {
            exitObservations(views, group);
            if (Fault.CALL_CUT.name().equals(first.path("fault").asText())) {
                cutSurvival(views, group);
            }
        }
        var withSetup = views.stream().filter(view -> !setupFailed(view.run())).toList();
        if (withSetup.isEmpty() && "R4".equals(item)) {
            group.put(VERDICT, "none");
            return group;
        }
        var launched = withSetup.isEmpty() ? views : withSetup;
        notFollowingReference(item, launched, group);
        var pass = judge(item, launched, group);
        group.put(VERDICT, "R4".equals(item) ? "none" : pass ? "pass" : "fail");
        var missing = group.path(EVIDENCE_MISSING).asInt(0);
        if (missing > 0) {
            group.put(VERDICT_EVIDENCE, missingEvidenceMark(missing, launched.size()));
        }
        var withoutQuota = new ArrayList<RunView>();
        for (var view : launched) {
            if (!quotaError(view)) {
                withoutQuota.add(view);
            }
        }
        if (withoutQuota.size() < launched.size()) {
            var scratch = QuestionStub.JSON.createObjectNode();
            var verdict = withoutQuota.isEmpty() || "R4".equals(item) ? "none"
                    : judge(item, withoutQuota, scratch) ? "pass" : "fail";
            group.put(VERDICT_WITHOUT_QUOTA_ERROR, verdict);
            group.put("runs_without_quota_error", withoutQuota.size());
            if (scratch.has(VERDICT_BY_NEXT_PM_WAIT)) {
                group.set(VERDICT_BY_NEXT_PM_WAIT + "_without_quota_error", scratch.get(VERDICT_BY_NEXT_PM_WAIT));
            }
        }
        var withoutContinuationExits = launched.stream()
                .filter(view -> continuationExit(view) != ContinuationExit.EXITED).toList();
        if (withoutContinuationExits.size() < launched.size() && !withoutContinuationExits.isEmpty()
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
     * ({@link #continuationExit(RunView)} is {@link ContinuationExit#EXITED}), {@value #VOTER_EARLY_EXIT}
     * ({@link #voterEarlyExit(RunView)}), {@value #QUOTA_ERROR}
     * ({@link #quotaError(RunView)}), {@value #SETUP_FAILED} ({@link #setupFailed(JsonNode)}), {@code asked},
     * {@code continued} and {@code submitted} (runs with at least one such event), and {@value #ANSWER_FALLBACK_OWN}
     * (runs whose record says an operator answer fell back to the operator's own option), {@value #TIMED_OUT_RUNS}
     * (the ids of the runs counted in {@code timed_out}) and {@value #CONTINUATION_NOT_STARTED}
     * ({@link #continuationNotStarted(RunView)}). A quota-error run counts in
     * {@value #QUOTA_ERROR} only, not in {@code timed_out}, {@code no_ask}, {@code early_exit} or
     * {@value #CONTINUATION_EARLY_EXIT}; a continuation early exit not in {@code timed_out}, even where its record,
     * written before the driver ended the wait on it, says timed out; a run whose setup failed in
     * {@value #SETUP_FAILED} only, since no worker of it launched; a run whose stub log is missing
     * ({@link RunView#evidenceMissing()}) in {@value #EVIDENCE_MISSING} only, a column present only where such a run
     * is, never in {@code no_ask} or {@code timed_out}: its empty events are no evidence that it did not ask (its
     * record's {@value #ANSWER_FALLBACK_OWN} still counts). They are reported figures: no verdict reads them.
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
        var voterEarlyExits = 0;
        var quotaErrors = 0;
        var setupFailures = 0;
        var continued = 0;
        var submitted = 0;
        var fallbacks = 0;
        var notStarted = 0;
        var evidenceMissing = 0;
        var timedOutRuns = QuestionStub.JSON.createArrayNode();
        for (var view : views) {
            if (setupFailed(view.run())) {
                setupFailures++;
                continue;
            }
            fallbacks += view.run().path(ANSWER_FALLBACK_OWN).asBoolean() ? 1 : 0;
            var askedHere = !view.named(ASKED).isEmpty();
            asked += askedHere ? 1 : 0;
            if (view.evidenceMissing()) {
                evidenceMissing++;
                continue;
            }
            if (quotaError(view)) {
                quotaErrors++;
            } else {
                var continuationExited = continuationExit(view) == ContinuationExit.EXITED;
                continuationEarlyExits += continuationExited ? 1 : 0;
                var timedOutHere = view.run().path(TIMED_OUT).asBoolean() && !continuationExited;
                timedOut += timedOutHere ? 1 : 0;
                if (timedOutHere) {
                    timedOutRuns.add(runId(view.run()));
                }
                notStarted += continuationNotStarted(view) ? 1 : 0;
                noAsk += askedHere ? 0 : 1;
                earlyExits += earlyExit(view) ? 1 : 0;
                voterEarlyExits += voterEarlyExit(view) ? 1 : 0;
            }
            continued += view.named("continued").isEmpty() ? 0 : 1;
            submitted += view.named(SUBMITTED).isEmpty() ? 0 : 1;
        }
        group.put(RUNS, views.size());
        group.put(TIMED_OUT, timedOut);
        group.put("no_ask", noAsk);
        group.put(EARLY_EXIT, earlyExits);
        group.put(CONTINUATION_EARLY_EXIT, continuationEarlyExits);
        group.put(VOTER_EARLY_EXIT, voterEarlyExits);
        group.put(QUOTA_ERROR, quotaErrors);
        group.put(SETUP_FAILED, setupFailures);
        group.put(ASKED, asked);
        group.put("continued", continued);
        group.put(SUBMITTED, submitted);
        group.put(ANSWER_FALLBACK_OWN, fallbacks);
        group.put(CONTINUATION_NOT_STARTED, notStarted);
        group.set(TIMED_OUT_RUNS, timedOutRuns);
        if (evidenceMissing > 0) {
            group.put(EVIDENCE_MISSING, evidenceMissing);
        }
    }

    /**
     * The group figure of the ids of its runs counted in {@code timed_out} ({@link #counts}): which run timed out, a
     * reported figure, no verdict reads it.
     */
    static final String TIMED_OUT_RUNS = "timed_out_runs";
    /**
     * The group figure of the ids of its runs whose last submission does not follow the reference, as the verdict of
     * Q4 and Q6 reads it ({@link #followsReference(RunView)}; in R4 among its runs that asked): which run missed, a
     * reported figure, no verdict reads it.
     */
    static final String NOT_FOLLOWING_REFERENCE_RUNS = "not_following_reference_runs";
    /**
     * The group figure of its runs whose continuation got no worker ({@link #continuationNotStarted(RunView)}): a
     * reported figure, no verdict reads it.
     */
    static final String CONTINUATION_NOT_STARTED = "continuation_not_started";

    /**
     * Whether a run's continuation got no worker: its record names the reason
     * ({@value QuestionDriver#CONTINUATION_GAP}), or the run timed out and its stub log shows a {@code continued} event
     * after which no generation was started ({@code worker_started}) and the continuing task was never offered. The
     * second reading finds the runs of the driver before 2026-10-25, which started no generation for a warm asker that
     * had exited instead of calling {@code pm_wait} (run {@code Q4-claude-warm-question_apply-answer_he-0-000-2ae81f04}),
     * among records whose stub log still exists.
     *
     * @param view a run
     * @return whether its continuation got no worker
     */
    static boolean continuationNotStarted(RunView view) {
        if (view.run().path(QuestionDriver.CONTINUATION_GAP).isTextual()) {
            return true;
        }
        if (!view.run().path(TIMED_OUT).asBoolean()) {
            return false;
        }
        for (var continued : view.named("continued")) {
            var task = continued.path(TASK).asText();
            var at = continued.path("at").asLong();
            var served = view.events().stream().filter(event -> event.path("at").asLong() >= at)
                    .anyMatch(event -> "worker_started".equals(event.path(EVENT).asText())
                            || OFFERED.equals(event.path(EVENT).asText()) && task.equals(event.path(TASK).asText()));
            if (!served) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a limit naming the runs whose continuation got no worker ({@link #continuationNotStarted(RunView)}), and the
     * runs whose warm asker exited after its ask instead of calling {@code pm_wait}
     * ({@value QuestionDriver#WARM_ASKER_EXITED}), whose continuation the driver started in a new generation. A run
     * without a worker for its continuation waited out its run limit and counts as timed_out as before; the verdict
     * logic is unchanged.
     *
     * @param result the result
     * @param runs   its run records
     * @throws IOException if a run's files cannot be read
     */
    static void limitOfContinuationGaps(ObjectNode result, List<JsonNode> runs) throws IOException {
        var gaps = new TreeMap<String, List<String>>();
        var exited = new TreeMap<String, List<String>>();
        for (var run : runs) {
            if (continuationNotStarted(view(run))) {
                gaps.computeIfAbsent(run.path(HOST).asText(), _ -> new ArrayList<>()).add(runId(run) + " ("
                        + run.path(QuestionDriver.CONTINUATION_GAP).asText("no generation after continued") + ")");
            }
            if (run.path(QuestionDriver.WARM_ASKER_EXITED).asBoolean()) {
                exited.computeIfAbsent(run.path(HOST).asText(), _ -> new ArrayList<>()).add(runId(run));
            }
        }
        if (gaps.isEmpty() && exited.isEmpty()) {
            return;
        }
        var text = new StringBuilder("A warm asker is expected to take its continuation over pm_wait. Before "
                + "2026-10-25 the driver started no generation for the continuation of a warm asker that had exited "
                + "after its ask instead of calling pm_wait, and waited for that call up to the host's silence limit "
                + "before it answered; the stub continued the task, nobody took it, and the run waited out its run "
                + "limit as timed_out. The driver now ends that wait at the exit and starts a generation leased on the "
                + "queued continuation (" + QuestionDriver.WARM_ASKER_EXITED + "), and records a continuation that "
                + "gets no worker with its reason (" + QuestionDriver.CONTINUATION_GAP + "). The verdict logic is "
                + "unchanged and reads these runs as before.");
        if (!gaps.isEmpty()) {
            text.append(" Continuation without a worker: ").append(namedRuns(gaps)).append('.');
        }
        if (!exited.isEmpty()) {
            text.append(" Warm asker exited, continuation started in a new generation: ").append(namedRuns(exited))
                    .append('.');
        }
        limit(result, text.toString());
    }

    /**
     * Adds the ids of a group's runs whose last submission does not follow the reference
     * ({@value #NOT_FOLLOWING_REFERENCE_RUNS}) for the items whose verdict or figures read it: Q4 and Q6 over all
     * runs, R4 over its runs that asked. A reported figure; the verdict logic is unchanged.
     *
     * @param item  the item
     * @param views the group's runs
     * @param group the group
     */
    static void notFollowingReference(String item, List<RunView> views, ObjectNode group) {
        if (!Set.of("Q4", "Q6", "R4").contains(item)) {
            return;
        }
        var ids = group.putArray(NOT_FOLLOWING_REFERENCE_RUNS);
        for (var view : views) {
            if ("R4".equals(item) && view.named(ASKED).isEmpty()) {
                continue;
            }
            if (!followsReference(view)) {
                ids.add(runId(view.run()));
            }
        }
    }

    /**
     * Whether a run's last submission states the reference within a longer text: the submitted answer, read as
     * {@link #followsReference(RunView)} reads it, is not the reference but contains it (OpenCode's run
     * {@code Q4-opencode-fresh-question_apply-answer_he-106-002-a79333b6} submitted
     * {@code f(7) == [1, 2, 6, 24, 15, 720, 28]} for the reference {@code [1, 2, 6, 24, 15, 720, 28]}).
     *
     * @param view a run
     * @return whether its submission wraps the reference
     */
    static boolean referenceWrapped(RunView view) {
        var reference = normalize(view.run().path("reference").asText());
        var answer = normalize(lastSubmitArgs(view).path("answer").asText());
        return !reference.isEmpty() && !reference.equals(answer) && answer.contains(reference);
    }

    /**
     * Adds a limit naming the runs of Q4, Q6 and R4 that count as not following the reference only by the form of
     * their submission ({@link #referenceWrapped(RunView)}): the check compares the whole submitted answer with the
     * reference after trimming, lower case, outer quotes and runs of white space, so a submission that states the
     * reference within an expression counts as a miss. The check is not changed.
     *
     * @param result the result
     * @param item   the item
     * @param runs   its run records
     * @throws IOException if a run's files cannot be read
     */
    static void limitOfReferenceFormat(ObjectNode result, String item, List<JsonNode> runs) throws IOException {
        if (!Set.of("Q4", "Q6", "R4").contains(item)) {
            return;
        }
        var wrapped = new TreeMap<String, List<String>>();
        for (var run : runs) {
            if (referenceWrapped(view(run))) {
                wrapped.computeIfAbsent(run.path(HOST).asText(), _ -> new ArrayList<>()).add(runId(run));
            }
        }
        if (wrapped.isEmpty()) {
            return;
        }
        limit(result, "Whether a run follows the reference is read from its last submission: its answer must equal "
                + "the reference after trimming, lower case, outer quotes and runs of white space, so an answer that "
                + "states the reference within a longer text (an expression such as f(7) == [...]) counts as not "
                + "following, by its form alone. The check is unchanged; these runs count as not following although "
                + "their answer contains the reference: " + namedRuns(wrapped) + ".");
    }

    /**
     * Whether a run ended early: its record says so ({@value #EARLY_EXIT}), or a worker generation of it connected
     * and exited without a single call to the stub, was not ended by an injected fault, was not followed by a newer
     * generation of the same worker, and belongs to a worker that did not ask (the generation of a worker that asked
     * before is a continuation generation, read by {@link #continuationExit(RunView)}, as
     * {@link QuestionDriver#earlyExit(QuestionDriver.Worker)} marks it), and is no voter (a voter that exits so is a
     * {@value #VOTER_EARLY_EXIT}, {@link #voterEarlyExit(RunView)}). The second reading also finds the early exits of
     * records written before the driver detected them, whose runs waited out their run limit.
     *
     * @param view a run
     * @return whether it ended early
     */
    static boolean earlyExit(RunView view) {
        if (view.run().path(EARLY_EXIT).asBoolean()) {
            return true;
        }
        return silentExits(view).stream().anyMatch(worker -> !QuestionDriver.voter(worker));
    }

    /**
     * Whether a voter of the run (the worker of a vote task, {@link QuestionDriver#voter(String)}) connected and exited
     * without a single call to the stub: its record says so ({@value #VOTER_EARLY_EXIT}), or its stub log shows it,
     * read as {@link #earlyExit(RunView)} reads a worker. A reported figure, no verdict reads it; its vote task closes
     * {@code lost} and counts among the votes, not among the valid ones.
     *
     * @param view a run
     * @return whether a voter of it ended early
     */
    static boolean voterEarlyExit(RunView view) {
        if (view.run().path(VOTER_EARLY_EXIT).asBoolean()) {
            return true;
        }
        return silentExits(view).stream().anyMatch(QuestionDriver::voter);
    }

    /**
     * @return the workers of a run with a generation that connected and exited without a call to the stub, was not
     *         ended by an injected fault, was not followed by a newer generation of the same worker, and belongs to a
     *         worker that did not ask
     */
    private static List<String> silentExits(RunView view) {
        var latest = new HashMap<String, Integer>();
        view.named("worker_started").forEach(event -> latest.merge(event.path(WORKER).asText(),
                event.path(GENERATION).asInt(), Math::max));
        var silent = new ArrayList<String>();
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
                silent.add(worker);
            }
        }
        return silent;
    }

    /**
     * Whether a run met a quota or limit error of its harness: its record says so ({@value #QUOTA_ERROR}) under the
     * rule of {@link #quotaDetail(String, List)} ({@value #QUOTA_RULE} {@value #HARNESS_ERROR_OUTPUT}), or the
     * harness's own error output of its workers matches {@link #QUOTA_PATTERN}. A record that says
     * {@value #QUOTA_ERROR} without the rule was written under the earlier rule, which matched every output line,
     * the results of the worker's own tool calls among them; it is read again from its stored output and
     * {@code stderr}, and counts only where the harness's own error output matches ({@link #quotaReclassified}). The
     * second reading also finds the quota errors of records written before the driver detected them.
     *
     * @param view a run
     * @return whether its harness answered with a quota error
     * @throws IOException if a worker's {@code stderr} cannot be read
     */
    static boolean quotaError(RunView view) throws IOException {
        if (view.run().path(QUOTA_ERROR).asBoolean()
                && HARNESS_ERROR_OUTPUT.equals(view.run().path(QUOTA_RULE).asText())) {
            return true;
        }
        return quotaDetail(stderrOf(view), view.outputs()).isPresent();
    }

    /**
     * Whether a run's record says {@value #QUOTA_ERROR} under the earlier rule (no {@value #QUOTA_RULE}) and the
     * harness's own error output of its workers shows none: a false quota error, read now as the run it was.
     *
     * @param view a run
     * @return whether its recorded quota error is not one
     * @throws IOException if a worker's {@code stderr} cannot be read
     */
    static boolean quotaReclassified(RunView view) throws IOException {
        return view.run().path(QUOTA_ERROR).asBoolean() && !view.run().has(QUOTA_RULE) && !quotaError(view);
    }

    private static String stderrOf(RunView view) throws IOException {
        var stderr = new StringBuilder();
        for (var worker : view.run().path("workers")) {
            var file = stderrOf(worker);
            if (file != null && Files.exists(file)) {
                stderr.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return stderr.toString();
    }

    /**
     * The quota error in a worker's own error output, the rule {@value #HARNESS_ERROR_OUTPUT}: a line of its
     * {@code stderr} that is not one of {@code pm-exec}'s own, or the error text of an error line of its structured
     * output ({@link #harnessErrorText(String)}), that matches {@link #QUOTA_PATTERN}. A tool's input or result, the
     * assistant's text and a file the worker read are never matched: the R4 large-work runs of 2026-10-23 were ended as
     * quota errors because a repository file the worker read contained the word.
     *
     * @param stderr  what a worker wrote to {@code stderr}
     * @param outputs its arrival-stamped output lines
     * @return the first matching line of {@code stderr}, else the first matching error line of the output; empty when
     *         none
     */
    static Optional<String> quotaDetail(String stderr, List<JsonNode> outputs) {
        return stderr.lines().filter(line -> !QuestionDriver.pmExecLine(line))
                .filter(line -> QUOTA_PATTERN.matcher(line).find()).findFirst()
                .or(() -> outputs.stream().map(line -> line.path("line").asText())
                        .filter(line -> harnessErrorText(line).filter(text -> QUOTA_PATTERN.matcher(text).find())
                                .isPresent())
                        .findFirst());
    }

    /**
     * The error text of one line of a harness's structured output, when the line is an error line of the harness
     * itself; the shapes are those of the stored outputs under {@code pm-e2e/target/question-loop/runs/}:
     * <ul>
     * <li>Claude Code ({@code --output-format stream-json}): a {@code result} line with {@code is_error: true} or a
     * subtype that starts with {@code error}; its text is {@code errors[]}, {@code result} and {@code error}. Verified:
     * {@code {"type":"result","subtype":"error_during_execution","is_error":true,…,"errors":["No conversation found
     * with session ID: …"]}} in R2-claude-fresh-probe-resumability-000-b59f4c86. Its {@code api_error} and
     * {@code error} events are not verified (no stored output has one) and are not matched; a quota error of Claude
     * Code reaches the run through its {@code stderr} or such a result line. Its {@code rate_limit_event} lines
     * ({@code status: allowed}, {@code allowed_warning}) are no error.</li>
     * <li>OpenCode ({@code --format json}): a line {@code {"type":"error",…,"error":{"type":…,"message":…}}}; its
     * text is its {@code error}. Verified: {@code provider.transport} in
     * Q4-opencode-warm-question_apply-answer_he-106-002-d46425ce.</li>
     * <li>Antigravity ({@code --output-format json}): its final object ({@code conversation_id}) with a textual
     * {@code error}; its text is that {@code error}, never its {@code response}. Verified:
     * {@code "status":"ERROR","error":"Individual quota reached. …"} in the Q3 quota runs
     * Q3-agy-warm-question_apply-answer_he-106-001-0910e1ab, -001-63b874de and -002-6860b0e8, whose {@code stderr}
     * carries {@code error: Individual quota reached. …} as well.</li>
     * </ul>
     *
     * @param text one output line of a worker
     * @return the error text of the line, empty when it is no error line of the harness
     */
    static Optional<String> harnessErrorText(String text) {
        JsonNode line;
        try {
            line = QuestionStub.JSON.readTree(text);
        } catch (JsonProcessingException _) {
            return Optional.empty();
        }
        if (line == null || !line.isObject()) {
            return Optional.empty();
        }
        var type = line.path("type").asText("");
        if ("result".equals(type) && (line.path("is_error").asBoolean()
                || line.path("subtype").asText("").startsWith("error"))) {
            var parts = new ArrayList<String>();
            line.path("errors").forEach(error -> parts.add(error.isTextual() ? error.asText() : error.toString()));
            for (var field : List.of("result", "error")) {
                if (line.path(field).isTextual()) {
                    parts.add(line.path(field).asText());
                }
            }
            return Optional.of(String.join("\n", parts));
        }
        if ("error".equals(type) && line.has("error")) {
            var error = line.path("error");
            return Optional.of(error.isTextual() ? error.asText() : error.toString());
        }
        if (antigravityResult(line) && line.path("error").isTextual()) {
            return Optional.of(line.path("error").asText());
        }
        return Optional.empty();
    }

    /**
     * Whether a run's fixture or workspace setup failed before any worker launched: its record says so
     * ({@value #SETUP_FAILED}), or, in a record written before that field, it names no worker and its {@code error} is
     * a failed git step of the setup ({@code <git command> failed: …}) or a missing local clone.
     *
     * @param run a run record
     * @return whether its setup failed
     */
    static boolean setupFailed(JsonNode run) {
        if (run.path(SETUP_FAILED).asBoolean()) {
            return true;
        }
        if (!run.path("workers").isMissingNode() && !run.path("workers").isEmpty()) {
            return false;
        }
        var error = run.path("error").asText("");
        return GIT_SETUP_ERROR.matcher(error).matches() || error.startsWith(NO_LOCAL_CLONE);
    }

    /**
     * @param run a run record whose setup failed ({@link #setupFailed(JsonNode)})
     * @return its reason: the recorded {@value #SETUP_FAILED_REASON}, else the first line of git's output in its
     *         {@code error}, else that error's first line
     */
    static String setupFailedReason(JsonNode run) {
        if (run.path(SETUP_FAILED_REASON).isTextual()) {
            return run.path(SETUP_FAILED_REASON).asText();
        }
        var error = run.path("error").asText("");
        var matcher = GIT_SETUP_ERROR.matcher(error);
        var text = matcher.matches() ? matcher.group(1) : error;
        return text.strip().lines().findFirst().orElse("");
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

    /**
     * Judges a Q7 group. The verdict reads, as before, {@code leases_while_open == 0}, every warm worker recycled,
     * every stub restarted while open, and every answer delivered exactly once. {@code leases_while_open} counts per
     * run ({@link #q7Run(RunView)}) the offers and deliveries of the entry's tasks between the ask and the answer and,
     * besides them, every worker generation other than the warm worker that held a {@code pm_wait} in the open time:
     * a held wait is a held slot. The group reports beside it {@value #WORKERS_CONNECTED_WHILE_OPEN},
     * {@value #HELD_WAIT_MS}, the runs whose open time cannot be read from the stub log
     * ({@code open_time_unavailable}), the runs whose asker the driver ended ({@value QuestionDriver#ASKER_ENDED_BY_DRIVER},
     * and those recorded before the field), the runs whose continuing task was offered more than once only because
     * of an offer to a generation fenced before it acknowledged ({@value #FENCED_UNACKNOWLEDGED_OFFER_RUNS}), and
     * each run's figures ({@value #Q7_RUNS}).
     *
     * @param views the group's runs
     * @param group the group
     * @return whether the group passes
     */
    static boolean q7(List<RunView> views, ObjectNode group) {
        var held = 0;
        var recycled = 0;
        var restarted = 0;
        var exactlyOnce = 0;
        var perRun = QuestionStub.JSON.createArrayNode();
        for (var view : views) {
            var figures = q7Run(view);
            perRun.add(figures);
            var asked = view.named(ASKED).stream().findFirst();
            var answered = view.named("answer_recorded").stream().findFirst();
            if (asked.isEmpty() || answered.isEmpty()) {
                continue;
            }
            var from = asked.get().path("at").asLong();
            var to = answered.get().path("at").asLong();
            held += figures.path(LEASES_WHILE_OPEN).asInt();
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
        group.put(LEASES_WHILE_OPEN, held);
        q7HeldFigures(perRun, group);
        group.put("warm_worker_recycled", recycled);
        group.put("stub_restarted_while_open", restarted);
        group.put("answer_delivered_exactly_once", exactlyOnce);
        group.set(Q7_RUNS, perRun);
        return held == 0 && recycled == views.size() && restarted == views.size() && exactlyOnce == views.size();
    }

    /** The Q7 figure, per run and per group, that the verdict reads as the slots held while the record is open. */
    static final String LEASES_WHILE_OPEN = "leases_while_open";
    /**
     * The Q7 figure of the worker generations other than the warm worker whose relay was connected and whose process
     * ran in the open time: a reported figure, no verdict reads it.
     */
    static final String WORKERS_CONNECTED_WHILE_OPEN = "workers_connected_while_open";
    /**
     * The Q7 figure of the milliseconds of {@code pm_wait} held in the open time by worker generations other than the
     * warm worker: a reported figure, no verdict reads it.
     */
    static final String HELD_WAIT_MS = "held_wait_ms";
    /** The Q7 figure of the worker generations other than the warm worker that held a {@code pm_wait} in the open time. */
    static final String HELD_WAIT_GENERATIONS = "held_wait_generations";
    /** The value of a Q7 figure that the stub log of a run cannot give. */
    static final String UNAVAILABLE = "unavailable";
    /**
     * The Q7 group figure of the runs whose continuing task was offered more than once only because of offers to a
     * generation fenced before it acknowledged the task: a reported figure, no verdict reads it.
     */
    static final String FENCED_UNACKNOWLEDGED_OFFER_RUNS = "fenced_unacknowledged_offer_runs";
    /** The per-run flag of Q7: the continuing task's offer counted twice is an offer fenced before its acknowledgement. */
    static final String OFFER_COUNTED_TWICE_FENCED = "offer_counted_twice_is_fenced_unacknowledged";
    /** The Q7 group array of the figures of each run ({@link #q7Run(RunView)}). */
    static final String Q7_RUNS = "q7_runs";

    private static final Set<String> WAIT_ENDS = Set.of("wait_again", "wait_end", "refused", "answer_delivered",
            "consultation_delivered", "offered", "disconnected", "worker_exited");
    private static final String STUB_STOPPED_EVENT = "stub_stopped";
    private static final String OFFERED = "offered";

    /**
     * One held {@code pm_wait} of a worker generation, from the stub log ({@link #waits(List)}).
     *
     * @param worker     the worker
     * @param generation its generation
     * @param start      its {@code wait_started} (epoch milliseconds)
     * @param end        its end (epoch milliseconds), {@link Long#MAX_VALUE} while the log shows none
     */
    record Wait(String worker, int generation, long start, long end) {
    }

    /**
     * One connection of a worker generation, from the stub log ({@link #connections(List)}).
     *
     * @param worker     the worker
     * @param generation its generation
     * @param start      its {@code connected} (epoch milliseconds)
     * @param end        its {@code disconnected}, its process's {@code worker_exited} or the stub's stop, whichever
     *                   is first; {@link Long#MAX_VALUE} while the log shows none
     */
    record Link(String worker, int generation, long start, long end) {
    }

    /**
     * The open time of a Q7 run: from the event {@value QuestionDriver#OPEN_TIME_STARTED} (the asker stopped, by its
     * own exit or ended by the driver), or, in a stub log written before that event, from the end of the driver's wait
     * for the asker's stop (the asking generation's second {@code worktree_digest} at or after the ask), to the
     * answer ({@code answer_recorded}).
     *
     * @param from   its start (epoch milliseconds)
     * @param to     its end (epoch milliseconds)
     * @param source {@value QuestionDriver#OPEN_TIME_STARTED} or {@code stop_wait_end}
     */
    record OpenTime(long from, long to, String source) {
    }

    /**
     * The held {@code pm_wait} calls of a stub log: each {@code wait_started} of a generation, ended by the first
     * later event of the same generation that ends a wait ({@code wait_again}, {@code wait_end}, {@code refused}, a
     * delivery, an offer over the wait, {@code disconnected}, {@code worker_exited}), by its next
     * {@code wait_started}, or by the stub's stop ({@code stub_stopped}).
     *
     * @param events the stub log
     * @return the waits, in the order they ended
     */
    static List<Wait> waits(List<JsonNode> events) {
        var open = new LinkedHashMap<String, Long>();
        var waits = new ArrayList<Wait>();
        for (var event : events) {
            var name = event.path(EVENT).asText();
            var at = event.path("at").asLong();
            if (STUB_STOPPED_EVENT.equals(name)) {
                open.forEach((key, start) -> waits.add(wait(key, start, at)));
                open.clear();
                continue;
            }
            var worker = event.path(WORKER).asText("");
            if (worker.isEmpty()) {
                continue;
            }
            var key = worker + "#" + event.path(GENERATION).asInt();
            var ends = WAIT_ENDS.contains(name) && !(OFFERED.equals(name) && event.path("at_spawn").asBoolean());
            if ("wait_started".equals(name) || ends) {
                var start = open.remove(key);
                if (start != null) {
                    waits.add(wait(key, start, at));
                }
            }
            if ("wait_started".equals(name)) {
                open.put(key, at);
            }
        }
        open.forEach((key, start) -> waits.add(wait(key, start, Long.MAX_VALUE)));
        return waits;
    }

    private static Wait wait(String key, long start, long end) {
        var cut = key.lastIndexOf('#');
        return new Wait(key.substring(0, cut), Integer.parseInt(key.substring(cut + 1)), start, end);
    }

    /**
     * @param events     the stub log
     * @param worker     a worker
     * @param generation its generation
     * @return whether the log shows the generation holding a {@code pm_wait} now: a {@code wait_started} without an end
     */
    static boolean holdingWait(List<JsonNode> events, String worker, int generation) {
        return waits(events).stream().anyMatch(wait -> wait.worker().equals(worker)
                && wait.generation() == generation && wait.end() == Long.MAX_VALUE);
    }

    /**
     * The connections of a stub log: each {@code connected} of a generation, ended by its {@code disconnected}, by its
     * process's {@code worker_exited}, or by the stub's stop ({@code stub_stopped}), whichever comes first; a second
     * {@code connected} of a generation still connected continues the connection.
     *
     * @param events the stub log
     * @return the connections, in the order they ended
     */
    static List<Link> connections(List<JsonNode> events) {
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
            } else if ("disconnected".equals(name) || "worker_exited".equals(name)) {
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
     * @return its open time ({@link OpenTime}), empty when it did not ask, was not answered, or its stub log shows
     *         neither the start of the open time nor the end of the driver's wait for the asker's stop
     */
    static Optional<OpenTime> openTime(RunView view) {
        var asked = view.named(ASKED).stream().findFirst();
        var answered = view.named("answer_recorded").stream().findFirst();
        if (asked.isEmpty() || answered.isEmpty()) {
            return Optional.empty();
        }
        var askedAt = asked.get().path("at").asLong();
        var to = answered.get().path("at").asLong();
        var started = view.named(QuestionDriver.OPEN_TIME_STARTED).stream().findFirst();
        if (started.isPresent()) {
            var from = started.get().path("at").asLong();
            return from <= to ? Optional.of(new OpenTime(from, to, QuestionDriver.OPEN_TIME_STARTED))
                    : Optional.empty();
        }
        var worker = asked.get().path(WORKER).asText();
        var generation = asked.get().path(GENERATION).asInt();
        return view.named("worktree_digest").stream().filter(event -> same(event, worker, generation))
                .filter(event -> event.path("at").asLong() >= askedAt).skip(1).findFirst()
                .map(event -> event.path("at").asLong()).filter(from -> from <= to)
                .map(from -> new OpenTime(from, to, "stop_wait_end"));
    }

    /**
     * The figures of one Q7 run. {@code offers_while_open} is the count the verdict read before: the offers and
     * deliveries of the entry's tasks between the ask and the answer. In the open time ({@link #openTime(RunView)}),
     * for every worker generation but the warm worker: {@value #HELD_WAIT_GENERATIONS} (the generations that held a
     * {@code pm_wait}), {@value #HELD_WAIT_MS} (how long, summed) and {@value #WORKERS_CONNECTED_WHILE_OPEN} (the
     * generations connected), each {@value #UNAVAILABLE} when the open time cannot be read.
     * {@value #LEASES_WHILE_OPEN} is {@code offers_while_open} plus {@value #HELD_WAIT_GENERATIONS}: a held wait is a
     * held slot. Beside them: whether the driver ended the asker ({@value QuestionDriver#ASKER_ENDED_BY_DRIVER},
     * {@code not recorded} for a record written before the field), the offers of the continuing task, those to a
     * generation fenced before it acknowledged the task ({@code fenced_unacknowledged_offers}), whether an offer
     * counted twice is such an offer ({@value #OFFER_COUNTED_TWICE_FENCED}, {@code null} when the task was offered at
     * most once), and the {@code generation_fenced} refusals of a generation no {@code fenced} event names
     * ({@code refused_fenced_without_fence}, the log artifact of the stub's stop before 2026-10-22).
     *
     * @param view a Q7 run
     * @return its figures
     */
    static ObjectNode q7Run(RunView view) {
        var node = QuestionStub.JSON.createObjectNode();
        node.put("run", view.run().has("stub_dir") ? runId(view.run()) : "");
        var asked = view.named(ASKED).stream().findFirst();
        var answered = view.named("answer_recorded").stream().findFirst();
        var offers = 0L;
        if (asked.isPresent() && answered.isPresent()) {
            var from = asked.get().path("at").asLong();
            var to = answered.get().path("at").asLong();
            var entry = view.run().path("task_id").asText();
            offers = view.events().stream().filter(event -> within(event, from, to))
                    .filter(event -> OFFERED.equals(event.path(EVENT).asText())
                            || "answer_delivered".equals(event.path(EVENT).asText()))
                    .filter(event -> event.path(TASK).asText().startsWith(entry)).count();
        }
        node.put("offers_while_open", offers);
        var warm = view.run().path("warm_worker").asText("");
        var open = openTime(view);
        var heldGenerations = 0L;
        if (open.isPresent()) {
            var from = open.get().from();
            var to = open.get().to();
            var held = waits(view.events()).stream().filter(wait -> !warm.equals(wait.worker()))
                    .filter(wait -> wait.start() < to && wait.end() > from).toList();
            heldGenerations = held.stream().map(wait -> wait.worker() + "#" + wait.generation()).distinct().count();
            node.put("open_time_from", open.get().source());
            node.put("open_time_ms", to - from);
            node.put(HELD_WAIT_GENERATIONS, heldGenerations);
            node.put(HELD_WAIT_MS, held.stream().mapToLong(wait -> Math.min(wait.end(), to)
                    - Math.max(wait.start(), from)).sum());
            node.put(WORKERS_CONNECTED_WHILE_OPEN, connections(view.events()).stream()
                    .filter(link -> !warm.equals(link.worker()))
                    .filter(link -> link.start() < to && link.end() > from)
                    .map(link -> link.worker() + "#" + link.generation()).distinct().count());
        } else {
            node.put("open_time_from", UNAVAILABLE);
            node.put(HELD_WAIT_GENERATIONS, UNAVAILABLE);
            node.put(HELD_WAIT_MS, UNAVAILABLE);
            node.put(WORKERS_CONNECTED_WHILE_OPEN, UNAVAILABLE);
        }
        node.put(LEASES_WHILE_OPEN, offers + heldGenerations);
        var ended = QuestionDriver.ASKER_ENDED_BY_DRIVER;
        if (view.run().has(ended)) {
            node.put(ended, view.run().path(ended).asBoolean());
            node.put(ended + "_after_ask_ms", view.run().path(ended + "_after_ask_ms").asLong());
            node.put(ended + "_holding_wait", view.run().path(ended + "_holding_wait").asBoolean());
        } else {
            node.put(ended, NOT_RECORDED);
        }
        continuingOffers(view, asked.map(event -> event.path(DECISION).asText()).orElse(""), node);
        node.put("refused_fenced_without_fence", refusedFencedWithoutFence(view));
        return node;
    }

    /**
     * Adds the offers of the task that continues a decision ({@code continuing_task_offers}), those of them to a
     * generation that never acknowledged the task and whose lease of it was released {@code fenced}
     * ({@code fenced_unacknowledged_offers}), and {@value #OFFER_COUNTED_TWICE_FENCED}: with more than one offer,
     * whether every offer but one is such a fenced, unacknowledged offer; {@code null} with at most one.
     */
    private static void continuingOffers(RunView view, String decision, ObjectNode node) {
        var continued = view.named("continued").stream()
                .filter(event -> decision.equals(event.path(DECISION).asText())).toList();
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
            var acknowledged = view.named("acknowledged").stream().anyMatch(event -> same(event, worker, generation)
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

    /**
     * @return the {@code refused} events with the reason {@code generation_fenced} of a generation that no
     *         {@code fenced} event before them names: in the stub before 2026-10-22, a held {@code pm_wait} that the
     *         stopping stub refused at a restart
     */
    private static long refusedFencedWithoutFence(RunView view) {
        var fenced = new HashSet<String>();
        var count = 0L;
        for (var event : view.events()) {
            var name = event.path(EVENT).asText();
            var key = event.path(WORKER).asText() + "#" + event.path(GENERATION).asInt();
            if ("fenced".equals(name)) {
                fenced.add(key);
            } else if ("refused".equals(name) && "generation_fenced".equals(event.path("reason").asText())
                    && !fenced.contains(key)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Adds the group's sums of the per-run held figures: {@value #WORKERS_CONNECTED_WHILE_OPEN},
     * {@value #HELD_WAIT_MS} and {@value #HELD_WAIT_GENERATIONS} over the runs whose open time is readable
     * ({@value #UNAVAILABLE} when none is), {@code open_time_unavailable}, the runs whose asker the driver ended and
     * those recorded before the field, and {@value #FENCED_UNACKNOWLEDGED_OFFER_RUNS}.
     */
    private static void q7HeldFigures(ArrayNode perRun, ObjectNode group) {
        var available = 0;
        var connected = 0L;
        var heldMs = 0L;
        var heldGenerations = 0L;
        var endedByDriver = 0;
        var notRecorded = 0;
        var fencedOffers = 0;
        for (var run : perRun) {
            if (run.path(HELD_WAIT_MS).isNumber()) {
                available++;
                connected += run.path(WORKERS_CONNECTED_WHILE_OPEN).asLong();
                heldMs += run.path(HELD_WAIT_MS).asLong();
                heldGenerations += run.path(HELD_WAIT_GENERATIONS).asLong();
            }
            var ended = run.path(QuestionDriver.ASKER_ENDED_BY_DRIVER);
            endedByDriver += ended.isBoolean() && ended.asBoolean() ? 1 : 0;
            notRecorded += ended.isBoolean() ? 0 : 1;
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
        group.put(QuestionDriver.ASKER_ENDED_BY_DRIVER + "_not_recorded", notRecorded);
        group.put(FENCED_UNACKNOWLEDGED_OFFER_RUNS, fencedOffers);
    }

    /**
     * Adds the limit of Q7: what its open time is and how {@value #LEASES_WHILE_OPEN} counts a held wait; that the
     * asker ended by the driver is a harness action, with the runs that needed it (and, for records written before the
     * driver ended the asker, the runs whose asker held {@code pm_wait} into the open time); that a model that does
     * not end its turn after the ask is a finding of the run, not of the server; the runs whose open time cannot be
     * read; the runs whose continuing task was offered twice because of a fenced, unacknowledged offer; and the runs
     * whose stub log names a held wait refused at the stub's stop as {@code generation_fenced}.
     *
     * @param result the result of Q7
     * @param runs   its run records
     * @throws IOException if a run's files cannot be read
     */
    static void limitOfQ7(ObjectNode result, List<JsonNode> runs) throws IOException {
        var figures = new ArrayList<ObjectNode>();
        for (var run : runs) {
            figures.add(q7Run(view(run)));
        }
        limitOfQ7Figures(result, figures);
    }

    /**
     * The limit of Q7 ({@link #limitOfQ7}) from the figures of its runs ({@link #q7Run(RunView)}).
     *
     * @param result  the result of Q7
     * @param figures the figures of its runs
     */
    static void limitOfQ7Figures(ObjectNode result, List<ObjectNode> figures) {
        var field = QuestionDriver.ASKER_ENDED_BY_DRIVER;
        var ended = new ArrayList<String>();
        var recorded = 0;
        var heldBefore = new ArrayList<String>();
        var unavailable = new ArrayList<String>();
        var fencedOffers = new ArrayList<String>();
        var refusedAtStop = new ArrayList<String>();
        for (var run : figures) {
            var id = run.path("run").asText();
            if (run.path(field).isBoolean()) {
                recorded++;
                if (run.path(field).asBoolean()) {
                    ended.add(id);
                }
            } else if (run.path(HELD_WAIT_GENERATIONS).asLong() > 0) {
                heldBefore.add(id + " " + run.path(HELD_WAIT_MS).asLong() + " ms");
            }
            if (!run.path(HELD_WAIT_MS).isNumber()) {
                unavailable.add(id);
            }
            if (run.path(OFFER_COUNTED_TWICE_FENCED).asBoolean()) {
                fencedOffers.add(id);
            }
            if (run.path("refused_fenced_without_fence").asLong() > 0) {
                refusedAtStop.add(id);
            }
        }
        var text = new StringBuilder("The open time of a run starts when the asking generation has stopped (the "
                + "event " + QuestionDriver.OPEN_TIME_STARTED + "; in a stub log written before it, the end of the "
                + "driver's wait for the asker's stop, its second worktree_digest) and ends with the answer "
                + "(answer_recorded). " + LEASES_WHILE_OPEN + " counts, as before, the offers and deliveries of the "
                + "entry's tasks between the ask and the answer, and besides them every worker generation other than "
                + "the warm worker that held a pm_wait in the open time: a held wait is a held slot. "
                + WORKERS_CONNECTED_WHILE_OPEN + " (generations other than the warm worker whose relay was connected "
                + "in the open time) and " + HELD_WAIT_MS + " (how long they held pm_wait in it) are reported "
                + "figures beside it. The verdict logic is unchanged.");
        text.append(" The fresh asker that has not ended its turn at its silence limit after the ask is ended by "
                + "the driver before the open time starts (" + field + "), a harness action as in the other fresh "
                + "items; a model that does not end its turn after the ask is a finding of the run, not of the "
                + "server.");
        if (recorded > 0) {
            text.append(" The driver ended the asker in ").append(ended.size()).append(" of ").append(recorded)
                    .append(" runs").append(ended.isEmpty() ? "" : " (" + String.join(", ", ended) + ")")
                    .append('.');
        }
        if (recorded < figures.size()) {
            text.append(" ").append(figures.size() - recorded).append(" run records were written before the driver "
                    + "ended the asker (" + field + " not recorded); in ").append(heldBefore.size())
                    .append(" of them the asker held pm_wait into the open time and counts in " + LEASES_WHILE_OPEN)
                    .append(heldBefore.isEmpty() ? "." : ": " + String.join(", ", heldBefore) + ".");
        }
        if (!unavailable.isEmpty()) {
            text.append(" The open time cannot be read from the stub log of ").append(String.join(", ", unavailable))
                    .append(": its held figures are " + UNAVAILABLE + ", and " + LEASES_WHILE_OPEN
                            + " counts its offers and deliveries only.");
        }
        if (!fencedOffers.isEmpty()) {
            text.append(" In ").append(String.join(", ", fencedOffers)).append(" the continuing task was offered "
                    + "twice because the stub offered it over the held pm_wait of a generation the driver then "
                    + "fenced, before that generation acknowledged it (" + OFFER_COUNTED_TWICE_FENCED + "); "
                    + "answer_delivered_exactly_once counts that offer, the verdict logic being unchanged.");
        }
        if (!refusedAtStop.isEmpty()) {
            text.append(" The stub logs of ").append(String.join(", ", refusedAtStop)).append(" show a pm_wait "
                    + "refused generation_fenced of a generation no fenced event names: the stub before 2026-10-22 "
                    + "refused a wait it held when it stopped for the restart with that reason; it was not fenced, "
                    + "and it reconnected with fenced: false. The stub now refuses it " + QuestionStub.STUB_STOPPED
                    + ".");
        }
        limit(result, text.toString());
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

    /**
     * Adds {@value #RESTART_BREAKDOWN} to an R2 group: its runs split by their record's {@code restart}, each part with
     * {@code runs}, {@code asked}, {@code submitted} and {@code timed_out} as {@link #counts(List, ObjectNode)} counts
     * them, and {@code continued_from_session} as {@link #r2(List, ObjectNode)} counts it
     * ({@link #continuedFromSession(RunView)}). A reported figure: no verdict reads it.
     *
     * @param views the group's runs
     * @param group the group
     * @throws IOException if a run's files cannot be read
     */
    static void restartBreakdown(List<RunView> views, ObjectNode group) throws IOException {
        var breakdown = group.putObject(RESTART_BREAKDOWN);
        for (var restart : List.of(true, false)) {
            var part = views.stream().filter(view -> view.run().path("restart").asBoolean() == restart).toList();
            var counted = QuestionStub.JSON.createObjectNode();
            counts(part, counted);
            var node = breakdown.putObject(String.valueOf(restart));
            node.put(RUNS, part.size());
            node.put(ASKED, counted.path(ASKED).asInt());
            node.put("continued_from_session", part.stream().filter(QuestionResults::continuedFromSession).count());
            node.put(SUBMITTED, counted.path(SUBMITTED).asInt());
            node.put(TIMED_OUT, counted.path(TIMED_OUT).asInt());
        }
    }

    /**
     * Whether a positive R2 run continued from the session, as {@link #r2(List, ObjectNode)} counts it: an answer was
     * delivered to a resumed generation, an answer was acknowledged, and the submission names the canary.
     *
     * @param view a run
     * @return whether it continued from the session; never for a negative resume
     */
    static boolean continuedFromSession(RunView view) {
        if (view.run().path("negative").asBoolean()) {
            return false;
        }
        var acknowledged = view.named("acknowledged").stream()
                .anyMatch(event -> "answer".equals(event.path("kind").asText()));
        var resumed = view.named("answer_delivered").stream().anyMatch(event -> event.path("resumed").asBoolean());
        return resumed && acknowledged && namesCanary(view);
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

    /** The field of an R3 run record of {@link Fault#CALL_CUT}: whether the logs show the host's cut. */
    static final String CALL_CUT_FIELD = "call_cut";

    /**
     * The field of a run record whose stub did not lose a worker at its process exit
     * ({@link QuestionStub.Settings#loseOnExit()} false, every R3 run from 2026-10-23); absent otherwise.
     */
    static final String STUB_LOSE_ON_EXIT = "stub_lose_on_exit";

    /**
     * The R3 group figure of the exit observations ({@link #exitObservations}): per run the time from the delivery of
     * the answer to the observed exit of the generation's process and, where it has one, the time of the detection by
     * the acknowledgement; an additional figure, no verdict reads it.
     */
    static final String EXIT_OBSERVATION = "exit_observation";

    /**
     * When a generation's answer was delivered and its process exit observed, from a stub log
     * ({@link #exitObservation(List, String, int)}).
     *
     * @param deliveredAt         its first {@code answer_delivered} (epoch milliseconds), {@code null} without one
     * @param exitAt              its first {@code worker_exited}, the driver's observation of the exit (epoch
     *                            milliseconds), {@code null} without one
     * @param exitAfterDeliveryMs the milliseconds from the delivery to the exit (negative for an exit before the
     *                            delivery), {@code null} without either
     */
    record ExitObservation(Long deliveredAt, Long exitAt, Long exitAfterDeliveryMs) {
    }

    /**
     * @param events     a stub log
     * @param worker     a worker
     * @param generation its generation
     * @return when the generation's answer was delivered and its process exit observed
     */
    static ExitObservation exitObservation(List<JsonNode> events, String worker, int generation) {
        var delivered = firstAt(events, "answer_delivered", worker, generation);
        var exited = firstAt(events, "worker_exited", worker, generation);
        return new ExitObservation(delivered, exited, delivered == null || exited == null ? null
                : exited - delivered);
    }

    private static Long firstAt(List<JsonNode> events, String name, String worker, int generation) {
        return events.stream().filter(event -> name.equals(event.path(EVENT).asText())
                && same(event, worker, generation)).findFirst().map(event -> event.path("at").asLong()).orElse(null);
    }

    /**
     * Adds {@value #EXIT_OBSERVATION} to an R3 group, for each run the generation the driver detected as lost
     * ({@code lost_detected}), else the one the fault was injected into: the milliseconds from the delivery of its
     * answer to its observed process exit ({@code exit_observed_after_delivery_ms}), the detection's milliseconds after
     * the delivery and by what ({@code detection_after_ms}, {@code detection_by}), and whether the exit was observed
     * before the detection; the group sums the exits observed, the exits before the detection, the median of the exit
     * times, and the median detection time of the runs with an exit. An additional figure: the verdict logic of R3 is
     * unchanged.
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
            figures.put("run", view.run().has("stub_dir") ? runId(view.run()) : "");
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
            var observation = exitObservation(view.events(), worker, generation);
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

    /** The R3 {@link Fault#CALL_CUT} group figure of the runs whose held call the logs show cut by the host. */
    static final String CUT_BY_HOST = "cut_by_host";
    /**
     * The R3 {@link Fault#CALL_CUT} group figure of the runs whose cut the worker survived
     * ({@link #cutSurvived(RunView, JsonNode)}).
     */
    static final String CUT_SURVIVED = "cut_survived";
    /** The R3 {@link Fault#CALL_CUT} figure of the milliseconds from the cut to the generation's next pm_wait. */
    static final String RE_WAIT_AFTER_CUT_MS = "re_wait_after_cut_ms";
    /** The limit of R3 on a survived call cut, verbatim. */
    static final String CUT_SURVIVED_LIMIT = "a call cut that the worker survives by waiting again is not a loss; the "
            + "criterion counts it as undetected; reported as cut_survived";

    /**
     * The host's cut of a run's held call as the stub log shows it: the driver's {@value QuestionDriver#FAULT_INJECTED}
     * of {@link Fault#CALL_CUT} with {@code cut_by: host} ({@link QuestionDriver#awaitCallCut}).
     *
     * @param view a run
     * @return the cut event, empty when the logs show none
     */
    static Optional<JsonNode> hostCut(RunView view) {
        return view.named(QuestionDriver.FAULT_INJECTED).stream()
                .filter(event -> Fault.CALL_CUT.name().equals(event.path("fault").asText())
                        && "host".equals(event.path("cut_by").asText()))
                .findFirst();
    }

    /**
     * The milliseconds from the host's cut of a held call to the next {@code pm_wait} the same generation called at or
     * after it ({@value QuestionDriver#CALL} of {@code pm_wait}).
     *
     * @param view a run
     * @param cut  its cut ({@link #hostCut(RunView)})
     * @return the milliseconds, empty when the generation did not call {@code pm_wait} again
     */
    static Optional<Long> reWaitAfterCut(RunView view, JsonNode cut) {
        var worker = cut.path(WORKER).asText();
        var generation = cut.path(GENERATION).asInt();
        var cutAt = cut.path("cut_at").asLong(cut.path("at").asLong());
        return view.events().stream()
                .filter(event -> QuestionDriver.CALL.equals(event.path(EVENT).asText())
                        && "pm_wait".equals(event.path("tool").asText()) && same(event, worker, generation)
                        && event.path("at").asLong() >= cutAt)
                .map(event -> event.path("at").asLong() - cutAt).findFirst();
    }

    /**
     * Whether a worker survived the host's cut of its held call: the same generation called {@code pm_wait} again
     * after the cut ({@link #reWaitAfterCut}), the answer was delivered to it exactly once, it acknowledged the answer,
     * and no loss of it was detected. Nothing was lost, so nothing was detected; the criterion counts the run as
     * undetected and the verdict logic reads it so.
     *
     * @param view a run
     * @param cut  its cut ({@link #hostCut(RunView)})
     * @return whether the worker survived the cut
     */
    static boolean cutSurvived(RunView view, JsonNode cut) {
        var worker = cut.path(WORKER).asText();
        var generation = cut.path(GENERATION).asInt();
        var delivered = view.named("answer_delivered").stream().filter(event -> same(event, worker, generation))
                .count();
        var acknowledged = view.named("acknowledged").stream().anyMatch(event -> same(event, worker, generation)
                && "answer".equals(event.path("kind").asText()));
        var lost = view.named(QuestionDriver.LOST_DETECTED).stream().anyMatch(event -> same(event, worker,
                generation));
        return reWaitAfterCut(view, cut).isPresent() && delivered == 1 && acknowledged && !lost;
    }

    /**
     * Adds the figures of a survived call cut to an R3 {@link Fault#CALL_CUT} group: {@value #CUT_BY_HOST} (runs whose
     * held call the logs show cut by the host), {@value #CUT_SURVIVED} ({@link #cutSurvived}), and
     * {@value #RE_WAIT_AFTER_CUT_MS} median and max over the cut runs whose generation called {@code pm_wait} again;
     * additional figures, the verdict logic of R3 is unchanged ({@value #CUT_SURVIVED_LIMIT}).
     *
     * @param views the group's runs
     * @param group the group
     */
    static void cutSurvival(List<RunView> views, ObjectNode group) {
        var cuts = 0;
        var survived = 0;
        var reWaits = new ArrayList<Long>();
        for (var view : views) {
            var cut = hostCut(view);
            if (cut.isEmpty()) {
                continue;
            }
            cuts++;
            reWaitAfterCut(view, cut.get()).ifPresent(reWaits::add);
            survived += cutSurvived(view, cut.get()) ? 1 : 0;
        }
        group.put(CUT_BY_HOST, cuts);
        group.put(CUT_SURVIVED, survived);
        group.put(RE_WAIT_AFTER_CUT_MS + "_median", median(reWaits));
        group.put(RE_WAIT_AFTER_CUT_MS + "_max", max(reWaits));
    }

    /**
     * Adds the limits of R3 to its result: how each fault is produced, per host for the call cut, and how many runs
     * had it as specified (the stop injected while the asker held {@code pm_wait}; the held call cut by the host), and
     * in how many runs with a detected loss the stub had released the task at the worker's exit, before the
     * acknowledgement deadline ({@code loss_first_by: stub}). Records of runs before 2026-10-23 carry none of these
     * fields and count as not held, not cut, and not first seen by the stub. A last limit states that the stub of R3
     * does not react to a worker's process exit ({@value #STUB_LOSE_ON_EXIT}), that exit detection was measured in
     * Part A, and in how many runs the stub ran so.
     *
     * @param result the result of R3
     * @param runs   its run records
     */
    static void limitsOfR3(ObjectNode result, List<JsonNode> runs) {
        var stops = runs.stream().filter(run -> Fault.STOP_BEFORE_ANSWER.name().equals(run.path("fault").asText()))
                .toList();
        limit(result, "The stop before the answer (STOP_BEFORE_ANSWER) is injected once the stub shows the asker "
                + "holding pm_wait after its ask, before the answer is sent; an asker that never holds is not stopped "
                + "(fault_not_injected). Stopped while holding: " + stops.stream()
                .filter(run -> run.path("fault_held").asBoolean()).count() + " of " + stops.size() + " runs.");
        var cuts = new TreeMap<String, long[]>();
        runs.stream().filter(run -> Fault.CALL_CUT.name().equals(run.path("fault").asText()))
                .forEach(run -> {
                    var counts = cuts.computeIfAbsent(run.path(HOST).asText(), _ -> new long[2]);
                    counts[0] += run.path(CALL_CUT_FIELD).asBoolean() ? 1 : 0;
                    counts[1]++;
                });
        var perHost = new ArrayList<String>();
        cuts.forEach((host, counts) -> perHost.add(host + " " + counts[0] + " of " + counts[1]));
        limit(result, "The call cut (CALL_CUT) is made by the host and never simulated: the held pm_wait gets no "
                + "progress frame, and the operator answers " + QuestionDriver.CALL_CUT_MARGIN.toSeconds() + " s after "
                + "the host's cut. Claude Code: MCP_TOOL_TIMEOUT of " + QuestionDriver.CALL_CUT_TOOL_TIMEOUT.toSeconds()
                + " s; Part A measured only its default, a silent call ended after 1800 s. OpenCode: the MCP server's "
                + "timeout of " + QuestionDriver.CALL_CUT_TOOL_TIMEOUT.toSeconds() + " s in opencode.json, the 60 s "
                + "after which Part A saw it end a silent call. Antigravity: no tool timeout to configure; its "
                + QuestionDriver.ANTIGRAVITY_CALL_LIMIT.toSeconds() + " s limit on every call, with the stub's bounded "
                + "wait raised above it. A cut counts only where the relay's host-side log (notifications/cancelled of "
                + "the held call) or the stub log (a newer pm_wait superseding it, a failed reply, a disconnect) shows "
                + "it; a host with no cut did not cut a held call in these runs. Cut: "
                + (perHost.isEmpty() ? "no run" : String.join(", ", perHost)) + ".");
        if (!cuts.isEmpty()) {
            limit(result, CUT_SURVIVED_LIMIT + ". Each CALL_CUT group reports " + CUT_BY_HOST + ", " + CUT_SURVIVED
                    + " (the same generation called pm_wait again after the cut, got the answer once and acknowledged "
                    + "it) and " + RE_WAIT_AFTER_CUT_MS + " (median and max, from the cut to that pm_wait); additional "
                    + "figures, the verdict logic is unchanged.");
        }
        var losses = runs.stream().filter(run -> run.has("loss_by")).toList();
        limit(result, "In " + losses.stream().filter(run -> "stub".equals(run.path("loss_first_by").asText()))
                .count() + " of " + losses.size() + " runs with a detected loss the stub had already released the "
                + "task at the worker's exit, before the acknowledgement deadline (lost_detected first_by stub, with "
                + "stub_reason and stub_after_ms): their stub log shows a reaction earlier than the deadline.");
        limit(result, "The stub did not react to a worker's process exit in R3 (" + STUB_LOSE_ON_EXIT + " false, from "
                + "2026-10-23): it logged the exit as an observation (" + QuestionStub.EXIT_OBSERVED + ") and closed, "
                + "released or continued no task at it, so the task stayed leased until the driver detected the "
                + "missing answer acknowledgement at the host's deadline (by silence where the acknowledgement is "
                + "informational). Exit detection itself was measured in Part A (evaluation.adoc: 205 to 302 ms on "
                + "Claude Code). Where the answer never reached a generation whose process had exited without a held "
                + "pm_wait, the driver ended the generation after its delivery wait (" + QuestionDriver.ANSWER_UNDELIVERED
                + "), no detection. " + EXIT_OBSERVATION + " reports per run the time from the delivery to the exit "
                + "observation and the detection time, additional figures; the verdict logic is unchanged. Runs with "
                + "this stub: " + runs.stream().filter(run -> run.has(STUB_LOSE_ON_EXIT)
                        && !run.path(STUB_LOSE_ON_EXIT).asBoolean(true)).count() + " of " + runs.size()
                + "; the stub of the others, recorded before it, lost the worker at its exit.");
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

    /**
     * The longest passing value of a parameter per form ({@code fresh}, {@code warm}) and host and model, read as
     * {@link #longestPassing(ArrayNode, String)} reads it over all groups; a form without a passing group maps to an
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

    // --- per run ---

    private static RunView view(JsonNode run) throws IOException {
        var stub = Path.of(run.path("stub_dir").asText());
        var outputs = new ArrayList<JsonNode>();
        for (var worker : run.path("workers")) {
            outputs.addAll(readJsonLines(Path.of(worker.path("output").asText())));
        }
        return new RunView(run, readJsonLines(stub.resolve("events.jsonl")), readJsonLines(stub.resolve(
                "questions.jsonl")), outputs, evidenceMissing(run));
    }

    /**
     * Whether a run's evidence is missing: its record names no stub directory, or the directory or its
     * {@code events.jsonl} no longer exists, as after the reboot of 2026-10-24 that wiped the OS temp directory the
     * stub logs were kept in. A run whose setup failed has no stub log to read and is never counted so
     * ({@link #setupFailed(JsonNode)}). Such a run's events read as empty, which the counts must never take for a run
     * that did not ask ({@value #EVIDENCE_MISSING}).
     *
     * @param run a run record
     * @return whether its stub log is missing
     */
    static boolean evidenceMissing(JsonNode run) {
        if (setupFailed(run)) {
            return false;
        }
        var stub = run.path("stub_dir").asText("");
        return stub.isBlank() || !Files.isRegularFile(Path.of(stub).resolve("events.jsonl"));
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
        return rereadsCanary(view.outputs(), askedAt);
    }

    /**
     * Whether the worker read the canary file again after the ask (R1): an output line after the ask that is a tool
     * call of the worker's own tools whose input names {@value QuestionFixtures#CANARY_FILE}
     * ({@link #readsFile(String, String)}). A call of a stub tool ({@link #STUB_TOOLS}) never counts, whatever its
     * arguments say: the worker's submit names the canary file in its judgment ("read canary.txt"), which the detector
     * before 2026-10-23 counted as a re-read (11 of the 13 runs it flagged in the R1 smoke pass of 2026-10-22). Neither
     * does assistant prose, a tool result (the stub's facts quote the task, which names the file), or a tool's output
     * (a directory listing names the file).
     *
     * @param outputs a run's arrival-stamped output lines
     * @param askedAt when the worker asked (epoch milliseconds)
     * @return whether a line after the ask reads the canary file
     */
    static boolean rereadsCanary(List<JsonNode> outputs, long askedAt) {
        return outputs.stream().filter(line -> line.path("at").asLong() > askedAt)
                .anyMatch(line -> readsFile(line.path("line").asText(), QuestionFixtures.CANARY_FILE));
    }

    /**
     * Whether one output line of a worker is a call of one of its own tools whose input names a file, per host:
     * <ul>
     * <li>Claude Code ({@code --output-format stream-json}): an {@code assistant} line whose {@code message.content}
     * has an item {@code {"type":"tool_use","name":…,"input":{…}}} whose name is no stub tool (the stub's tools are
     * named {@code mcp__plan-marshall__pm_do} and so on) and whose input names the file, as {@code Read}
     * ({@code file_path}), {@code Bash} ({@code command}) or {@code Grep} ({@code path}, {@code pattern}) do. Verified
     * on the stored outputs of the R1 smoke pass: {@code Read} with the file's absolute {@code file_path} in runs
     * {@code R1-claude-fresh-probe-resumability-001-868f38e9} and {@code R1-claude-warm-probe-resumability-001-3c8a69d0}.
     * </li>
     * <li>OpenCode ({@code --format json}): a line {@code {"type":"tool_use","part":{"type":"tool","tool":…,
     * "state":{"input":{…},"output":…}}}} whose tool is no stub tool and whose input names the file (not its output);
     * for the tool {@code execute}, whose input is the code it runs, only when that code calls no stub tool, since
     * OpenCode calls the stub's tools through it ({@code tools["plan-marshall"].pm_do(…)}) with the submit's text in
     * its arguments. Verified on the stored output of
     * {@code Q2-opencode-warm-question_ask-or-proceed_he-0-original-000-4e0451b0} ({@code read} with
     * {@code path: canary.txt}); an {@code execute} that reads the file was not observed in any stored output.</li>
     * <li>Antigravity ({@code --output-format json}): not verified. Its output is one final object
     * ({@code conversation_id}, {@code response}, {@code usage}) without its tool calls in every stored Antigravity
     * output, so no read of a file can be seen in it and none counts.</li>
     * </ul>
     *
     * @param text one output line of a worker
     * @param file the file name
     * @return whether it is a call of one of the worker's own tools whose input names the file
     */
    static boolean readsFile(String text, String file) {
        if (!text.contains(file)) {
            return false;
        }
        JsonNode line;
        try {
            line = QuestionStub.JSON.readTree(text);
        } catch (JsonProcessingException _) {
            return false;
        }
        return claudeCodeReadsFile(line, file) || openCodeReadsFile(line, file);
    }

    private static boolean claudeCodeReadsFile(JsonNode line, String file) {
        if (!"assistant".equals(line.path("type").asText())) {
            return false;
        }
        for (var content : line.path("message").path("content")) {
            if ("tool_use".equals(content.path("type").asText()) && !stubTool(content.path("name").asText())
                    && content.path("input").toString().contains(file)) {
                return true;
            }
        }
        return false;
    }

    private static boolean openCodeReadsFile(JsonNode line, String file) {
        var part = line.path("part");
        if (!"tool_use".equals(line.path("type").asText()) || !"tool".equals(part.path("type").asText())) {
            return false;
        }
        var tool = part.path("tool").asText();
        var input = part.path("state").path("input");
        if (stubTool(tool) || !input.toString().contains(file)) {
            return false;
        }
        if ("execute".equals(tool)) {
            var code = input.path("code").asText();
            return STUB_TOOLS.stream().noneMatch(stub -> code.contains(stub + "("));
        }
        return true;
    }

    private static boolean stubTool(String name) {
        return STUB_TOOLS.stream().anyMatch(stub -> name.equals(stub) || name.endsWith("_" + stub)
                || name.endsWith("." + stub));
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

    // --- answer audit ---

    /**
     * The field of a run record with how each of its operator answers was composed
     * ({@link QuestionFixtures#composeAnswer}): per part the decision, question id, reply, chosen branch and label, and
     * whether the fixture has a consistency hint, the chosen option matched it, or the answer fell back to the
     * operator's own option. Records written before 2026-10-24 have none.
     */
    static final String ANSWER_COMPOSITIONS = "answer_compositions";
    /**
     * The field of a run record, and the group figure, of a run in which an operator answer fell back to the
     * operator's own option because no option of the asker matched the fixture's consistency hint: a reported figure,
     * no verdict reads it.
     */
    static final String ANSWER_FALLBACK_OWN = "answer_fallback_own";
    /** The audit figure of a recorded operator answer whose chosen branch contradicts its own reply text. */
    static final String CONTRADICTING = "contradicting";

    /**
     * Records how an operator answer of a run was composed: appends one entry per part to
     * {@value #ANSWER_COMPOSITIONS} ({@link QuestionDriver#compositionFields}) and sets {@value #ANSWER_FALLBACK_OWN}
     * once any part of any answer of the run fell back to the operator's own option.
     *
     * @param run         the run record, changed in place
     * @param decisionId  the decision answered
     * @param reply       the reply form
     * @param composition the composed answer
     */
    static void recordComposition(ObjectNode run, String decisionId, QuestionFixtures.Reply reply,
            QuestionFixtures.Composition composition) {
        var entries = run.get(ANSWER_COMPOSITIONS) instanceof ArrayNode existing ? existing
                : run.putArray(ANSWER_COMPOSITIONS);
        for (var note : composition.notes()) {
            entries.add(QuestionStub.JSON.valueToTree(QuestionDriver.compositionFields(decisionId, reply, note)));
        }
        run.put(ANSWER_FALLBACK_OWN, run.path(ANSWER_FALLBACK_OWN).asBoolean() || composition.fallbackOwn());
    }

    /**
     * Adds a limit naming the runs whose operator answer fell back to the operator's own option
     * ({@value #ANSWER_FALLBACK_OWN}): on a fixture with a consistency hint, no option of the asker read as the
     * reference direction (for a delegated answer: the recommendation did not), so the reply went as the operator's own
     * option instead of beside a branch that contradicts it. The group figure {@value #ANSWER_FALLBACK_OWN} counts them;
     * the verdict logic is unchanged and reads them as any other run.
     *
     * @param result the result
     * @param runs   its run records
     */
    static void limitOfAnswerFallbacks(ObjectNode result, List<JsonNode> runs) {
        var composed = runs.stream().filter(run -> run.has(ANSWER_COMPOSITIONS)).count();
        if (composed == 0) {
            return;
        }
        var fallbacks = new TreeMap<String, List<String>>();
        for (var run : runs) {
            if (run.path(ANSWER_FALLBACK_OWN).asBoolean()) {
                fallbacks.computeIfAbsent(run.path(HOST).asText(), _ -> new ArrayList<>()).add(runId(run));
            }
        }
        limit(result, "An operator answer never pairs a branch with a reply text that contradicts it: on a fixture "
                + "with a consistency hint (he-0, he-106, the probe) an answered option takes the asker's recommended "
                + "option where it reads as the fixture's reference direction, else the first option that does, and a "
                + "delegated answer only a recommendation that does; where none does, the reply goes as the "
                + "operator's own option (" + ANSWER_FALLBACK_OWN + "). Each run record carries "
                + ANSWER_COMPOSITIONS + " (" + composed + " of " + runs.size() + " records here); records without it "
                + "were answered with the recommended branch beside the fixture's reply, also where the two "
                + "contradict. The verdict logic is unchanged" + (fallbacks.isEmpty() ? "; no answer fell back."
                : "; fell back: " + namedRuns(fallbacks) + "."));
    }

    /**
     * The answer audit over stored runs, without a model: reads each run's question records ({@code questions.jsonl}
     * of its stub directory), and classifies every answer the driver gave on the operator's behalf (channel
     * {@code cli}) by the fixture's consistency hint ({@link QuestionFixtures#hint(String)}): the chosen option of a
     * {@code chosen} or {@code delegated} answer reads as the reference direction ({@code consistent}), as the opposite
     * direction ({@value #CONTRADICTING}: the branch contradicts the fixture's reply text beside it), or as neither
     * ({@code unclear}); an answer with the operator's own option ({@code own}) chooses no branch; a fixture without a
     * hint is counted {@code unhinted}; an answer of a quorum ({@code quorum}) carries no reply text. Per group (item,
     * host, model, warmth, kind, reply, fixture) it counts the runs, the runs with an operator answer, the runs with a
     * contradicting answer and among them those whose last submission does not follow the reference, the answers per
     * class, and how many of the stored operator answers the composition with the hint would answer with the
     * operator's own option ({@code recomposed_fallback_own}). It lists the contradicting runs, the result groups of
     * the items they belong to ({@code affected_result_groups}, as {@link #evaluate} keys them), and per fixture with a
     * hint every option the askers offered with its class, how often it was offered and recommended
     * ({@code options}). The records are only read.
     *
     * @param runs the run records
     * @return the audit
     * @throws IOException if a run's files cannot be read
     */
    static ObjectNode answerAudit(List<JsonNode> runs) throws IOException {
        var groups = new TreeMap<String, ObjectNode>();
        var affected = new TreeSet<String>();
        var options = new TreeMap<String, TreeMap<String, ObjectNode>>();
        for (var run : runs) {
            if (setupFailed(run)) {
                continue;
            }
            var group = groups.computeIfAbsent(auditKey(run), _ -> auditGroup(run));
            var view = view(run);
            var counts = auditRun(view, options);
            group.put(RUNS, group.path(RUNS).asInt() + 1);
            for (var entry : counts.properties()) {
                if (entry.getValue().isArray()) {
                    var list = group.get(entry.getKey()) instanceof ArrayNode existing ? existing
                            : group.putArray(entry.getKey());
                    list.addAll((ArrayNode) entry.getValue());
                } else {
                    group.put(entry.getKey(), group.path(entry.getKey()).asInt() + entry.getValue().asInt());
                }
            }
            if (counts.path("operator_answers").asInt() > 0) {
                group.put("runs_with_operator_answer", group.path("runs_with_operator_answer").asInt() + 1);
            }
            if (counts.path(CONTRADICTING).asInt() > 0) {
                group.put("contradicting_runs", group.path("contradicting_runs").asInt() + 1);
                if (!followsReference(view)) {
                    group.put("contradicting_runs_not_following",
                            group.path("contradicting_runs_not_following").asInt() + 1);
                }
                ((ArrayNode) group.get("contradicting_run_ids")).add(runId(run));
                affected.add(run.path("item").asText() + " " + groupKey(run.path("item").asText(), run));
            }
        }
        var audit = QuestionStub.JSON.createObjectNode();
        var array = audit.putArray("groups");
        var totals = audit.putObject("totals");
        for (var group : groups.values()) {
            array.add(group);
            for (var field : List.of(RUNS, "runs_with_operator_answer", "contradicting_runs",
                    "contradicting_runs_not_following", "operator_answers", CONTRADICTING, "consistent", "unclear",
                    "own", "unhinted", "quorum", "recomposed_fallback_own")) {
                totals.put(field, totals.path(field).asInt() + group.path(field).asInt());
            }
        }
        var groupsToRerun = audit.putArray("affected_result_groups");
        affected.forEach(groupsToRerun::add);
        var catalogue = audit.putObject("options");
        options.forEach((fixture, labels) -> {
            var list = catalogue.putArray(fixture);
            labels.values().forEach(list::add);
        });
        return audit;
    }

    private static String auditKey(JsonNode run) {
        var key = new StringBuilder();
        for (var field : List.of("item", HOST, MODEL, "form", "kind", "reply", "fixture")) {
            key.append(field).append('=').append(MODEL.equals(field) ? model(run) : run.path(field).asText())
                    .append(' ');
        }
        return key.toString().strip();
    }

    private static ObjectNode auditGroup(JsonNode run) {
        var group = QuestionStub.JSON.createObjectNode();
        group.put("item", run.path("item").asText());
        group.put(HOST, run.path(HOST).asText());
        group.put(MODEL, model(run));
        group.put("form", run.path("form").asText());
        group.put("kind", run.path("kind").asText());
        group.put("reply", run.path("reply").asText());
        group.put("fixture", run.path("fixture").asText());
        group.put("hinted", QuestionFixtures.hint(run.path("fixture").asText()).isPresent());
        for (var field : List.of(RUNS, "runs_with_operator_answer", "contradicting_runs",
                "contradicting_runs_not_following", "operator_answers", CONTRADICTING, "consistent", "unclear", "own",
                "unhinted", "quorum", "recomposed_fallback_own")) {
            group.put(field, 0);
        }
        group.putArray("contradicting_run_ids");
        return group;
    }

    /**
     * Classifies the answers of one run ({@link #answerAudit}) and adds the options its askers offered to the
     * catalogue.
     *
     * @param view    the run
     * @param options the catalogue per fixture and option label, changed in place
     * @return the counts of the run per class
     */
    static ObjectNode auditRun(RunView view, Map<String, TreeMap<String, ObjectNode>> options) {
        var fixture = view.run().path("fixture").asText();
        var hint = QuestionFixtures.hint(fixture);
        var reply = view.run().path("reply").asText();
        var parts = new HashMap<String, JsonNode>();
        var counts = QuestionStub.JSON.createObjectNode();
        for (var line : view.records()) {
            var name = line.path("line").asText();
            if (ASKED.equals(name)) {
                var ids = line.path("question_ids");
                var envelopeParts = line.path("envelope").path("parts");
                for (var index = 0; index < envelopeParts.size(); index++) {
                    var part = envelopeParts.get(index);
                    parts.put(ids.path(index).asText(), part);
                    hint.ifPresent(value -> catalogue(options, fixture, part, value));
                }
            } else if ("answered".equals(name)) {
                for (var item : line.path("items")) {
                    var part = parts.get(item.path(QUESTION_ID_FIELD).asText());
                    var answerClass = answerClass(line.path("channel").asText(), item, part, hint);
                    counts.put(answerClass, counts.path(answerClass).asInt() + 1);
                    if ("unclear".equals(answerClass)) {
                        var unclear = counts.get("unclear_labels") instanceof ArrayNode existing ? existing
                                : counts.putArray("unclear_labels");
                        unclear.add(item.path("label").asText());
                    }
                    if (!"quorum".equals(answerClass)) {
                        counts.put("operator_answers", counts.path("operator_answers").asInt() + 1);
                        if (recomposedFallback(reply, part, hint)) {
                            counts.put("recomposed_fallback_own", counts.path("recomposed_fallback_own").asInt() + 1);
                        }
                    }
                }
            }
        }
        return counts;
    }

    /**
     * @param channel the channel of the answer ({@code cli} for the driver's, {@code quorum})
     * @param item    the recorded answer item ({@code branch_key}, {@code kind})
     * @param part    the part it answers, {@code null} when its record is missing
     * @param hint    the fixture's consistency hint
     * @return {@code quorum}, {@code own}, {@code unhinted}, {@code consistent}, {@value #CONTRADICTING} or
     *         {@code unclear}
     */
    static String answerClass(String channel, JsonNode item, JsonNode part,
            Optional<QuestionFixtures.ConsistencyHint> hint) {
        if (!"cli".equals(channel)) {
            return "quorum";
        }
        var branch = item.path("branch_key").asText();
        if ("own".equals(branch) || "own_option".equals(item.path("kind").asText())) {
            return "own";
        }
        if (hint.isEmpty()) {
            return "unhinted";
        }
        JsonNode chosen = null;
        if (part != null) {
            for (var option : part.path("options")) {
                if (branch.equals(option.path("branch_key").asText())) {
                    chosen = option;
                }
            }
        }
        if (chosen == null) {
            return "unclear";
        }
        return switch (hint.get().classify(chosen)) {
            case CONSISTENT -> "consistent";
            case CONTRADICTING -> CONTRADICTING;
            case UNCLEAR -> "unclear";
        };
    }

    private static boolean recomposedFallback(String reply, JsonNode part,
            Optional<QuestionFixtures.ConsistencyHint> hint) {
        if (part == null || hint.isEmpty() || part.path("options").isEmpty()
                || !Set.of("option", "default").contains(reply)) {
            return false;
        }
        var recommended = part.path("recommendation").path("branch_key").asText("own");
        return QuestionFixtures.consistentChoice(part, hint.get(), recommended, "default".equals(reply)).isEmpty();
    }

    private static void catalogue(Map<String, TreeMap<String, ObjectNode>> options, String fixture, JsonNode part,
            QuestionFixtures.ConsistencyHint hint) {
        var recommended = part.path("recommendation").path("branch_key").asText("");
        for (var option : part.path("options")) {
            var label = option.path("label").asText();
            var node = options.computeIfAbsent(fixture, _ -> new TreeMap<>()).computeIfAbsent(label, _ -> {
                var created = QuestionStub.JSON.createObjectNode();
                created.put("label", label);
                created.put("description", option.path("description").asText());
                created.put("class", hint.classify(option).name().toLowerCase(Locale.ROOT));
                created.put("choosable", hint.consistent(option));
                created.put("offered", 0);
                created.put("recommended", 0);
                return created;
            });
            node.put("offered", node.path("offered").asInt() + 1);
            if (recommended.equals(option.path("branch_key").asText())) {
                node.put("recommended", node.path("recommended").asInt() + 1);
            }
        }
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
