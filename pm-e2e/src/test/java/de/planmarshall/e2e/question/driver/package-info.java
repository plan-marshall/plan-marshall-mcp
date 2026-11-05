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
 * The driver of the question-loop measurement: it starts harness workers, answers on the operator's behalf, injects
 * the faults of R3 and classifies how each worker generation ended.
 * <ul>
 * <li><em>Launch per host.</em> {@link de.planmarshall.e2e.question.driver.Host} holds the values of each host's
 * harness profile: Claude Code, OpenCode 2.x and Antigravity, with the argv of a fresh, a resumed and a model-bound
 * launch, where the session id comes from in the harness's structured output, the acknowledgement deadline or silence
 * limit, the limit at which the host cuts a silent call, and where the stub's MCP configuration is placed.
 * {@link de.planmarshall.e2e.question.driver.QuestionDriver} runs every harness through {@code pm-exec}
 * ({@code -Dpm.experiment.exec}, the native binary of a {@code pm-mcp-clients} checkout beside this repository, or the
 * JAR the JVM layout stages), resolves the harness's executable by its absolute path, strips the markers of the
 * driver's own host session from the environment and sets {@code PWD} to the worker directory.</li>
 * <li><em>Generations.</em> A worker's generations share one worker directory; the launch of a newer generation ends
 * the older ones that still run, every end of a worker stops and continues its whole process tree, and after a loss
 * the driver reads what the stub made of the lost task before it starts another generation.</li>
 * <li><em>Classification.</em> A generation that exits before its relay connects failed to launch
 * ({@code launch_failed}); one that connects and exits without a call ended early ({@code early_exit}), apart for a
 * continuation generation ({@code continuation_early_exit}) and a voter ({@code voter_early_exit}); a quota or limit
 * error counts only in the harness's own error output ({@code quota_error}), never in a tool's input or result, the
 * model's prose or a file the worker read. The driver further records {@code continued_by_hold},
 * {@code warm_asker_exited}, {@code continuation_gap} and {@code asker_ended_by_driver}; the measurement records
 * {@code setup_failed}.</li>
 * <li><em>Faults.</em> {@link de.planmarshall.e2e.question.driver.Fault} holds the faults of R3 with their placement
 * rules, selected by {@code -Dpm.experiment.r3.faults}.</li>
 * </ul>
 * Each run records the client version, the model and the parallel level. No class of this package starts a harness on
 * its own; the gated measurement entry does.
 */
package de.planmarshall.e2e.question.driver;
