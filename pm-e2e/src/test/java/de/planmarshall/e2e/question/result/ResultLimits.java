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

import static de.planmarshall.e2e.question.result.QuestionResults.HOST;
import static de.planmarshall.e2e.question.result.QuestionResults.INVOCATION;
import static de.planmarshall.e2e.question.result.QuestionResults.RUN_TIMEOUT;
import static de.planmarshall.e2e.question.result.QuestionResults.SETUP_STOPPED_RUNS;
import static de.planmarshall.e2e.question.result.RunView.runId;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.question.driver.Fault;
import de.planmarshall.e2e.question.driver.Host;
import de.planmarshall.e2e.question.driver.QuestionDriver;
import de.planmarshall.e2e.question.fixture.QuestionFixtures;
import de.planmarshall.e2e.question.result.RunReadings.ContinuationExit;

/**
 * The limits of a result: what each figure does and does not show, stated with the runs it concerns. The limits come
 * from the union of the invocations a result aggregates, so a later invocation without a host does not drop the limit
 * of an earlier one with it. No limit changes a verdict.
 */
public final class ResultLimits {

    /** The field of a result with its limits. */
    public static final String LIMITS = "limits";
    /** The limit of R2: the age of 24 hours is not run (operator decision: 1 h at most). */
    public static final String R2_NO_DAY_AGE = "the age 24 hours was not run, operator decision: R2 runs 1 hour at "
            + "most";
    /** The limit of R3 on a survived call cut (the changed criterion). */
    public static final String CUT_SURVIVED_LIMIT = "a call cut that the worker survives by waiting again is no lost "
            + "continuation: under the changed criterion it passes; the profile's cut setting stays";
    private static final String NOT_RECORDED = "not recorded";
    private static final String OPENCODE = "opencode";

    private ResultLimits() {
    }

    /**
     * Appends one limit to a result's {@value #LIMITS}.
     *
     * @param result the result
     * @param text   the limit
     */
    public static void limit(ObjectNode result, String text) {
        var limits = result.get(LIMITS) instanceof ArrayNode array ? array : result.putArray(LIMITS);
        limits.add(text);
    }

    /**
     * Adds every limit of an item's result.
     *
     * @param result       the result, its groups evaluated
     * @param item         the item
     * @param runs         its run records
     * @param contributing the invocations that contributed run records, by id
     * @param unused       the count of unused scenario recipes (R4)
     * @throws IOException if a run's files cannot be read
     */
    public static void addAll(ObjectNode result, String item, List<JsonNode> runs, Map<String, JsonNode> contributing,
            int unused) throws IOException {
        if ("R4".equals(item)) {
            limitsOfR4(result, unused);
        }
        limitsOfFixtures(result, item);
        var openCodeModels = openCodeModels(runs, contributing);
        if (!openCodeModels.isEmpty()) {
            limitOfOpenCodeDataHome(result, openCodeModels);
        }
        limitOfRunLimits(result, runs, contributing);
        limitOfContinuationEarlyExits(result, runs);
        limitOfContinuationsByHold(result, runs);
        limitOfQuotaErrors(result, runs);
        limitOfUsageLimit(result, runs, contributing.values());
        limitOfSetupFailures(result, runs, contributing.values());
        limitOfMissingEvidence(result, runs);
        limitOfAnswerFallbacks(result, runs);
        limitOfContinuationGaps(result, runs);
        limitOfReferenceForm(result, item, runs);
        switch (item) {
            case "Q3" -> limitOfQ3Times(result);
            case "Q4" -> limitOfQ4CrossHost(result, runs);
            case "Q5" -> limitOfQ5Bound(result);
            case "Q6" -> limitOfQ6Votes(result, runs);
            case "Q7" -> limitOfQ7(result, runs);
            case "R2" -> limitOfR2Ages(result, runs, contributing.values());
            case "R3" -> {
                limitsOfR3(result, runs);
                limitOfR3FaultSelection(result, runs, contributing.values());
            }
            default -> {
                // no limit of its own
            }
        }
    }

    private static String namedRuns(Map<String, List<String>> perHost) {
        return String.join("; ", perHost.entrySet().stream().map(entry -> entry.getKey() + " "
                + entry.getValue().size() + " (" + String.join(", ", entry.getValue()) + ")").toList());
    }

