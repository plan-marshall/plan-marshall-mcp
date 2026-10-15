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
 * The behavioural conformance harness (PM-TEST-6), outside the regular build: it verifies the model-facing content
 * against a live model and never runs in {@code verify}, the integration tests, the coverage run or a required
 * check. {@link de.planmarshall.conformance.Verdict} is the verdict of a scenario over its population, {@link
 * de.planmarshall.conformance.Attribution} how a verdict was measured, {@link
 * de.planmarshall.conformance.EnforcementClass} the class of the rule under test.
 * <p>
 * Specification: {@code pm-mcp-core/doc/specification/workflow-dsl/07-loop-back-diagrams-verification.adoc} (Behavioural
 * Conformance Harness).
 */
package de.planmarshall.conformance;
