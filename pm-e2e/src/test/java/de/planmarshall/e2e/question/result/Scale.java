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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The revised scale of the measurement, as the work package {@code question-loop-follow-up} and the Evaluation
 * Reference fix it before any measured run (operator decisions of 2026-10-28 to 2026-10-31):
 * <ul>
 * <li>A group (fixture, host, model, form, continuation and the item's measured parameter) runs
 * {@value #RUNS_PER_GROUP} times. It passes when every run passes ({@link #verdict(int, int)}).</li>
 * <li>A group whose runs disagree is extended to {@value #EXTENDED_RUNS} runs and reported with its rate; at that
 * count a disagreeing group fails, since not every run passed.</li>
 * <li>Q5 runs its question bounds sequentially: bound {@value #Q5_FIRST_BOUND} first, then bounds 4 and 5 only where a
 * run of the bound before reached the bound ({@link #nextQ5Bound(int, boolean)}).</li>
 * <li>R1 and R2 run longest first: a hold of {@link #R1_R2_DURATIONS 3,600 s} (R2: a resume after 1 h, at most), and
 * descend through 30, 15 and 5 minutes only on a failure ({@link #nextDuration(long, boolean)}).</li>
 * </ul>
 * A verdict is stated with its run count and the client version it was measured on ({@link #statement}).
 */
public final class Scale {

    /** The runs per group at the revised scale. */
    public static final int RUNS_PER_GROUP = 3;
    /** The runs a disagreeing group is extended to. */
    public static final int EXTENDED_RUNS = 10;
    /** The first question bound of Q5. */
    public static final int Q5_FIRST_BOUND = 3;
    /** The last question bound of Q5. */
    public static final int Q5_LAST_BOUND = 5;
    /** The durations of R1 (hold) and the ages of R2 (resume), in seconds, longest first. */
    public static final List<Long> R1_R2_DURATIONS = List.of(Duration.ofHours(1).toSeconds(),
            Duration.ofMinutes(30).toSeconds(), Duration.ofMinutes(15).toSeconds(), Duration.ofMinutes(5).toSeconds());
    /** The text of a client version no run recorded. */
    public static final String VERSION_NOT_RECORDED = "client version not recorded";

    /** The verdict of a group at the revised scale. */
    public enum Verdict {
        /** Every run passed. */
        PASS,
        /** No run passed, or the runs disagreed at {@value #EXTENDED_RUNS} runs or more. */
        FAIL,
        /** The runs disagreed with fewer than {@value #EXTENDED_RUNS} runs: extend the group to that count. */
        EXTEND,
        /** No run to judge, or an item without a criterion (R4). */
        NONE;

        /** @return the verdict as the result files name it */
        public String text() {
            return this == EXTEND ? "extend to " + EXTENDED_RUNS : name().toLowerCase(Locale.ROOT);
        }
    }

    private Scale() {
    }

    /**
     * @param runs    the runs the group judged
     * @param passing those of them that passed
     * @return {@link Verdict#PASS} when every run passed, {@link Verdict#NONE} without runs, {@link Verdict#FAIL} when
     *         none passed, else {@link Verdict#EXTEND} below {@value #EXTENDED_RUNS} runs and {@link Verdict#FAIL} at
     *         or above it
     * @throws IllegalArgumentException if a count is negative or more runs passed than ran
     */
    public static Verdict verdict(int runs, int passing) {
        if (runs < 0 || passing < 0 || passing > runs) {
            throw new IllegalArgumentException("runs " + runs + ", passing " + passing);
        }
        if (runs == 0) {
            return Verdict.NONE;
        }
        if (passing == runs) {
            return Verdict.PASS;
        }
        if (passing == 0) {
            return Verdict.FAIL;
        }
        return runs < EXTENDED_RUNS ? Verdict.EXTEND : Verdict.FAIL;
    }

    /**
     * @param passes the verdict of each run of a group, in order
     * @return the group's verdict ({@link #verdict(int, int)})
     */
    public static Verdict verdict(Collection<Boolean> passes) {
        return verdict(passes.size(), (int) passes.stream().filter(Boolean::booleanValue).count());
    }

    /**
     * @param verdict  a group's or an item's verdict
     * @param runs     the runs it rests on
     * @param passing  those of them that passed
     * @param versions the client versions the runs recorded
     * @return the verdict stated with its run count, its rate where the runs disagree, and the client versions, as
     *         {@code pass, 3 runs, client 2.1.0}
     */
    public static String statement(Verdict verdict, int runs, int passing, Collection<String> versions) {
        var text = new StringBuilder(verdict.text()).append(", ").append(runs).append(runs == 1 ? " run" : " runs");
        if (passing > 0 && passing < runs) {
            text.append(", ").append(passing).append(" of ").append(runs).append(" passed");
        }
        var named = new TreeSet<String>();
        versions.stream().filter(version -> version != null && !version.isBlank()).forEach(named::add);
        text.append(", ").append(named.isEmpty() ? VERSION_NOT_RECORDED : "client " + String.join(" and ", named));
        return text.toString();
    }

    /**
     * The next question bound of Q5 after a bound was run: the bounds run sequentially, and the next one runs only
     * where a run of this bound reached the bound.
     *
     * @param bound   the bound just run
     * @param reached whether a run of it reached the bound
     * @return the next bound, empty when none is run
     */
    public static Optional<Integer> nextQ5Bound(int bound, boolean reached) {
        return reached && bound < Q5_LAST_BOUND ? Optional.of(bound + 1) : Optional.empty();
    }

    /**
     * The question bounds of Q5 that are run, from the bounds whose runs reached the bound.
     *
     * @param reached the bounds of which a run reached the bound
     * @return the bounds run, in order: {@value #Q5_FIRST_BOUND}, then each next one while the one before was reached
     */
    public static List<Integer> q5Bounds(Collection<Integer> reached) {
        var bounds = new ArrayList<Integer>();
        Optional<Integer> bound = Optional.of(Q5_FIRST_BOUND);
        while (bound.isPresent()) {
            bounds.add(bound.get());
            bound = nextQ5Bound(bound.get(), reached.contains(bound.get()));
        }
        return bounds;
    }

    /**
     * The next duration of R1 or age of R2 after one was run: longest first, descending only on a failure.
     *
     * @param seconds the duration or age just run
     * @param passed  whether its group passed
     * @return the next shorter one after a failure, empty after a pass or after the shortest
     * @throws IllegalArgumentException if the duration is none of {@link #R1_R2_DURATIONS}
     */
    public static Optional<Long> nextDuration(long seconds, boolean passed) {
        var index = R1_R2_DURATIONS.indexOf(seconds);
        if (index < 0) {
            throw new IllegalArgumentException("no duration of the revised scale: " + seconds + " s");
        }
        return passed || index == R1_R2_DURATIONS.size() - 1 ? Optional.empty()
                : Optional.of(R1_R2_DURATIONS.get(index + 1));
    }
}