    /**
     * Adds the limit of the fixture wording to the result of an item whose fixtures force the ask: the forcing
     * sentences, the closed question introduced as the one to submit after the answer, and the evidence rule of the
     * contract; for Q2 also that its controls stay unforced, for R4 that its large work is forced as the loop tasks are.
     *
     * @param result the result
     * @param item   the item
     */
    public static void limitsOfFixtures(ObjectNode result, String item) {
        if (!QuestionResults.FORCED_ITEMS.contains(item)) {
            return;
        }
        limit(result, "Asking is forced by the fixture text, added before any measured run. The apply-answer task "
                + "says: \"" + QuestionFixtures.FORCE_ASK + "\" Its envelope plan task TASK-001 says: \""
                + QuestionFixtures.FORCE_ASK_PLAN_TASK + "\" The two-flaw task of Q5 says: \""
                + QuestionFixtures.FORCE_ASK_EACH + "\" Before the ask the facts do not claim that the operator has "
                + "answered: the closed question begins \"" + QuestionFixtures.CHECK_AFTER_ANSWER + "\" instead of the "
                + "corpus's \"" + QuestionFixtures.ANSWERED_FRAMING.strip() + "\"");
        limit(result, "The worker's contract states the evidence rule of the decision envelope, added before any "
                + "measured run: \"" + QuestionDriver.EVIDENCE_RULE + "\" Its example envelope carries the evidence "
                + "item " + QuestionDriver.EVIDENCE_EXAMPLE + ".");
        if ("Q2".equals(item)) {
            limit(result, "The controls of Q2 keep their original, unforced task text: no forcing sentence.");
        }
        if ("R4".equals(item)) {
            limit(result, "The large-work tasks of R4 (" + String.join(" and ", QuestionFixtures.LARGE_SCENARIOS)
                    + ") carry the forcing text of the apply-answer task as the loop tasks do (operator decision "
                    + "2026-10-31); a run that did not ask counts as no_ask, not as a pass.");
        }
    }

    /**
     * Adds the limits of R4: which scenarios large work uses, that R4 has no criterion, and that the corpus items
     * behind the scenarios are drafts.
     *
     * @param result the result of R4
     * @param unused how many scenario recipes on the telemetry branch were not used
     */
    public static void limitsOfR4(ObjectNode result, int unused) {
        limit(result, "Small work is the probe; large work only the scenarios "
                + String.join(" and ", QuestionFixtures.LARGE_SCENARIOS) + ". The other " + unused + " scenario "
                + "recipes are not used: they need tasks replayed from records whose commits are gone, or outside "
                + "state that has changed.");
        limit(result, "R4 measures hold, resume and fallback for small and large work, large work last; what large "
                + "work did not reach within the budget is recorded as not run. R4 has no pass criterion; its figures "
                + "are tokens and time, and the answer check of the large scenarios is secondary.");
        limit(result, "Both corpus items behind the large scenarios are drafts.");
    }

    private static Set<String> openCodeModels(List<JsonNode> runs, Map<String, JsonNode> contributing) {
        var models = new HashSet<String>();
        for (var entry : contributing.entrySet()) {
            var invocation = entry.getValue();
            if (!"global".equals(invocation.path("opencode_data_home").asText())) {
                continue;
            }
            var own = runs.stream().filter(run -> entry.getKey().equals(QuestionResults.invocationOf(run))).toList();
            var startsOpenCode = own.stream().anyMatch(run -> OPENCODE.equals(run.path(HOST).asText())
                    || OPENCODE.equals(run.path("continue_host").asText()) || "vote".equals(run.path("kind").asText()));
            if (startsOpenCode) {
                own.stream().filter(run -> OPENCODE.equals(run.path(HOST).asText())).map(RunView::model)
                        .forEach(models::add);
            }
        }
        return models;
    }

    /**
     * Adds the limit of OpenCode on the operator's global data home.
     *
     * @param result the result
     * @param models the OpenCode models of the runs
     */
    public static void limitOfOpenCodeDataHome(ObjectNode result, Collection<String> models) {
        limit(result, "OpenCode ran on the global data home with " + String.join(", ", new TreeSet<>(models))
                + ", a departure from the per-worker data home of the package, accepted by the operator. Its runs ran "
                + "one at a time, because every OpenCode worker writes the one SQLite database of that data home.");
    }

