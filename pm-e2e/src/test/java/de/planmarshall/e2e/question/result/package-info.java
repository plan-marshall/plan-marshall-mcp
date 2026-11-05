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
 * The evaluation of the question-loop measurement and the writer of its evidence.
 * <p>
 * {@link de.planmarshall.e2e.question.result.QuestionResults} reads every figure from the stub's event log, the
 * relay's host-side log and the worker's output, never from a model's own report, and states each item's verdict at
 * the revised scale: 3 runs per fixture, host and form, a group passes when every run passes, and a group whose runs
 * disagree is extended to 10 and reported with its rate. A verdict is stated with its run count and the client version
 * it was measured on.
 * <p>
 * {@link de.planmarshall.e2e.question.result.QuestionEvidence} keeps the evidence of every invocation in the form of
 * the evidence store (branch {@code question-loop-follow-up} of {@code cuioss/plan-marshall-telemetry}): one directory
 * per invocation id with {@code runs.jsonl}, {@code invocations.jsonl}, the stub logs, the per-invocation archive and
 * {@code answer-audit.json}. A run without its stub log is {@code evidence_missing} and never replaces a result.
 */
package de.planmarshall.e2e.question.result;
