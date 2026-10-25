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
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Where the evidence of the measurement is kept so that nothing removes it (experiment only). A reboot on 2026-10-24
 * wiped the OS temp directory that held every invocation's stub logs, and the re-aggregations after it read the runs
 * whose logs were gone as runs that did not ask. Therefore:
 * <ul>
 * <li>The stub logs of an invocation ({@code events.jsonl}, {@code questions.jsonl}, per run under
 * {@code <run id>/stub/}) live under the persistent root {@code target/question-loop/work/} of the results directory,
 * build output that git ignores, in a directory of the invocation ({@code pm-qlv-<time>}); {@value #WORK_PROPERTY}
 * places the root elsewhere ({@link #workRoot(Path)}). Only the evidence moved: the stub's Unix socket stays at
 * {@code /tmp/pmq-<hex>.sock}, a short path within {@code sun_path}, and the worker directories (the fixture checkouts
 * a harness works in) stay below the OS temp directory ({@code pm.experiment.workspace}), outside the repository so that
 * no harness reads its instructions, and nothing reads them after the run: the figures come from the stub logs, the
 * relay logs and the worker outputs, the last two under {@code target/question-loop/runs/<run id>/}.</li>
 * <li>At the end of every invocation the experiment writes its archive ({@link #archive}): the invocation's lines of
 * {@code runs.jsonl} and {@code invocations.jsonl}, its own result ({@code result.json}, aggregated from its records
 * only), a copy of the stub logs of each of its runs, and {@code analysis.txt}, a one-page reading of the result
 * ({@link #analysis}). It is written also when the guard of the result refuses to replace an item's result file;
 * {@value #ARCHIVE_PROPERTY}{@code =false} switches it off.</li>
 * <li>{@link #relocate} copies the stub logs of runs recorded under the OS temp directory into the persistent root and
 * rewrites only the {@code stub_dir} field of their records, run once for the invocations after the reboot
 * ({@link QuestionReaggregation}).</li>
 * </ul>
 */
final class QuestionEvidence {

    /** The property of the persistent root of the stub logs, by default {@code work} in the results directory. */
    static final String WORK_PROPERTY = "pm.experiment.work";
    /** The property that switches the archive of an invocation off ({@code false}); on by default. */
    static final String ARCHIVE_PROPERTY = "pm.experiment.archive";
    /** The name of the archive file of an invocation's own result. */
    static final String RESULT_FILE = "result.json";
    /** The name of the archive file of the reading of an invocation's result. */
    static final String ANALYSIS_FILE = "analysis.txt";

    private static final String RUNS_FILE = "runs.jsonl";
    private static final String INVOCATIONS_FILE = "invocations.jsonl";
    private static final String STUB = "stub";
    private static final String STUB_DIR = "stub_dir";
    private static final String EVENTS = "events.jsonl";
    private static final String QUESTIONS = "questions.jsonl";
    private static final String RUNS = "runs";
    private static final String NEWLINE = "\n";

    /** The figures each item's verdict reads, as the evaluation names them in a group. */
    private static final Map<String, List<String>> VERDICT_FIGURES = Map.ofEntries(
            Map.entry("Q1", List.of("asks", "accepted_share", "asks_lost")),
            Map.entry("Q2", List.of("ask_share", "q1_fixture_runs", "guessed_decisions", "host_question_tool_calls",
                    "prose_questions", "control_asks")),
            Map.entry("Q3", List.of("stopped", "worktree_unchanged", QuestionResults.VERDICT_BY_NEXT_PM_WAIT)),
            Map.entry("Q4", List.of("follows_answer_share", "answered_question_asked_again",
                    "submitted_plan_task_done_again")),
            Map.entry("Q5", List.of("bound_reached", "second_ask_share", "same_question_twice",
                    "ended_with_judgment_or_infeasible", "lost_tasks", "prose_questions")),
            Map.entry("Q6", List.of("votes", "valid_votes", "decided_by_quorum", "applied_share",
                    "consultation_question_mixups")),
            Map.entry("Q7", List.of(QuestionResults.LEASES_WHILE_OPEN, "warm_worker_recycled",
                    "stub_restarted_while_open", "answer_delivered_exactly_once")),
            Map.entry("R1", List.of("continued_same_generation_share", "canary_read_again")),
            Map.entry("R2", List.of("continued_from_session_share", "hung_resumes")),
            Map.entry("R3", List.of("detected_within_deadline", "detected_early", "continued_exactly_once")),
            Map.entry("R4", List.of("tokens_median", "worker_time_ms_median", "answer_follows_reference")));

    /** The group columns every run lands in, as the analysis lists them. */
    private static final List<String> COLUMNS = List.of(QuestionResults.EARLY_EXIT,
            QuestionResults.CONTINUATION_EARLY_EXIT, QuestionResults.VOTER_EARLY_EXIT, QuestionResults.QUOTA_ERROR,
            QuestionResults.SETUP_FAILED, QuestionResults.EVIDENCE_MISSING);

    private QuestionEvidence() {
    }

    /**
     * @param results the results directory of the measurement ({@code target/question-loop})
     * @return the persistent root of the stub logs: {@value #WORK_PROPERTY}, else {@code work} in the results directory
     */
    static Path workRoot(Path results) {
        var property = System.getProperty(WORK_PROPERTY, "").strip();
        return property.isEmpty() ? results.resolve("work") : Path.of(property).toAbsolutePath();
    }

    /** @return whether the archive of an invocation is written: unless {@value #ARCHIVE_PROPERTY} is {@code false} */
    static boolean archiveEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ARCHIVE_PROPERTY, "true").strip());
    }

    /**
     * @param results    the results directory of the measurement
     * @param invocation an invocation id
     * @return its archive directory, {@code archive/<invocation id>} in the results directory
     */
    static Path archiveDirectory(Path results, String invocation) {
        if (invocation.isBlank() || invocation.contains("/") || invocation.contains("\\")
                || invocation.startsWith(".")) {
            throw new IllegalArgumentException("no invocation id: " + invocation);
        }
        return results.resolve("archive").resolve(invocation);
    }

    /**
     * Writes the archive of one invocation, {@code archive/<invocation id>/} in the results directory: its lines of
     * {@code runs.jsonl} and {@code invocations.jsonl} byte for byte, its own result aggregated from its records only
     * ({@value #RESULT_FILE}, the JSON of a result file, {@link QuestionResults#render}), the stub logs of each of its
     * runs ({@code stub/<run id>/events.jsonl} and {@code questions.jsonl}, where they exist), and
     * {@value #ANALYSIS_FILE} ({@link #analysis}). An archive written before for the invocation is written again.
     *
     * @param results    the results directory of the measurement
     * @param item       the item
     * @param invocation the invocation id
     * @param unused     the count of unused scenario recipes, asked for R4 only
     * @return the archive directory
     * @throws IOException if the records cannot be read or the archive not be written
     */
    static Path archive(Path results, String item, String invocation, QuestionResults.UnusedScenarios unused)
            throws IOException {
        var directory = Files.createDirectories(archiveDirectory(results, invocation));
        var runLines = linesOf(results.resolve(RUNS_FILE), invocation);
        var invocationLines = linesOf(results.resolve(INVOCATIONS_FILE), invocation);
        Files.writeString(directory.resolve(RUNS_FILE), joined(runLines), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(INVOCATIONS_FILE), joined(invocationLines), StandardCharsets.UTF_8);
        var runs = new ArrayList<JsonNode>();
        for (var line : runLines) {
            runs.add(QuestionStub.JSON.readTree(line));
        }
        var invocations = QuestionResults.invocations(results, item).stream()
                .filter(node -> invocation.equals(node.path(QuestionResults.INVOCATION).asText())).toList();
        var result = QuestionResults.result(item, runs, invocations, unused);
        Files.writeString(directory.resolve(RESULT_FILE), QuestionResults.render(result), StandardCharsets.UTF_8);
        var copied = 0;
        for (var run : runs) {
            copied += copyStubLogs(run, directory.resolve(STUB)) ? 1 : 0;
        }
        Files.writeString(directory.resolve(ANALYSIS_FILE), analysis(item, invocation, result, runs.size(), copied),
                StandardCharsets.UTF_8);
        return directory;
    }

    /**
     * @param file       a JSON Lines file of the results directory
     * @param invocation an invocation id
     * @return its lines whose {@value QuestionResults#INVOCATION} is the invocation, verbatim; none without the file
     * @throws IOException if it cannot be read or a line is no JSON
     */
    static List<String> linesOf(Path file, String invocation) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        var lines = new ArrayList<String>();
        for (var line : Files.readString(file, StandardCharsets.UTF_8).split(NEWLINE, -1)) {
            if (!line.isBlank()
                    && invocation.equals(QuestionStub.JSON.readTree(line).path(QuestionResults.INVOCATION).asText())) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String joined(List<String> lines) {
        return lines.isEmpty() ? "" : String.join(NEWLINE, lines) + NEWLINE;
    }

    /** Copies a run's stub logs to {@code <stub>/<run id>/}; whether its {@code events.jsonl} was there to copy. */
    private static boolean copyStubLogs(JsonNode run, Path stub) throws IOException {
        if (QuestionResults.evidenceMissing(run) || QuestionResults.setupFailed(run)) {
            return false;
        }
        var source = Path.of(run.path(STUB_DIR).asText());
        var target = Files.createDirectories(stub.resolve(QuestionResults.runId(run)));
        for (var name : List.of(EVENTS, QUESTIONS)) {
            if (Files.isRegularFile(source.resolve(name))) {
                Files.copy(source.resolve(name), target.resolve(name), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        return true;
    }

    /**
     * The one-page reading of an invocation's result: the verdict with its marks, and per group its runs, asked,
     * continued and submitted, {@code timed_out} with the runs, the early exits, quota errors, setup failures and runs
     * without a stub log, the figures its verdict reads and its verdict; then the runs not following the reference and
     * the limits.
     *
     * @param item       the item
     * @param invocation the invocation id
     * @param result     its result ({@link QuestionResults#result})
     * @param records    its run records
     * @param copied     the runs whose stub logs the archive holds
     * @return the text
     */
    static String analysis(String item, String invocation, ObjectNode result, int records, int copied) {
        var text = new StringBuilder();
        text.append("Invocation ").append(invocation).append(" of ").append(item).append(", archived ")
                .append(Instant.now()).append(NEWLINE);
        text.append("Criterion: ").append(result.path("criterion").asText()).append(NEWLINE);
        text.append("Verdict: ").append(result.path("verdict").asText());
        for (var mark : List.of("verdict_scale", QuestionResults.VERDICT_EVIDENCE,
                QuestionResults.VERDICT_WITHOUT_QUOTA_ERROR, QuestionResults.VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT)) {
            if (result.has(mark)) {
                text.append(" (").append(mark).append(": ").append(result.path(mark).asText()).append(')');
            }
        }
        text.append(NEWLINE);
        text.append("Run records: ").append(records).append("; stub logs archived: ").append(copied).append(NEWLINE);
        var notFollowing = new ArrayList<String>();
        for (var group : result.path("groups")) {
            text.append(NEWLINE).append("Group ").append(group.path("group").asText()).append(NEWLINE);
            text.append("  runs ").append(group.path(RUNS).asInt()).append(", asked ")
                    .append(group.path("asked").asInt()).append(", continued ").append(group.path("continued").asInt())
                    .append(", submitted ").append(group.path("submitted").asInt()).append(NEWLINE);
            text.append("  timed_out ").append(group.path(QuestionResults.TIMED_OUT).asInt())
                    .append(names(group.path(QuestionResults.TIMED_OUT_RUNS))).append(NEWLINE);
            var columns = new ArrayList<String>();
            for (var column : COLUMNS) {
                columns.add(column + " " + group.path(column).asInt(0));
            }
            text.append("  ").append(String.join(", ", columns)).append(NEWLINE);
            var figures = new ArrayList<String>();
            for (var figure : VERDICT_FIGURES.getOrDefault(item, List.of())) {
                if (group.has(figure)) {
                    figures.add(figure + " " + group.path(figure).asText());
                }
            }
            text.append("  verdict figure: ").append(figures.isEmpty() ? "none" : String.join(", ", figures))
                    .append(NEWLINE);
            text.append("  verdict: ").append(group.path("verdict").asText());
            for (var mark : List.of(QuestionResults.VERDICT_EVIDENCE, QuestionResults.VERDICT_WITHOUT_QUOTA_ERROR,
                    QuestionResults.VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT)) {
                if (group.has(mark)) {
                    text.append(" (").append(mark).append(": ").append(group.path(mark).asText()).append(')');
                }
            }
            text.append(NEWLINE);
            group.path(QuestionResults.NOT_FOLLOWING_REFERENCE_RUNS).forEach(run -> notFollowing.add(run.asText()));
        }
        text.append(NEWLINE).append("Runs not following the reference: ")
                .append(notFollowing.isEmpty() ? "none" : String.join(", ", notFollowing)).append(NEWLINE);
        text.append(NEWLINE).append("Limits:").append(NEWLINE);
        var index = 1;
        for (var limit : result.path("limits")) {
            text.append(index++).append(". ").append(limit.asText()).append(NEWLINE);
        }
        if (index == 1) {
            text.append("none").append(NEWLINE);
        }
        return text.toString();
    }

    private static String names(JsonNode runs) {
        var names = new ArrayList<String>();
        runs.forEach(run -> names.add(run.asText()));
        return names.isEmpty() ? "" : " (" + String.join(", ", names) + ")";
    }

    // --- relocation ---

    /**
     * What a relocation did per invocation.
     *
     * @param relocated    the records whose {@code stub_dir} it rewrote
     * @param fromTemp     of them, the records whose stub logs it copied from the recorded directory
     * @param fromFallback of them, the records whose stub logs it copied from a fallback copy
     * @param already      the records already under the persistent root, left as they were
     */
    record Moved(int relocated, int fromTemp, int fromFallback, int already) {

        Moved add(boolean fallback) {
            return new Moved(relocated + 1, fromTemp + (fallback ? 0 : 1), fromFallback + (fallback ? 1 : 0), already);
        }

        Moved kept() {
            return new Moved(relocated, fromTemp, fromFallback, already + 1);
        }
    }

    /**
     * Relocates the stub logs of the named invocations' runs into the persistent root and rewrites only the
     * {@code stub_dir} field of their records: the stub directory {@code <workspace>/<run id>/stub} of each record is
     * copied to {@code <work root>/<workspace>/<run id>/stub}, from the recorded directory while it exists, else from
     * a fallback copy ({@code <fallback>/<invocation>/<workspace>/<run id>/stub} or
     * {@code <fallback>/<invocation>/<run id>/stub}); then the record's line gets the new path in place of the old, the
     * rest of the line and every other line byte for byte as before. A record already under the persistent root is
     * left alone, so a second run changes nothing. The file is replaced at once, after every copy succeeded.
     *
     * @param runsFile    the {@code runs.jsonl} of the measurement
     * @param invocations the invocations whose records move
     * @param workRoot    the persistent root ({@link #workRoot(Path)})
     * @param fallbacks   the roots of the fallback copies, by invocation
     * @return what moved, per named invocation, in the order named
     * @throws IOException if a record's stub logs exist nowhere, a record names its stub directory other than once, or
     *                     a file cannot be read, copied or written
     */
    static Map<String, Moved> relocate(Path runsFile, Collection<String> invocations, Path workRoot,
            List<Path> fallbacks) throws IOException {
        var moved = new LinkedHashMap<String, Moved>();
        invocations.forEach(invocation -> moved.put(invocation, new Moved(0, 0, 0, 0)));
        var lines = Files.readString(runsFile, StandardCharsets.UTF_8).split(NEWLINE, -1);
        var changed = false;
        for (var index = 0; index < lines.length; index++) {
            var line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            var record = QuestionStub.JSON.readTree(line);
            var invocation = record.path(QuestionResults.INVOCATION).asText();
            if (!moved.containsKey(invocation)) {
                continue;
            }
            var old = Path.of(record.path(STUB_DIR).asText());
            var runDirectory = old.getParent();
            if (runDirectory == null || runDirectory.getParent() == null) {
                throw new IOException("no stub directory <workspace>/<run id>/stub in " + old);
            }
            var runId = runDirectory.getFileName().toString();
            var workspace = runDirectory.getParent().getFileName().toString();
            var target = workRoot.resolve(workspace).resolve(runId).resolve(STUB);
            if (old.equals(target)) {
                moved.put(invocation, moved.get(invocation).kept());
                continue;
            }
            var source = source(old, fallbacks, invocation, workspace, runId)
                    .orElseThrow(() -> new IOException("no stub log of " + runId + " at " + old + " or in "
                            + fallbacks));
            copyTree(source, target);
            lines[index] = withStubDir(line, record, target);
            moved.put(invocation, moved.get(invocation).add(!source.equals(old)));
            changed = true;
        }
        if (changed) {
            var temporary = runsFile.resolveSibling(runsFile.getFileName() + ".relocating");
            Files.writeString(temporary, String.join(NEWLINE, lines), StandardCharsets.UTF_8);
            Files.move(temporary, runsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return moved;
    }

    private static Optional<Path> source(Path old, List<Path> fallbacks, String invocation, String workspace,
            String runId) {
        var candidates = new ArrayList<Path>();
        candidates.add(old);
        for (var fallback : fallbacks) {
            candidates.add(fallback.resolve(invocation).resolve(workspace).resolve(runId).resolve(STUB));
            candidates.add(fallback.resolve(invocation).resolve(runId).resolve(STUB));
        }
        return candidates.stream().filter(candidate -> Files.isRegularFile(candidate.resolve(EVENTS))).findFirst();
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var files = Files.walk(source)) {
            for (var file : files.toList()) {
                var copy = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(copy);
                } else if (Files.isRegularFile(file)) {
                    Files.createDirectories(copy.getParent());
                    Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    /**
     * @param line   a record's line of {@code runs.jsonl}
     * @param record the record it holds
     * @param target the new stub directory
     * @return the line with the new {@code stub_dir} in place of the old and nothing else changed
     * @throws IOException if the line names its stub directory other than once, or the new line differs from the
     *                     record in anything but {@code stub_dir}
     */
    static String withStubDir(String line, JsonNode record, Path target) throws IOException {
        var old = "\"" + STUB_DIR + "\":" + QuestionStub.JSON.writeValueAsString(record.path(STUB_DIR).asText());
        var first = line.indexOf(old);
        if (first < 0 || line.indexOf(old, first + 1) >= 0) {
            throw new IOException("the record does not name its stub directory exactly once: " + line);
        }
        var replaced = line.substring(0, first) + "\"" + STUB_DIR + "\":"
                + QuestionStub.JSON.writeValueAsString(target.toString()) + line.substring(first + old.length());
        var expected = ((ObjectNode) record.deepCopy()).put(STUB_DIR, target.toString());
        if (!expected.equals(QuestionStub.JSON.readTree(replaced))) {
            throw new IOException("the relocated record differs beyond stub_dir: " + replaced);
        }
        return replaced;
    }

    /**
     * Writes the report of a relocation beside the relocated logs: {@code relocation-<time>.json} in the persistent
     * root, with the invocations and what moved of each.
     *
     * @param workRoot the persistent root
     * @param moved    what moved, per invocation ({@link #relocate})
     * @return the report file
     * @throws IOException if it cannot be written
     */
    static Path report(Path workRoot, Map<String, Moved> moved) throws IOException {
        var report = QuestionStub.JSON.createObjectNode();
        report.put("at", Instant.now().toString());
        var invocations = report.putObject("invocations");
        moved.forEach((invocation, counts) -> invocations.set(invocation, QuestionStub.JSON.valueToTree(counts)));
        var file = Files.createDirectories(workRoot).resolve("relocation-"
                + Instant.now().toString().replace(":", "").replace(".", "") + ".json");
        Files.writeString(file, QuestionResults.render(report), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * @param value a comma-separated list, blank entries ignored
     * @return its entries, stripped, in their order
     */
    static List<String> list(String value) {
        var entries = new ArrayList<String>();
        for (var entry : value.split(",")) {
            if (!entry.isBlank()) {
                entries.add(entry.strip());
            }
        }
        return entries;
    }
}