    /**
     * Adds the run limits when the invocations ran with different ones, or did not record theirs.
     *
     * @param result       the result
     * @param runs         its run records
     * @param contributing the recorded invocations that contributed them, by id
     */
    public static void limitOfRunLimits(ObjectNode result, List<JsonNode> runs, Map<String, JsonNode> contributing) {
        var perHost = new TreeMap<String, Set<String>>();
        var values = new TreeSet<String>();
        for (var run : runs) {
            var invocation = contributing.get(QuestionResults.invocationOf(run));
            var value = invocation == null ? NOT_RECORDED : invocation.path(RUN_TIMEOUT).asText(NOT_RECORDED);
            perHost.computeIfAbsent(run.path(HOST).asText(), _ -> new TreeSet<>()).add(value);
            values.add(value);
        }
        if (values.size() < 2 && !values.contains(NOT_RECORDED)) {
            return;
        }
        var named = perHost.entrySet().stream().map(entry -> entry.getKey() + " " + String.join(" and ",
                entry.getValue())).toList();
        limit(result, values.size() < 2 ? "The run limit of the invocations is not recorded."
                : "The invocations ran with different run limits (pm.experiment.run-timeout; a run waits that long "
                + "plus four times its hold): " + String.join(", ", named) + ".");
    }

    /**
     * Adds a limit when runs have a continuation generation that connected and exited without a call: they count as
     * {@value QuestionDriver#CONTINUATION_EARLY_EXIT}, not as early_exit or timed_out, and where a group's verdict
     * without them reads differently it is reported beside the verdict.
     *
     * @param result the result, its groups evaluated
     * @param runs   its run records
     * @throws IOException if a run's files cannot be read
     */
    public static void limitOfContinuationEarlyExits(ObjectNode result, List<JsonNode> runs) throws IOException {
        var counted = new TreeMap<String, List<String>>();
        for (var run : runs) {
            var view = RunView.read(run);
            if (!RunReadings.quotaError(view) && RunReadings.continuationExit(view) == ContinuationExit.EXITED) {
                counted.computeIfAbsent(run.path(HOST).asText(), _ -> new ArrayList<>()).add(runId(run));
            }
        }
        if (counted.isEmpty()) {
            return;
        }
        var differing = new ArrayList<String>();
        result.path(QuestionResults.GROUPS).forEach(group -> {
            if (group.has(QuestionResults.VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT)) {
                differing.add(group.path(QuestionResults.GROUP).asText());
            }
        });
        limit(result, "A continuation generation (a generation of the asking worker after the one that asked) that "
                + "connected and exited without a single call to the stub counts as continuation_early_exit, not as "
                + "early_exit and not as timed_out; its run keeps its ask, counts in continued and not in submitted, "
                + "and the driver ends the wait at once on such an exit: " + namedRuns(counted) + ". The verdict "
                + "reads these runs as any other; " + (differing.isEmpty()
                ? "no group's verdict reads differently without them."
                : QuestionResults.VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT + " is the verdict without them, an "
                + "additional figure, where it reads differently: " + String.join("; ", differing)
                + "."));
    }

    /**
     * Adds a limit naming the runs whose continuation was made by hold within the decision
     * ({@value QuestionDriver#CONTINUED_BY_HOLD}): the asking worker took the continuing task over its held
     * {@code pm_wait}, so no generation was started for it.
     *
     * @param result the result
     * @param runs   its run records
     */
    public static void limitOfContinuationsByHold(ObjectNode result, List<JsonNode> runs) {
        var affected = byHost(runs, run -> run.path(QuestionDriver.CONTINUED_BY_HOLD).asBoolean());
        if (affected.isEmpty()) {
            return;
        }
        limit(result, "In these runs the decision was answered while the asking worker still held pm_wait, and the "
                + "stub offered the continuing task to that generation within the hold; the driver started no new "
                + "generation for it and recorded it as " + QuestionDriver.CONTINUED_BY_HOLD + ". Such a run counts "
                + "in continued and, with its submission, in submitted, as every run: " + namedRuns(affected) + ".");
    }

