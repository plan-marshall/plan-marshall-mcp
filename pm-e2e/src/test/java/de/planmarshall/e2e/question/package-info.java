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
 * The question-loop measurement: whether a headless worker asks through the server, stops after its question, and
 * continues with the answer by holding, by a resumed session, or in a new task (PM-WF-12, PM-WF-18, PM-WORK-2,
 * PM-WORK-4), measured with real harnesses against a stub MCP server.
 * <p>
 * The measurement is gated by properties: no class of this package starts a harness unless its property is set, so a
 * normal build runs only the deterministic tests of the stub, the driver's classification, the fixtures and the
 * evaluation. Every figure comes from the stub's event log, the relay's host-side log and the worker's output, never
 * from what a model reports about itself. The results are recorded in the Evaluation Reference of
 * {@code plan-marshall-documentation} ({@code doc/specification/evaluation.adoc}); the code is described in its
 * developer document {@code doc/developer/question-loop-experiment.adoc}.
 */
package de.planmarshall.e2e.question;
