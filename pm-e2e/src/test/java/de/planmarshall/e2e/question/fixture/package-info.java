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
 * The fixtures of the question-loop measurement, generated at run time and never written into the repository.
 * <p>
 * {@link de.planmarshall.e2e.question.fixture.QuestionFixtures} builds the worker tasks from three read-only sources:
 * the corpus cache that {@code CorpusSourcesTest} writes with {@code -Pcorpus} (the {@code question/apply-answer}
 * items of Q1 to Q4, Q6, Q7 and R3, the {@code question/ask-or-proceed} controls of Q2 and the two-flaw items of Q5),
 * the probe of the {@code resumability} stage (R1, R2 and the small work of R4), and the scenario recipes of the large
 * work of R4 on the branch {@code verification/question-items} of a local checkout of
 * {@code cuioss/plan-marshall-telemetry}. Every forced task, the large-work scenarios of R4 included, carries the
 * forcing text {@link de.planmarshall.e2e.question.fixture.QuestionFixtures#FORCE_ASK} (or its two-flaw and plan-task
 * forms), so that the choice is the operator's and the worker must ask before it acts.
 * <p>
 * The operator's answer to a decision is composed here as well
 * ({@link de.planmarshall.e2e.question.fixture.QuestionFixtures#composeAnswer}): it never contradicts the fixture's
 * reply. The asker's recommendation is used only where it reads as the reply's direction, then the first option that
 * does, and otherwise the reply goes as the operator's own option ({@code answer_fallback_own}).
 */
package de.planmarshall.e2e.question.fixture;
