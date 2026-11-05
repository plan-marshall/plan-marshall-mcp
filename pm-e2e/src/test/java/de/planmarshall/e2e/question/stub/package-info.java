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
 * The stub MCP server of the question-loop measurement and the relay a harness starts to reach it.
 * <ul>
 * <li><em>Protocol.</em> {@link de.planmarshall.e2e.question.stub.QuestionStub} serves {@code pm_wait},
 * {@code pm_state} and {@code pm_do} over a Unix socket, newline-delimited JSON-RPC, every result a TOON
 * representation of a task entity, with the question links {@code ask} and {@code ask:TASK-NNN}, the consultation
 * link and the vote link of a quorum, and the continuation forms {@code HOLD}, {@code RESUME} and
 * {@code FALLBACK}.</li>
 * <li><em>Identity.</em> {@link de.planmarshall.e2e.question.stub.StubRelay} writes the worker's identity, role,
 * generation and harness as the first line of its connection, from its environment, never from the model, as
 * {@code pm-mcp serve --job} does.</li>
 * <li><em>Leases.</em> A task is leased to one generation of a worker; a newer generation fences the older one, whose
 * calls are refused {@code generation_fenced} and whose leases are released.</li>
 * <li><em>Replay.</em> Question records are appended to {@code questions.jsonl} and replayed at start, so a restarted
 * stub keeps an open record open and delivers its answer exactly once.</li>
 * </ul>
 * Every transition is appended to {@code events.jsonl} with its arrival time, and the relay logs every line host-side;
 * the figures of the measurement are computed from those two logs and the worker's output.
 */
package de.planmarshall.e2e.question.stub;
