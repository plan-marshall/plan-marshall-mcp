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

import static de.planmarshall.e2e.question.result.RunView.EVENT;
import static de.planmarshall.e2e.question.result.RunView.GENERATION;
import static de.planmarshall.e2e.question.result.RunView.WORKER;
import static de.planmarshall.e2e.question.result.RunView.normalize;
import static de.planmarshall.e2e.question.result.RunView.same;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import de.planmarshall.e2e.question.driver.Fault;
import de.planmarshall.e2e.question.driver.Host;
import de.planmarshall.e2e.question.driver.QuestionDriver;
import de.planmarshall.e2e.question.fixture.QuestionFixtures;
import de.planmarshall.e2e.question.stub.QuestionStub;

/**
 * What one run shows, read from its stub log, its question records and its worker outputs ({@link RunView}): how its
 * generations ended, whether its last submission follows the reference, whether its worker read the canary again, its
 * time, tokens and final text, and the readings of R2 and R3. No reading takes what a model reports about itself.
 */
public final class RunReadings {

    /** The exit codes of a process the driver ended ({@code SIGTERM}, {@code SIGKILL}): no exit of its own. */
    public static final Set<Integer> TERMINATED_EXITS = Set.of(143, 137);
    /** The tools of the stub: a call of one of them is no read of a file of the worktree (R1). */
    public static final Set<String> STUB_TOOLS = Set.copyOf(QuestionStub.TOOLS);
    /** How long a resume of a missing or over-age session may take before it counts as hung (R2). */
    public static final long HUNG_RESUME_MS = 60_000;
    /** A negative resume (R2) that started a new session instead of failing. */
    public static final String NEGATIVE_NEW_SESSION = "negative_new_session";
    /** A negative resume (R2) that failed at once, as the specification expects. */
    public static final String FAILED_AT_ONCE = "failed_at_once";
    /** A negative resume (R2) that neither ended nor started a new session within {@link #HUNG_RESUME_MS}. */
    public static final String HUNG = "hung";
    /** A negative resume (R2) that ended at once with exit 0 and no new session. */
    public static final String EXITED_WITHOUT_FAILURE = "exited_without_failure";

    /**
     * The accepted reference form of Q4 (Evaluation Reference, task-2 decision): a submission that states the reference
     * as the value of a call, {@code f(7) == [1, 2, 6, 24, 15, 720, 28]} for the bare list, follows the reference.
     */
    public static final Pattern REFERENCE_FORM = Pattern.compile("^[a-z_][\\w.]*\\s*\\(.*\\)\\s*==\\s*(.+)$");

    static final String ASKED = QuestionDriver.ASKED;
    static final String SUBMITTED = "submitted";
    static final String ACKNOWLEDGED = "acknowledged";
    static final String ANSWER_DELIVERED = "answer_delivered";
    static final String WORKER_STARTED = "worker_started";
    static final String WORKER_EXITED = "worker_exited";

    private RunReadings() {
    }

    /** How a run's continuation generation ended ({@link #continuationExit(RunView)}). */
    public enum ContinuationExit {
        /** No continuation generation connected and ended without a call. */
        NONE,
        /** A continuation generation connected and exited without a call, by its own exit. */
        EXITED
    }

    // --- how the generations ended ---

    /**
     * Whether a run ended early: its record says so, or a generation of a worker that did not ask (and is no voter)
     * connected and exited without a single call to the stub, was not ended by an injected fault and was not followed
     * by a newer generation of the same worker.
     *
     * @param view a run
     * @return whether it ended early
     */
    public static boolean earlyExit(RunView view) {
        return view.run().path(QuestionDriver.EARLY_EXIT).asBoolean()
                || silentExits(view).stream().anyMatch(worker -> !QuestionDriver.voter(worker));
    }

    /**
     * Whether a voter of the run (the worker of a vote task) connected and exited without a single call to the stub,
     * read as {@link #earlyExit(RunView)} reads a worker; its vote task closes {@code lost}.
     *
     * @param view a run
     * @return whether a voter of it ended early
     */
    public static boolean voterEarlyExit(RunView view) {
        return view.run().path(QuestionDriver.VOTER_EARLY_EXIT).asBoolean()
                || silentExits(view).stream().anyMatch(QuestionDriver::voter);
    }

