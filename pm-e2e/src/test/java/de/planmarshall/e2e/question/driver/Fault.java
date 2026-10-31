/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.e2e.question.driver;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;


import de.planmarshall.e2e.question.stub.QuestionStub.Form;

/**
 * A fault of R3, injected by {@link QuestionDriver#inject(QuestionDriver.Worker, Fault)} with its placement rule.
 * An R3 run continues in {@link #form()} and the operator holds the answer for {@link #hold(Host)}; the faults of an
 * invocation are selected by {@value #PROPERTY} ({@link #select(String)}).
 */
public enum Fault {

    /**
     * The worker's process tree stopped ({@code SIGSTOP}) while it holds {@code pm_wait} for the asked task, before the
     * answer is delivered ({@link QuestionDriver#stopWhileHolding}): the stop is injected only once the stub shows the
     * asking generation's {@code pm_wait} started after the ask and not ended. Before 2026-10-23 it was injected at the
     * ask, before the worker called {@code pm_wait}, so the answer was never delivered and nothing was detected (R3
     * smoke attempt, runs {@code fcf8e585} and {@code d0ce71f2}, each open about 1353 s and timed out).
     */
    STOP_BEFORE_ANSWER,
    /**
     * The held call cut by the host: the held {@code pm_wait} gets no progress frame and outlasts the host's limit on a
     * silent call, which is set per host by {@link Host#cutSetting()} (a configured tool timeout on Claude Code and
     * OpenCode, the fixed call limit on Antigravity). The cut is never simulated: it counts only where the relay's
     * host-side log or the stub log shows it ({@link QuestionDriver#callCut}), and is then logged as
     * {@value QuestionDriver#FAULT_INJECTED} naming its evidence; a held call the host does not cut is logged as
     * {@value QuestionDriver#CALL_NOT_CUT}. Before 2026-10-23 the held call lasted 30 s against a tool timeout of 60 s,
     * with a progress frame every 5 s, so no host cut it (R3 smoke attempt, runs {@code 81b08a29}, {@code 613cbeb1} and
     * {@code 9cad3e84}).
     */
    CALL_CUT,
    /** The model request left hanging after the answer: the process tree stopped once the answer is delivered. */
    MODEL_HANG,
    /** The process tree killed while it holds. */
    KILL,
    /** A resumed process that exits before its first call: the resumed generation killed at once. */
    RESUMED_EXIT;

    /**
     * The property that selects the faults of an R3 invocation: a comma-separated list of names, in any case; every
     * fault without it.
     */
    public static final String PROPERTY = "pm.experiment.r3.faults";

    /** The operator's hold of an R3 run, beside {@link #CALL_CUT}. */
    public static final Duration HOLD = Duration.ofSeconds(30);

    /**
     * @return whether the fault stops the worker's whole process tree ({@link QuestionDriver#stopTree}) instead of
     *         killing it
     */
    public boolean stopsTree() {
        return this == STOP_BEFORE_ANSWER || this == MODEL_HANG;
    }

    /** @return whether the fault kills the worker's whole process tree */
    public boolean killsTree() {
        return this == KILL || this == RESUMED_EXIT;
    }

    /** @return the continuation form of the run: resume for {@link #RESUMED_EXIT}, hold for every other fault */
    public Form form() {
        return this == RESUMED_EXIT ? Form.RESUME : Form.HOLD;
    }

    /**
     * @param host the host
     * @return the operator's hold of the run: {@link #HOLD}, for {@link #CALL_CUT} the host's cut plus
     *         {@link Host#CALL_CUT_MARGIN} ({@link Host.CutSetting#hold()}), so the host cuts the held call before the
     *         answer comes
     */
    public Duration hold(Host host) {
        return this == CALL_CUT ? host.cutSetting().hold() : HOLD;
    }

    /**
     * The faults an R3 invocation measures, from {@value #PROPERTY}: every fault, in the order of the enum, without the
     * property; else the named faults, in the same order whatever the order of the list, each once. A re-run of the
     * faults whose injection was defective does not repeat the others.
     *
     * @param value the value of {@value #PROPERTY}, {@code null} when it is not set
     * @return the selected faults, in the order of the enum
     * @throws IllegalArgumentException for a name that is no fault, or a value that names none; the message lists the
     *                                  valid names
     */
    public static List<Fault> select(String value) {
        if (value == null) {
            return List.of(values());
        }
        var valid = String.join(", ", Arrays.stream(values()).map(Fault::name).toList());
        var selected = EnumSet.noneOf(Fault.class);
        for (var name : value.split(",")) {
            if (name.isBlank()) {
                continue;
            }
            var fault = Arrays.stream(values()).filter(candidate -> candidate.name().equalsIgnoreCase(name.strip()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown fault '" + name.strip()
                    + "' in -D" + PROPERTY + "=" + value + "; valid: " + valid));
            selected.add(fault);
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("-D" + PROPERTY + "=" + value + " names no fault; valid: " + valid);
        }
        return List.copyOf(selected);
    }
}
