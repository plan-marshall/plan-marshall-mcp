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
 * {@code -Dpm.experiment.agy.argv} and {@code -Dpm.experiment.agy.config} override it.</li>
 * <li>{@link de.planmarshall.e2e.experiment.question.AgyProbeTest}: the probe of the agy CLI those values rest on,
 * disabled unless {@code -Dpm.experiment.agy.probe=<step>} is set.</li>
 * <li>{@link de.planmarshall.e2e.experiment.question.QuestionFixtures}: the loop tasks, generated from the corpus cache
 * and the private telemetry scenarios, never written into the repository.</li>
 * <li>{@link de.planmarshall.e2e.experiment.question.QuestionResults}: the pass criteria of the package, fixed before the
 * first measured run, and the figures per item, host, model and form.</li>
 * <li>{@link de.planmarshall.e2e.experiment.question.QuestionLoopExperiment}: the entry that runs one item, disabled
 * unless {@code -Dpm.experiment.question=<item>} is set.</li>
 * </ul>
 * Every figure comes from the stub's event log, the relay's host-side log and the worker's output, never from what a
 * model reports about itself.
 */
package de.planmarshall.e2e.experiment.question;