    /**
     * Adds a limit naming the runs whose harness answered with a quota or limit error: they count as
     * {@value QuestionDriver#QUOTA_ERROR}, not as timed out, early exit or no ask, and
     * {@value QuestionResults#VERDICT_WITHOUT_QUOTA_ERROR} is the verdict without them.
     *
     * @param result the result
     * @param runs   its run records
     * @throws IOException if a run's files cannot be read
     */
    public static void limitOfQuotaErrors(ObjectNode result, List<JsonNode> runs) throws IOException {
        var affected = new ArrayList<String>();
        for (var run : runs) {
            if (!RunView.notRun(run) && RunReadings.quotaError(RunView.read(run))) {
                affected.add(runId(run));
            }
        }
        if (!affected.isEmpty()) {
            limit(result, "Runs whose harness answered with a quota or limit error (the text 'quota' in the harness's "
                    + "own error output: its stderr without pm-exec's lines, and the error lines of its structured "
                    + "output, never a tool's input or result, assistant text or a file the worker read) count as "
                    + "quota_error, not as timed_out, early_exit or no_ask. The verdict reads them as any other run; "
                    + QuestionResults.VERDICT_WITHOUT_QUOTA_ERROR + " is the verdict without them, an additional "
                    + "figure: " + String.join(", ", affected) + ".");
        }
    }

    /**
     * Adds a limit naming the runs the account's usage limit refused ({@value RunView#NOT_RUN},
     * {@value QuestionDriver#RATE_LIMIT_EVENT}) with the reset time their refusal named, and the runs their invocations
     * did not start afterwards ({@value QuestionResults#USAGE_LIMIT_STOPPED_RUNS}). Such a run is not run: it is in
     * no figure and no verdict, never a failed run or a failed item, and its group runs again once the account may run.
     *
     * @param result       the result
     * @param runs         its run records
     * @param contributing the invocations that contributed run records
     */
    public static void limitOfUsageLimit(ObjectNode result, List<JsonNode> runs, Collection<JsonNode> contributing) {
        var refused = new ArrayList<String>();
        for (var run : runs) {
            if (RunView.notRun(run)) {
                var resets = run.path(QuestionDriver.RESETS_AT).asText("");
                refused.add(runId(run) + " (resets " + (resets.isEmpty() ? "not named" : resets) + ")");
            }
        }
        var stopped = new ArrayList<String>();
        for (var invocation : contributing) {
            var count = invocation.path(QuestionResults.USAGE_LIMIT_STOPPED_RUNS).asInt(0);
            if (count > 0) {
                stopped.add(invocation.path(INVOCATION).asText() + " " + count);
            }
        }
        if (refused.isEmpty() && stopped.isEmpty()) {
            return;
        }
        var text = new StringBuilder("A run the account's usage limit refused (a rate_limit_event of Claude Code whose "
                + "status is neither allowed nor allowed_warning) is not run: it counts as " + RunView.NOT_RUN
                + " only, is in no figure and no verdict of its group, and is never a failed run or a failed item; its "
                + "group runs again once the account may run");
        text.append(refused.isEmpty() ? "." : ": " + String.join("; ", refused) + ".");
        text.append(" A refusal stops the invocation's remaining runs");
        text.append(stopped.isEmpty() ? "." : ", not run and not counted as missing: " + String.join(", ", stopped)
                + ".");
        limit(result, text.toString());
    }

