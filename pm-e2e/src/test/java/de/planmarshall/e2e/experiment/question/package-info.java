/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
/**
 * Experiment-only code of the work package {@code question-loop-verification}: the measurement of the loop a worker's
 * question runs through (PM-WF-12, PM-WF-18, PM-WORK-2, PM-WORK-4). It lives on the package's branch only and is
 * deleted before the merge, so the package's final diff shows no file here; its results are recorded in the
 * Evaluation Reference of {@code plan-marshall-documentation}, and the values they settle in the owning specifications.
 * <ul>
 * <li>{@link de.planmarshall.e2e.experiment.question.QuestionStub}: a stub MCP server with {@code pm_wait},
 * {@code pm_state} and {@code pm_do}, leases per generation, the question links, question records on disk,
 * {@code facts.answers}, and an append-only event log; a worker reaches it over {@code stdio} through its relay, which
 * carries the worker's identity and generation as {@code pm-mcp serve --job} does.</li>
 * <li>{@link de.planmarshall.e2e.experiment.question.QuestionDriver}: starts the harnesses headless through
 * {@code pm-exec}, answers on the operator's behalf, resumes stored sessions, restarts the stub, and injects the
 * faults. Antigravity's launch is the one verified with agy against the stub on 2026-10-21: {@code agy --print
 * <prompt> --output-format json --dangerously-skip-permissions [--model <model>] [--conversation <id>]}, the stub's
 * MCP server as the workspace plugin {@code .agents/plugins/pm-question-stub/} of the worker directory, the session id
 * in {@code conversation_id}; a missing id starts a new conversation instead of failing, and an expired id is not
 * verified (see {@link de.planmarshall.e2e.experiment.question.QuestionLoopExperiment}).
 * {@code -Dpm.experiment.agy.argv} and {@code -Dpm.experiment.agy.config} override it. OpenCode's launch is the one
 * verified with OpenCode 2.1.2 against the stub on 2026-10-21: {@code opencode run --standalone --format json --agent
 * pull-worker [--model <model>] [--session <id>] <prompt>} in the worker directory as working directory and
 * {@code PWD}, the stub's MCP server and the agent in the {@code opencode.json} of the worker directory (the stub's
 * tools reached through OpenCode's code tool {@code execute}), the worker's data home in {@code XDG_DATA_HOME}, the
 * session id in {@code sessionID}; the operator's login does not carry into a worker's data home, a missing
 * {@code ses_} id creates a session under that id, and an id of another form fails at once (see
 * {@link de.planmarshall.e2e.experiment.question.QuestionLoopExperiment}). {@code -Dpm.experiment.opencode.argv}
 * overrides it.</li>
 * <li>{@link de.planmarshall.e2e.experiment.question.AgyProbeTest} and
 * {@link de.planmarshall.e2e.experiment.question.OpenCodeProbeTest}: the probes of the agy and OpenCode CLIs those
 * values rest on, disabled unless {@code -Dpm.experiment.agy.probe=<step>} or
 * {@code -Dpm.experiment.opencode.probe=<step>} is set.</li>
 * <li>{@link de.planmarshall.e2e.experiment.question.QuestionFixtures}: the loop tasks, generated from the corpus cache
 * and the private telemetry scenarios, never written into the repository.</li>
 * <li>{@link de.planmarshall.e2e.experiment.question.QuestionResults}: the pass criteria of the package, fixed before the
 * first measured run, the figures per item, host, model and form, and the invocations a result aggregates with
 * whether they ran at the specified scale.</li>
 * <li>{@link de.planmarshall.e2e.experiment.question.QuestionEvidence}: the persistent root of the stub logs below
 * {@code target/question-loop/work}, the archive every invocation writes at its end, and the relocation of stub logs
 * recorded under the OS temp directory.</li>
 * <li>{@link de.planmarshall.e2e.experiment.question.QuestionLoopExperiment}: the entry that runs one item, disabled
 * unless {@code -Dpm.experiment.question=<item>} is set; its class documentation lists the run properties, among them
 * {@code -Dpm.experiment.r3.faults=<fault>,<fault>} (case-insensitive names of {@code QuestionDriver.Fault}, all by
 * default), which lets an R3 invocation measure only the named faults, for example a re-run of the faults whose
 * injection was defective; its invocation record names them ({@code r3_faults}), and a limit of the result says that
 * the item verdict then reads the groups of the measured faults only.</li>
 * <li>{@link de.planmarshall.e2e.experiment.question.QuestionReaggregation}: writes one item's result again from its
 * existing records without a run, disabled unless {@code -Dpm.experiment.reaggregate=<item>} is set.</li>
 * </ul>
 * Every figure comes from the stub's event log, the relay's host-side log and the worker's output, never from what a
 * model reports about itself.
 */
package de.planmarshall.e2e.experiment.question;