    private static List<String> silentExits(RunView view) {
        var latest = latestGenerations(view);
        var silent = new ArrayList<String>();
        for (var exited : view.named(WORKER_EXITED)) {
            var worker = exited.path(WORKER).asText();
            var generation = exited.path(GENERATION).asInt();
            if (latest.getOrDefault(worker, generation) > generation || view.named(ASKED).stream()
                    .anyMatch(asked -> worker.equals(asked.path(WORKER).asText()))) {
                continue;
            }
            if (silentGeneration(view, worker, generation)) {
                silent.add(worker);
            }
        }
        return silent;
    }

    private static boolean silentGeneration(RunView view, String worker, int generation) {
        var own = view.events().stream().filter(event -> same(event, worker, generation))
                .map(event -> event.path(EVENT).asText()).collect(Collectors.toSet());
        return own.contains(QuestionDriver.CONNECTED) && !own.contains(QuestionDriver.CALL)
                && !own.contains(QuestionDriver.FAULT_INJECTED);
    }

    private static HashMap<String, Integer> latestGenerations(RunView view) {
        var latest = new HashMap<String, Integer>();
        view.named(WORKER_STARTED).forEach(event -> latest.merge(event.path(WORKER).asText(),
                event.path(GENERATION).asInt(), Math::max));
        return latest;
    }

    /**
     * How a run's continuation generation ended: {@link ContinuationExit#EXITED} when its record says
     * {@value QuestionDriver#CONTINUATION_EARLY_EXIT}, or when the newest generation of a worker, later than the one in
     * which that worker first asked, connected, made no call, had no fault injected and exited by itself (an exit code
     * not in {@link #TERMINATED_EXITS}); {@link ContinuationExit#NONE} otherwise, and always for the negative resume of
     * R2, which continues no task.
     *
     * @param view a run
     * @return how its continuation generation ended
     */
    public static ContinuationExit continuationExit(RunView view) {
        if (view.run().path(QuestionDriver.CONTINUATION_EARLY_EXIT).asBoolean()) {
            return ContinuationExit.EXITED;
        }
        if (view.run().path("negative").asBoolean()) {
            return ContinuationExit.NONE;
        }
        var firstAsk = new HashMap<String, Integer>();
        view.named(ASKED).forEach(event -> firstAsk.merge(event.path(WORKER).asText(), event.path(GENERATION).asInt(),
                Math::min));
        for (var entry : latestGenerations(view).entrySet()) {
            var worker = entry.getKey();
            var generation = entry.getValue();
            var asked = firstAsk.get(worker);
            if (asked == null || generation <= asked || !silentGeneration(view, worker, generation)) {
                continue;
            }
            var exitedByItself = view.named(WORKER_EXITED).stream().filter(event -> same(event, worker, generation))
                    .anyMatch(event -> !TERMINATED_EXITS.contains(event.path("exit").asInt(0)));
            if (exitedByItself) {
                return ContinuationExit.EXITED;
            }
        }
        return ContinuationExit.NONE;
    }

    /**
     * Whether a run met a quota or limit error of its harness: its record says so under the rule
     * {@value QuestionDriver#HARNESS_ERROR_OUTPUT} or {@value QuestionDriver#RATE_LIMIT_EVENT}, or the harness's own
     * error output of its workers shows one ({@link QuestionDriver#quotaDetail(String, List)}), or their structured
     * output carries a refusing {@code rate_limit_event} ({@link QuestionDriver#rateLimitRefusal(List)}).
     *
     * @param view a run
     * @return whether its harness answered with a quota error
     * @throws IOException if a worker's {@code stderr} cannot be read
     */
    public static boolean quotaError(RunView view) throws IOException {
        var rule = view.run().path(QuestionDriver.QUOTA_RULE).asText();
        if (view.run().path(QuestionDriver.QUOTA_ERROR).asBoolean() && (QuestionDriver.HARNESS_ERROR_OUTPUT.equals(rule)
                || QuestionDriver.RATE_LIMIT_EVENT.equals(rule))) {
            return true;
        }
        return QuestionDriver.quotaError("", 0, stderrOf(view), view.outputs()).isPresent();
    }

