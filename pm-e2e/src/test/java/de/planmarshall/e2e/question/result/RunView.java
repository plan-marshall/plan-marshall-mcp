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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;


import com.fasterxml.jackson.databind.JsonNode;
import de.planmarshall.e2e.question.driver.Host;
import de.planmarshall.e2e.question.stub.QuestionStub;

/**
 * One run as the evaluation reads it, and the reading of a run's records and files.
 *
 * @param run             the run record
 * @param events          the stub's events of the run ({@code events.jsonl} of its stub directory)
 * @param records         the stub's question records of the run ({@code questions.jsonl})
 * @param outputs         the worker outputs of the run: each line with its arrival time ({@code at}) and its text
 *                        ({@code line})
 * @param evidenceMissing whether the run's stub log is gone ({@link #evidenceMissing(JsonNode)}): its events are then
 *                        empty because nothing could be read, not because nothing happened
 */
public record RunView(JsonNode run, List<JsonNode> events, List<JsonNode> records, List<JsonNode> outputs,
boolean evidenceMissing) {

    /** The field of a run record with its stub directory. */
    public static final String STUB_DIR = "stub_dir";
    /** The stub's event log in a stub directory. */
    public static final String EVENTS_FILE = "events.jsonl";
    /** The stub's question records in a stub directory. */
    public static final String QUESTIONS_FILE = "questions.jsonl";
    /** The model of a run on the harness's default model, which the launch does not name. */
    public static final String HARNESS_DEFAULT = "harness default";
    /** The field of a run record, and the group column, of a run whose setup failed before any worker launched. */
    public static final String SETUP_FAILED = "setup_failed";
    /** The field of a run record with the reason of its {@value #SETUP_FAILED}: the first line of git's output. */
    public static final String SETUP_FAILED_REASON = "setup_failed_reason";
    /**
     * The field of a run record, and the group column, of a run the account's usage limit refused
     * ({@code QuestionDriver#RATE_LIMIT_EVENT}): it counts as not run, in no figure and no verdict, never as a failure.
     */
    public static final String NOT_RUN = "not_run";
    /** The field of a {@value #NOT_RUN} run record with its reason. */
    public static final String NOT_RUN_REASON = "not_run_reason";
    /** The reason of a run refused by the account's usage limit. */
    public static final String USAGE_LIMIT = "usage_limit";

    static final String EVENT = "event";
    static final String WORKER = "worker";
    static final String GENERATION = "generation";

    /**
     * A run whose stub log was read.
     *
     * @param run     the run record
     * @param events  the stub's events of the run
     * @param records the stub's question records of the run
     * @param outputs the worker outputs of the run
     */
    public RunView(JsonNode run, List<JsonNode> events, List<JsonNode> records, List<JsonNode> outputs) {
        this(run, events, records, outputs, false);
    }

    /**
     * @param name an event name
     * @return the run's events of that name, in their order
     */
    public List<JsonNode> named(String name) {
        return events.stream().filter(event -> name.equals(event.path(EVENT).asText())).toList();
    }

    /** @return the host of the run */
    public Host host() {
        return Host.of(run.path("host").asText());
    }

    /**
     * Reads a run's stub log, question records and worker outputs.
     *
     * @param run a run record
     * @return the run as the evaluation reads it; empty events where its stub log is missing
     * @throws IOException if a file exists but cannot be read
     */
    public static RunView read(JsonNode run) throws IOException {
        var outputs = new ArrayList<JsonNode>();
        for (var worker : run.path("workers")) {
            var output = worker.path("output").asText("");
            if (!output.isBlank()) {
                outputs.addAll(readJsonLines(Path.of(output)));
            }
        }
        var missing = evidenceMissing(run);
        var stubDir = run.path(STUB_DIR).asText("");
        if (missing || stubDir.isBlank()) {
            return new RunView(run, List.of(), List.of(), outputs, missing);
        }
        var stub = Path.of(stubDir);
        return new RunView(run, readJsonLines(stub.resolve(EVENTS_FILE)), readJsonLines(stub.resolve(QUESTIONS_FILE)),
                outputs, false);
    }

    /**
     * Reads a JSON Lines file written to the end: every line must be JSON. A file still being written is read with
     * the driver's {@code readJsonLinesSoFar} instead.
     *
     * @param file a JSON Lines file
     * @return its lines, parsed; none when the file does not exist
     * @throws IOException if it cannot be read or a line is no JSON
     */
    public static List<JsonNode> readJsonLines(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
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
     * @param run a run record
     * @return the id of the run: the name of the directory of its stub
     */
    public static String runId(JsonNode run) {
        var stub = Path.of(run.path(STUB_DIR).asText(""));
        return stub.getParent() == null ? stub.toString() : stub.getParent().getFileName().toString();
    }

    /**
     * @param run a run record
     * @return its model, {@value #HARNESS_DEFAULT} when the launch named none
     */
    public static String model(JsonNode run) {
        var model = run.path("model").asText("");
        return model.isEmpty() || "null".equals(model) ? HARNESS_DEFAULT : model;
    }

    /**
     * @param run a run record
     * @return whether its fixture or workspace setup failed before any worker launched ({@value #SETUP_FAILED})
     */
    public static boolean setupFailed(JsonNode run) {
        return run.path(SETUP_FAILED).asBoolean();
    }

    /**
     * @param run a run record
     * @return whether the account's usage limit refused it, so that it counts as not run ({@value #NOT_RUN})
     */
    public static boolean notRun(JsonNode run) {
        return run.path(NOT_RUN).asBoolean();
    }

    /**
     * @param run a run record
     * @return whether it is in no figure and no verdict of its group: its setup failed ({@value #SETUP_FAILED}) or the
     *         usage limit refused it ({@value #NOT_RUN})
     */
    public static boolean excluded(JsonNode run) {
        return setupFailed(run) || notRun(run);
    }

    /**
     * @param run a run record whose setup failed
     * @return its reason ({@value #SETUP_FAILED_REASON}), empty when it recorded none
     */
    public static String setupFailedReason(JsonNode run) {
        return run.path(SETUP_FAILED_REASON).asText("");
    }

    /**
     * Whether a run's evidence is missing: its record names no stub directory, or the directory or its
     * {@value #EVENTS_FILE} no longer exists. A run whose setup failed has no stub log to read and is never counted so.
     * Such a run's events read as empty, which the counts must never take for a run that did not ask.
     *
     * @param run a run record
     * @return whether its stub log is missing
     */
    public static boolean evidenceMissing(JsonNode run) {
        if (setupFailed(run)) {
            return false;
        }
        var stub = run.path(STUB_DIR).asText("");
        return stub.isBlank() || !Files.isRegularFile(Path.of(stub).resolve(EVENTS_FILE));
    }

    /**
     * @param event      a stub event
     * @param worker     a worker
     * @param generation its generation
     * @return whether the event is the generation's
     */
    public static boolean same(JsonNode event, String worker, int generation) {
        return worker.equals(event.path(WORKER).asText()) && generation == event.path(GENERATION).asInt();
    }

    /**
     * @param text a text
     * @return it trimmed, in lower case, without outer quotes and with runs of white space as one blank
     */
    public static String normalize(String text) {
        var value = text.strip().toLowerCase(Locale.ROOT);
        while (value.length() > 1 && Set.of('"', '\'', '`').contains(value.charAt(0))
                && value.charAt(value.length() - 1) == value.charAt(0)) {
            value = value.substring(1, value.length() - 1).strip();
        }
        return value.replaceAll("\\s+", " ");
    }
}