    /**
     * Adds a limit naming the runs whose fixture or workspace setup failed before any worker launched, with their
     * reasons, and the runs their invocations did not start because of it.
     *
     * @param result       the result
     * @param runs         its run records
     * @param contributing the invocations that contributed run records
     */
    public static void limitOfSetupFailures(ObjectNode result, List<JsonNode> runs, Collection<JsonNode> contributing) {
        var failed = new ArrayList<String>();
        for (var run : runs) {
            if (RunView.setupFailed(run)) {
                failed.add(runId(run) + " (" + run.path("fixture").asText() + ": " + RunView.setupFailedReason(run)
                        + ")");
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
                + "as " + RunView.SETUP_FAILED + " only, not as no_ask, timed_out or launched, and is in no figure and "
                + "no verdict of its group; its reason is the first line of git's output");
        text.append(failed.isEmpty() ? "." : ": " + String.join("; ", failed) + ".");
        text.append(" A failed setup stops the invocation's remaining runs of that scenario");
        text.append(stopped.isEmpty() ? "." : ", not run and not counted as missing: " + String.join(", ", stopped)
                + ".");
        limit(result, text.toString());
    }

    /**
     * Adds a limit naming the runs whose stub log is missing, per host: they count as
     * {@value QuestionResults#EVIDENCE_MISSING} only, never as {@code no_ask}.
     *
     * @param result the result
     * @param runs   its run records
     */
    public static void limitOfMissingEvidence(ObjectNode result, List<JsonNode> runs) {
        var missing = byHost(runs, RunView::evidenceMissing);
        if (missing.isEmpty()) {
            return;
        }
        limit(result, "The stub log of these runs is missing: the stub directory their record names, or its "
                + "events.jsonl, no longer exists. They count as " + QuestionResults.EVIDENCE_MISSING + " only, not as "
                + "no_ask, timed_out, early_exit, quota_error or in any other column, and their figures are not "
                + "measured. The verdict reads them as runs without a single stub event, so they count against their "
                + "group; " + QuestionResults.VERDICT_EVIDENCE + " marks each verdict judged so: " + namedRuns(missing)
                + ".");
    }

    /**
     * Adds a limit naming the runs whose operator answer fell back to the operator's own option
     * ({@value AnswerAudit#ANSWER_FALLBACK_OWN}).
     *
     * @param result the result
     * @param runs   its run records
     */
    public static void limitOfAnswerFallbacks(ObjectNode result, List<JsonNode> runs) {
        var composed = runs.stream().filter(run -> run.has(AnswerAudit.ANSWER_COMPOSITIONS)).count();
        if (composed == 0) {
            return;
        }
        var fallbacks = byHost(runs, run -> run.path(AnswerAudit.ANSWER_FALLBACK_OWN).asBoolean());
        limit(result, "An operator answer never pairs a branch with a reply text that contradicts it: on a fixture "
                + "with a consistency hint an answered option takes the asker's recommended option where it reads as "
                + "the fixture's reference direction, else the first option that does, and a delegated answer only a "
                + "recommendation that does; where none does, the reply goes as the operator's own option ("
                + AnswerAudit.ANSWER_FALLBACK_OWN + "). " + composed + " of " + runs.size() + " records carry "
                + AnswerAudit.ANSWER_COMPOSITIONS + (fallbacks.isEmpty() ? "; no answer fell back."
                : "; fell back: " + namedRuns(fallbacks) + "."));
    }

    /**
     * Adds a limit naming the runs whose continuation got no worker ({@value QuestionDriver#CONTINUATION_GAP}), and the
     * runs whose warm asker exited after its ask ({@value QuestionDriver#WARM_ASKER_EXITED}), whose continuation the
     * driver started in a new generation.
     *
     * @param result the result
     * @param runs   its run records
     */
    public static void limitOfContinuationGaps(ObjectNode result, List<JsonNode> runs) {
        var gaps = new TreeMap<String, List<String>>();
        for (var run : runs) {
            if (run.path(QuestionDriver.CONTINUATION_GAP).isTextual()) {
                gaps.computeIfAbsent(run.path(HOST).asText(), _ -> new ArrayList<>()).add(runId(run) + " ("
                        + run.path(QuestionDriver.CONTINUATION_GAP).asText() + ")");
            }
        }
        var exited = byHost(runs, run -> run.path(QuestionDriver.WARM_ASKER_EXITED).asBoolean());
        if (gaps.isEmpty() && exited.isEmpty()) {
            return;
        }
        var text = new StringBuilder("A warm asker is expected to take its continuation over pm_wait; where it exited "
                + "after its ask instead, the driver starts a generation leased on the queued continuation ("
                + QuestionDriver.WARM_ASKER_EXITED + "), and a continuation that gets no worker is recorded with its "
                + "reason (" + QuestionDriver.CONTINUATION_GAP + ").");
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
     * Adds a limit naming the runs of Q4, Q6 and R4 that follow the reference only through the accepted reference form
     * ({@link RunReadings#REFERENCE_FORM}, the task-2 decision on Q4).
     *
     * @param result the result
     * @param item   the item
     * @param runs   its run records
     * @throws IOException if a run's files cannot be read
     */
    public static void limitOfReferenceForm(ObjectNode result, String item, List<JsonNode> runs) throws IOException {
        if (!Set.of("Q4", "Q6", "R4").contains(item)) {
            return;
        }
        var form = new TreeMap<String, List<String>>();
        for (var run : runs) {
            if (RunReadings.referenceForm(RunView.read(run))) {
                form.computeIfAbsent(run.path(HOST).asText(), _ -> new ArrayList<>()).add(runId(run));
            }
        }
        limit(result, "Whether a run follows the reference is read from its last submission: its answer must equal "
                + "the reference after trimming, lower case, outer quotes and runs of white space, or state it as the "
                + "value of a call (f(7) == [...] for the bare list), the reference form accepted for Q4"
                + (form.isEmpty() ? "; no run used that form." : "; runs that used it: " + namedRuns(form) + "."));
    }

    /**
     * Adds the limit of the time figures of Q3.
     *
     * @param result the result of Q3
     */
    public static void limitOfQ3Times(ObjectNode result) {
        limit(result, "ask_to_stop_event_ms is the time from the ask to the event the verdict reads as the stop: for a "
                + "warm worker its next wait_started, for a fresh worker the exit of the asking generation's process. "
                + "A fresh worker that kept calling pm_wait after the ask did not end its turn; for such a run the "
                + "figure is a silence-limit wait, not the time to the end of the turn. ask_to_next_pm_wait_ms is the "
                + "time to the asking generation's first pm_wait after the ask, ask_to_exit_ms the time to its process "
                + "exit, and " + QuestionResults.VERDICT_BY_NEXT_PM_WAIT + " reads the next pm_wait or an exit before "
                + "the answer as the stop, an additional figure.");
    }

    /**
     * Adds the limit of Q4's continuation on another host: it is no group of its own but a figure of each group.
     *
     * @param result the result of Q4
     * @param runs   its run records
     */
    public static void limitOfQ4CrossHost(ObjectNode result, List<JsonNode> runs) {
        var hosts = runs.stream().filter(RunReadings::crossHost)
                .map(run -> run.path(HOST).asText() + "->" + run.path("continue_host").asText())
                .collect(Collectors.toCollection(TreeSet::new));
        if (hosts.isEmpty()) {
            return;
        }
        limit(result, "The continuation on another host (pm.experiment.q4.other-host) is no group of its own: "
                + QuestionResults.CROSS_HOST_RUNS + " reports a group's runs whose continuing task ran on another "
                + "host than the asker's (" + String.join(", ", hosts) + ").");
    }

    /**
     * Adds the limit of Q5's bound: {@code bound_reached} is read from the stub log, and it decides whether the next
     * bound runs.
     *
     * @param result the result of Q5
     */
    public static void limitOfQ5Bound(ObjectNode result) {
        limit(result, "bound_reached counts a group's runs whose asks reached their question bound, read from the "
                + "stub log. The bounds run sequentially: " + Scale.Q5_FIRST_BOUND + " first, the next one only where "
                + "a run of the bound before reached it (" + QuestionResults.Q5_BOUNDS + ").");
    }

    /**
     * Adds the limit of Q6's votes: how the stub decides a vote whose voter is missing, and the runs in which a voter
     * exited without a call.
     *
     * @param result the result of Q6
     * @param runs   its run records
     * @throws IOException if a run's files cannot be read
     */
    public static void limitOfQ6Votes(ObjectNode result, List<JsonNode> runs) throws IOException {
        var affected = new ArrayList<String>();
        for (var run : runs) {
            if (RunReadings.voterEarlyExit(RunView.read(run))) {
                affected.add(runId(run));
            }
        }
        limit(result, "A vote task is served by one voter and never offered again. When a voter's process exits "
                + "without a vote, its vote task closes lost at once and a listed role without a vote escalates the "
                + "set with the reason role_unavailable; the driver then answers the decision as the operator. Such a "
                + "vote counts in votes and not in valid_votes, and its run keeps its ask"
                + (affected.isEmpty() ? "." : ": " + String.join(", ", affected) + "."));
    }

    /**
     * Adds the limit of Q7: what its open time is and how {@value QuestionResults#LEASES_WHILE_OPEN} counts a held
     * wait, and the runs whose asker the driver ended or whose open time cannot be read.
     *
     * @param result the result of Q7
     * @param runs   its run records
     * @throws IOException if a run's files cannot be read
     */
    public static void limitOfQ7(ObjectNode result, List<JsonNode> runs) throws IOException {
        var ended = new ArrayList<String>();
        var unavailable = new ArrayList<String>();
        for (var run : runs) {
            var figures = QuestionResults.q7Run(RunView.read(run));
            if (figures.path(QuestionDriver.ASKER_ENDED_BY_DRIVER).asBoolean()) {
                ended.add(runId(run));
            }
            if (!figures.path(QuestionResults.HELD_WAIT_MS).isNumber()) {
                unavailable.add(runId(run));
            }
        }
        var text = new StringBuilder("The open time of a run starts when the asking generation has stopped ("
                + QuestionDriver.OPEN_TIME_STARTED + ") and ends with the answer (answer_recorded). "
                + QuestionResults.LEASES_WHILE_OPEN + " counts the offers and deliveries of the entry's tasks between "
                + "the ask and the answer, and every generation other than the warm worker that held a pm_wait in the "
                + "open time: a held wait is a held slot. The fresh asker that has not ended its turn at its silence "
                + "limit is ended by the driver before the open time starts ("
                + QuestionDriver.ASKER_ENDED_BY_DRIVER + "), a harness action; a model that does not end its turn "
                + "after the ask is a finding of the run, not of the server.");
        if (!ended.isEmpty()) {
            text.append(" The driver ended the asker in ").append(String.join(", ", ended)).append('.');
        }
        if (!unavailable.isEmpty()) {
            text.append(" The open time cannot be read from the stub log of ").append(String.join(", ", unavailable))
                    .append(": its held figures are ").append(QuestionResults.UNAVAILABLE).append('.');
        }
        limit(result, text.toString());
    }

    /**
     * Adds the limit of R2's ages ({@value #R2_NO_DAY_AGE}) when the age of 24 hours was not run.
     *
     * @param result       the result of R2
     * @param runs         its run records
     * @param contributing the invocations that contributed run records
     */
    public static void limitOfR2Ages(ObjectNode result, List<JsonNode> runs, Collection<JsonNode> contributing) {
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
     * Adds the limits of R3: how the stop and the call cut are produced and how many runs had them as specified, the
     * changed criterion of a survived cut, the losses the stub saw first, and that the stub does not react to a
     * worker's process exit.
     *
     * @param result the result of R3
     * @param runs   its run records
     */
    public static void limitsOfR3(ObjectNode result, List<JsonNode> runs) {
        var stops = runs.stream().filter(run -> Fault.STOP_BEFORE_ANSWER.name().equals(run.path("fault").asText()))
                .toList();
        limit(result, "The stop before the answer (STOP_BEFORE_ANSWER) is injected once the stub shows the asker "
                + "holding pm_wait after its ask, before the answer is sent; an asker that never holds is not stopped "
                + "(fault_not_injected). Stopped while holding: " + stops.stream()
                .filter(run -> run.path("fault_held").asBoolean()).count() + " of " + stops.size() + " runs.");
        var cuts = new TreeMap<String, long[]>();
        runs.stream().filter(run -> Fault.CALL_CUT.name().equals(run.path("fault").asText())).forEach(run -> {
            var counts = cuts.computeIfAbsent(run.path(HOST).asText(), _ -> new long[2]);
            counts[0] += run.path(QuestionResults.CALL_CUT_FIELD).asBoolean() ? 1 : 0;
            counts[1]++;
        });
        var perHost = new ArrayList<String>();
        cuts.forEach((host, counts) -> perHost.add(host + " " + counts[0] + " of " + counts[1]));
        limit(result, "The call cut (CALL_CUT) is made by the host and never simulated: the held pm_wait gets no "
                + "progress frame, and the operator answers " + Host.CALL_CUT_MARGIN.toSeconds() + " s after the "
                + "host's cut. Claude Code: MCP_TOOL_TIMEOUT of " + Host.CALL_CUT_TOOL_TIMEOUT.toSeconds() + " s. "
                + "OpenCode: the MCP server's timeout of " + Host.CALL_CUT_TOOL_TIMEOUT.toSeconds() + " s. "
                + "Antigravity: its " + Host.ANTIGRAVITY_CALL_LIMIT.toSeconds() + " s limit on every call. A cut "
                + "counts only where the relay's host-side log or the stub log shows it. Cut: "
                + (perHost.isEmpty() ? "no run" : String.join(", ", perHost)) + ".");
        if (!cuts.isEmpty()) {
            limit(result, CUT_SURVIVED_LIMIT + ". Each CALL_CUT group reports " + QuestionResults.CUT_BY_HOST + ", "
                    + QuestionResults.CUT_SURVIVED + " and " + QuestionResults.RE_WAIT_AFTER_CUT_MS + ".");
        }
        var losses = runs.stream().filter(run -> run.has("loss_by")).toList();
        limit(result, "In " + losses.stream().filter(run -> "stub".equals(run.path("loss_first_by").asText())).count()
                + " of " + losses.size() + " runs with a detected loss the stub had already released the task at the "
                + "worker's exit, before the acknowledgement deadline.");
        limit(result, "The stub did not react to a worker's process exit in R3 (" + QuestionResults.STUB_LOSE_ON_EXIT
                + " false): it logged the exit as an observation and closed, released or continued no task at it, so "
                + "the task stayed leased until the driver detected the missing answer acknowledgement at the host's "
                + "deadline. Exit detection itself was measured in Part A. Where the answer never reached a generation "
                + "whose process had exited, the driver ended it after its delivery wait ("
                + QuestionDriver.ANSWER_UNDELIVERED + "), no detection. Runs with this stub: " + runs.stream()
                .filter(run -> run.has(QuestionResults.STUB_LOSE_ON_EXIT)
                        && !run.path(QuestionResults.STUB_LOSE_ON_EXIT).asBoolean(true))
                .count() + " of " + runs.size() + ".");
    }

    /**
     * Adds the limit of an R3 result whose invocations measured only some faults ({@value QuestionResults#R3_FAULTS}):
     * the item verdict is then a verdict over the faults measured.
     *
     * @param result       the result of R3, its groups evaluated
     * @param runs         its run records
     * @param contributing the invocations that contributed run records
     */
    public static void limitOfR3FaultSelection(ObjectNode result, List<JsonNode> runs,
            Collection<JsonNode> contributing) {
        var selections = new ArrayList<String>();
        for (var invocation : contributing) {
            var faults = invocation.path(QuestionResults.R3_FAULTS);
            if (faults.isArray() && faults.size() < Fault.values().length) {
                var names = new ArrayList<String>();
                faults.forEach(fault -> names.add(fault.asText()));
                selections.add("only the faults " + String.join(", ", names) + " were measured by invocation "
                        + invocation.path(INVOCATION).asText());
            }
        }
        if (selections.isEmpty()) {
            return;
        }
        var measured = runs.stream().map(run -> run.path("fault").asText()).collect(Collectors.toSet());
        var absent = Arrays.stream(Fault.values()).map(Fault::name).filter(name -> !measured.contains(name)).toList();
        var present = Arrays.stream(Fault.values()).map(Fault::name).filter(measured::contains).toList();
        var text = new StringBuilder("The faults of an R3 invocation are selected by " + Fault.PROPERTY + ": ")
                .append(String.join("; ", selections)).append(". The item verdict is computed from the groups "
                + "present");
        if (absent.isEmpty()) {
            text.append(", and every fault has a group from the aggregated invocations.");
        } else {
            text.append("; no group of ").append(String.join(", ", absent)).append(" is in this result, so the item "
                    + "verdict is a verdict over the faults ").append(String.join(", ", present))
                    .append(" only, not a verdict of the whole item.");
        }
        limit(result, text.toString());
    }

    private static TreeMap<String, List<String>> byHost(List<JsonNode> runs, Predicate<JsonNode> predicate) {
        var affected = new TreeMap<String, List<String>>();
        for (var run : runs) {
            if (predicate.test(run)) {
                affected.computeIfAbsent(run.path(HOST).asText(), _ -> new ArrayList<>()).add(runId(run));
            }
        }
        return affected;
    }
}