    private static String stderrOf(RunView view) throws IOException {
        var stderr = new StringBuilder();
        for (var worker : view.run().path("workers")) {
            var file = stderrFile(worker);
            if (file != null && Files.isRegularFile(file)) {
                stderr.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return stderr.toString();
    }

    private static Path stderrFile(JsonNode worker) {
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
     * @param view a run
     * @return whether its continuation got no worker: its record names the reason
     *         ({@value QuestionDriver#CONTINUATION_GAP})
     */
    public static boolean continuationNotStarted(RunView view) {
        return view.run().path(QuestionDriver.CONTINUATION_GAP).isTextual();
    }

    /**
     * @param run a run record
     * @return whether its continuing task ran on another host than the asker's (Q4)
     */
    public static boolean crossHost(JsonNode run) {
        var other = run.path("continue_host").asText("");
        return !other.isEmpty() && !other.equals(run.path("host").asText());
    }

    // --- the reference ---

    /**
     * @param view a run
     * @return the arguments of its last submission, empty without one
     */
    public static JsonNode lastSubmitArgs(RunView view) {
        var submitted = view.named(SUBMITTED);
        return submitted.isEmpty() ? QuestionStub.JSON.createObjectNode() : submitted.getLast().path("args");
    }

    /**
     * Whether a run's last submission follows the reference: its answer equals the reference after
     * {@link RunView#normalize(String)}, or states it in the accepted reference form ({@link #REFERENCE_FORM}).
     *
     * @param view a run
     * @return whether it follows the reference
     */
    public static boolean followsReference(RunView view) {
        return followsReference(view.run().path("reference").asText(""), lastSubmitArgs(view).path("answer").asText(""));
    }

    /**
     * @param reference the reference answer
     * @param answer    a submitted answer
     * @return whether the answer is the reference or states it in the accepted reference form
     */
    public static boolean followsReference(String reference, String answer) {
        var expected = normalize(reference);
        if (expected.isEmpty()) {
            return false;
        }
        var given = normalize(answer);
        if (expected.equals(given)) {
            return true;
        }
        var matcher = REFERENCE_FORM.matcher(given);
        return matcher.matches() && expected.equals(normalize(matcher.group(1)));
    }

    /**
     * @param view a run
     * @return whether its last submission follows the reference only through the accepted reference form
     */
    public static boolean referenceForm(RunView view) {
        var reference = normalize(view.run().path("reference").asText(""));
        var answer = normalize(lastSubmitArgs(view).path("answer").asText(""));
        return !reference.isEmpty() && !reference.equals(answer) && followsReference(view);
    }

    // --- Q4, Q5, Q6 ---

    /**
     * @param view a run
     * @return how many times it asked a question that an earlier answer had answered (Q4)
     */
    public static int reasked(RunView view) {
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

    /**
     * @param view a run
     * @return how many submitted plan tasks it submitted again (Q4)
     */
    public static int redone(RunView view) {
        var done = new HashSet<String>();
        var count = 0;
        for (var event : view.named(SUBMITTED)) {
            var planTask = event.path("plan_task_id").asText("");
            if (!planTask.isEmpty() && !done.add(event.path("task").asText().replaceAll("-.*$", "") + planTask)) {
                count++;
            }
        }
        return count;
    }

    /**
     * @param view a run
     * @return how often consultation and question were mixed up (Q6): a link refused in the consulting state, a
     *         consulted question asked as a question, or a consultation never delivered
     */
    public static int mixups(RunView view) {
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

    // --- the canary (R1, R2) ---

    /**
     * @param view a run
     * @return whether a submission of it names the run's canary token
     */
    public static boolean namesCanary(RunView view) {
        var canary = view.run().path("canary").asText("");
        return !canary.isEmpty() && view.named(SUBMITTED).stream()
                .anyMatch(event -> event.path("args").toString().contains(canary));
    }

    /**
     * Whether the worker read the canary file again after the ask (R1): an output line after the ask that is a call of
     * one of the worker's own tools whose input names {@value QuestionFixtures#CANARY_FILE}
     * ({@link #readsFile(String, String)}). A call of a stub tool ({@link #STUB_TOOLS}) never counts, whatever its
     * arguments say (the submit names the canary file in its judgment); neither does assistant prose, a tool result or
     * a tool's output.
     *
     * @param outputs a run's arrival-stamped output lines
     * @param askedAt when the worker asked (epoch milliseconds)
     * @return whether a line after the ask reads the canary file
     */
    public static boolean rereadsCanary(List<JsonNode> outputs, long askedAt) {
        return outputs.stream().filter(line -> line.path("at").asLong() > askedAt)
                .anyMatch(line -> readsFile(line.path("line").asText(), QuestionFixtures.CANARY_FILE));
    }

    /**
     * Whether one output line of a worker is a call of one of its own tools whose input names a file: a Claude Code
     * {@code assistant} line with a {@code tool_use} item, or an OpenCode {@code tool_use} line whose tool's input names
     * it (for OpenCode's {@code execute} only when the code calls no stub tool). Antigravity's output carries no tool
     * calls, so nothing of it counts.
     *
     * @param text one output line of a worker
     * @param file the file name
     * @return whether it is a call of one of the worker's own tools whose input names the file
     */
    public static boolean readsFile(String text, String file) {
        if (!text.contains(file)) {
            return false;
        }
        var line = json(text);
        return line.isPresent() && (claudeCodeReadsFile(line.get(), file) || openCodeReadsFile(line.get(), file));
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

    /**
     * @param name a tool name as a host reports it
     * @return whether it is one of the stub's tools ({@code pm_wait}, {@code mcp__plan-marshall__pm_wait},
     *         {@code plan-marshall.pm_wait})
     */
    public static boolean stubTool(String name) {
        return STUB_TOOLS.stream().anyMatch(stub -> name.equals(stub) || name.endsWith("_" + stub)
                || name.endsWith("." + stub));
    }

    // --- output ---

    /**
     * @param view a run
     * @return the calls of the host's own question tool in its output (Q2)
     */
    public static int hostToolCalls(RunView view) {
        var tool = view.host().questionTool();
        return (int) view.outputs().stream().map(line -> line.path("line").asText())
                .filter(text -> text.contains("\"name\":\"" + tool + "\"") || text.contains("\"tool\":\"" + tool + "\""))
                .count();
    }

    /**
     * @param view a run with a host question tool call
     * @return how the host's question tool behaved: {@code hangs}, {@code fails} or {@code answers_itself}
     */
    public static String hostToolBehaviour(RunView view) {
        if (view.run().path("timed_out").asBoolean()) {
            return "hangs";
        }
        var failed = view.outputs().stream().map(line -> line.path("line").asText())
                .anyMatch(text -> text.contains("\"is_error\":true") || text.contains("\"status\":\"error\""));
        return failed ? "fails" : "answers_itself";
    }

    /**
     * @param outputs a run's arrival-stamped output lines
     * @return the worker's final text: Claude Code's {@code result}, OpenCode's last text part, Antigravity's
     *         top-level {@code response}
     */
    public static String finalText(List<JsonNode> outputs) {
        var text = "";
        for (var stamped : outputs) {
            var parsed = json(stamped.path("line").asText());
            if (parsed.isEmpty()) {
                continue;
            }
            var line = parsed.get();
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
     * @return whether the worker's final text ends with a question mark: a prose question
     */
    public static boolean proseQuestion(List<JsonNode> outputs) {
        return finalText(outputs).strip().endsWith("?");
    }

    /**
     * @param outputs a run's arrival-stamped output lines
     * @return the tokens the harness reported: Claude Code's result usage, OpenCode's step usage, Antigravity's
     *         top-level usage ({@link #usageTokens(JsonNode)})
     */
    public static long tokens(List<JsonNode> outputs) {
        var total = 0L;
        for (var stamped : outputs) {
            var parsed = json(stamped.path("line").asText());
            if (parsed.isEmpty()) {
                continue;
            }
            var line = parsed.get();
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
                total += steps.path("input").asLong() + steps.path("output").asLong() + steps.path("reasoning").asLong()
                        + steps.path("cache").path("read").asLong() + steps.path("cache").path("write").asLong();
            }
        }
        return total;
    }

    /**
     * The tokens of Antigravity's top-level {@code usage}: a total field when it has one, else the sum of its numeric
     * fields whose names contain {@code token}.
     *
     * @param usage the usage object
     * @return its tokens, {@code 0} without usage
     */
    public static long usageTokens(JsonNode usage) {
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

    private static boolean antigravityResult(JsonNode line) {
        return line.path("conversation_id").isTextual();
    }

    /**
     * @param view a run
     * @return the milliseconds its worker generations ran, summed from {@code worker_started} to {@code worker_exited}
     */
    public static long workerTime(RunView view) {
        var started = new LinkedHashMap<String, Long>();
        var total = 0L;
        for (var event : view.events()) {
            var key = event.path(WORKER).asText() + "#" + event.path(GENERATION).asInt();
            var name = event.path(EVENT).asText();
            if (WORKER_STARTED.equals(name)) {
                started.put(key, event.path("at").asLong());
            } else if (WORKER_EXITED.equals(name)) {
                var start = started.remove(key);
                if (start != null) {
                    total += event.path("at").asLong() - start;
                }
            }
        }
        return total;
    }

    // --- Q3 ---

    /**
     * @param view  a run
     * @param asked its first ask
     * @return the milliseconds from the ask to the asking generation's first {@code pm_wait} at or after it, empty when
     *         it called none
     */
    public static Optional<Long> nextPmWait(RunView view, JsonNode asked) {
        var at = asked.path("at").asLong();
        var worker = asked.path(WORKER).asText();
        var generation = asked.path(GENERATION).asInt();
        return view.events().stream().filter(event -> event.path("at").asLong() >= at && same(event, worker, generation))
                .filter(event -> "wait_started".equals(event.path(EVENT).asText())
                        || QuestionDriver.CALL.equals(event.path(EVENT).asText())
                        && "pm_wait".equals(event.path("tool").asText()))
                .map(event -> event.path("at").asLong() - at).findFirst();
    }

    /**
     * @param view  a run
     * @param asked its first ask
     * @return the milliseconds from the ask to the asking generation's process exit, empty without one
     */
    public static Optional<Long> askToExit(RunView view, JsonNode asked) {
        var at = asked.path("at").asLong();
        var worker = asked.path(WORKER).asText();
        var generation = asked.path(GENERATION).asInt();
        return view.named(WORKER_EXITED).stream()
                .filter(event -> event.path("at").asLong() >= at && same(event, worker, generation))
                .map(event -> event.path("at").asLong() - at).findFirst();
    }

    // --- R2 ---

    /**
     * Whether a positive R2 run continued from the session: an answer was delivered to a resumed generation, an answer
     * was acknowledged, and a submission names the canary.
     *
     * @param view a run
     * @return whether it continued from the session; never for a negative resume
     */
    public static boolean continuedFromSession(RunView view) {
        if (view.run().path("negative").asBoolean()) {
            return false;
        }
        var acknowledged = view.named(ACKNOWLEDGED).stream()
                .anyMatch(event -> "answer".equals(event.path("kind").asText()));
        var resumed = view.named(ANSWER_DELIVERED).stream().anyMatch(event -> event.path("resumed").asBoolean());
        return resumed && acknowledged && namesCanary(view);
    }

    /**
     * @param view a negative R2 run (the resume of a missing or over-age session)
     * @return how its resume ended ({@link #negativeOutcome(boolean, boolean, long, int)})
     */
    public static String negativeOutcome(RunView view) {
        var started = view.named(WORKER_STARTED).stream().filter(event -> event.path("resumed").asBoolean())
                .findFirst();
        var exited = started.flatMap(start -> view.named(WORKER_EXITED).stream()
                .filter(event -> same(event, start.path(WORKER).asText(), start.path(GENERATION).asInt()))
                .findFirst());
        var exitAfter = started.isPresent() && exited.isPresent()
                ? exited.get().path("at").asLong() - started.get().path("at").asLong() : -1L;
        return negativeOutcome(view.run().path(NEGATIVE_NEW_SESSION).asBoolean(), started.isPresent(), exitAfter,
                exited.map(event -> event.path("exit").asInt()).orElse(-1));
    }

    /**
     * Classifies the resume of a missing or over-age session (R2). A new session comes first; otherwise a non-zero
     * exit within {@link #HUNG_RESUME_MS} failed at once, no exit or a later one is hung, and an exit 0 within it with
     * no new session is recorded as such.
     *
     * @param newSession whether the generation started a new session
     * @param started    whether the resumed generation was started
     * @param exitAfter  the milliseconds from its start to its exit, {@code -1} when it did not exit
     * @param exitCode   its exit code, {@code -1} when it did not exit
     * @return {@link #NEGATIVE_NEW_SESSION}, {@link #FAILED_AT_ONCE}, {@link #HUNG} or {@link #EXITED_WITHOUT_FAILURE}
     */
    public static String negativeOutcome(boolean newSession, boolean started, long exitAfter, int exitCode) {
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
     */
    public static boolean warnsMissingSession(String stderr, String sessionId) {
        return stderr.contains("conversation \"" + sessionId + "\" not found");
    }

    /**
     * Whether the resume of a missing session (R2) started a new session: the harness warned that the named session
     * was not found, or it reports a session other than the one named, or it is OpenCode and reports any session
     * (OpenCode creates a session under a well-formed id that names none).
     *
     * @param host     the host
     * @param named    the session id the generation was told to resume
     * @param reported the session id the generation reports, empty when none
     * @param warned   whether it warned that the named session was not found
     * @return whether it started a new session
     */
    public static boolean startedNewSession(Host host, String named, String reported, boolean warned) {
        if (warned) {
            return true;
        }
        if (reported == null || reported.isEmpty()) {
            return false;
        }
        return !named.equals(reported) || host == Host.OPENCODE;
    }

    // --- R3 ---

    /**
     * When a generation's answer was delivered and its process exit observed ({@link #exitObservation}).
     *
     * @param deliveredAt         its first {@code answer_delivered} (epoch milliseconds), {@code null} without one
     * @param exitAt              its first {@code worker_exited} (epoch milliseconds), {@code null} without one
     * @param exitAfterDeliveryMs the milliseconds from the delivery to the exit, {@code null} without either
     */
    public record ExitObservation(Long deliveredAt, Long exitAt, Long exitAfterDeliveryMs) {
    }

    /**
     * @param events     a stub log
     * @param worker     a worker
     * @param generation its generation
     * @return when the generation's answer was delivered and its process exit observed
     */
    public static ExitObservation exitObservation(List<JsonNode> events, String worker, int generation) {
        var delivered = firstAt(events, ANSWER_DELIVERED, worker, generation);
        var exited = firstAt(events, WORKER_EXITED, worker, generation);
        return new ExitObservation(delivered, exited, delivered == null || exited == null ? null : exited - delivered);
    }

    private static Long firstAt(List<JsonNode> events, String name, String worker, int generation) {
        return events.stream().filter(event -> name.equals(event.path(EVENT).asText()) && same(event, worker, generation))
                .findFirst().map(event -> event.path("at").asLong()).orElse(null);
    }

    /**
     * @param view a run
     * @return the host's cut of its held call: the driver's {@value QuestionDriver#FAULT_INJECTED} of
     *         {@link Fault#CALL_CUT} with {@code cut_by: host}, empty when the logs show none
     */
    public static Optional<JsonNode> hostCut(RunView view) {
        return view.named(QuestionDriver.FAULT_INJECTED).stream()
                .filter(event -> Fault.CALL_CUT.name().equals(event.path("fault").asText())
                        && "host".equals(event.path("cut_by").asText()))
                .findFirst();
    }

    /**
     * @param view a run
     * @param cut  its cut ({@link #hostCut(RunView)})
     * @return the milliseconds from the cut to the same generation's next {@code pm_wait}, empty when it called none
     */
    public static Optional<Long> reWaitAfterCut(RunView view, JsonNode cut) {
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
     * after the cut, the answer was delivered to it exactly once, it acknowledged the answer, and no loss of it was
     * detected. Under the changed R3 criterion such a run is no lost continuation and passes.
     *
     * @param view a run
     * @param cut  its cut ({@link #hostCut(RunView)})
     * @return whether the worker survived the cut
     */
    public static boolean cutSurvived(RunView view, JsonNode cut) {
        var worker = cut.path(WORKER).asText();
        var generation = cut.path(GENERATION).asInt();
        var delivered = view.named(ANSWER_DELIVERED).stream().filter(event -> same(event, worker, generation)).count();
        var acknowledged = view.named(ACKNOWLEDGED).stream().anyMatch(event -> same(event, worker, generation)
                && "answer".equals(event.path("kind").asText()));
        var lost = view.named(QuestionDriver.LOST_DETECTED).stream().anyMatch(event -> same(event, worker, generation));
        return reWaitAfterCut(view, cut).isPresent() && delivered == 1 && acknowledged && !lost;
    }

    // --- numbers ---

    static double share(int count, int population) {
        return population == 0 ? 0 : (double) count / population;
    }

    /**
     * The median of the values: the middle value of an odd count, the mean of the two middle values (rounded down)
     * of an even count, and {@code -1} for no value.
     */
    static long median(List<Long> values) {
        if (values.isEmpty()) {
            return -1;
        }
        var sorted = values.stream().sorted().toList();
        var middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return Math.floorDiv(sorted.get(middle - 1) + sorted.get(middle), 2L);
    }

    static long max(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).max().orElse(-1);
    }

    private static Optional<JsonNode> json(String text) {
        try {
            var node = QuestionStub.JSON.readTree(text);
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (JsonProcessingException _) {
            return Optional.empty();
        }
    }
}
