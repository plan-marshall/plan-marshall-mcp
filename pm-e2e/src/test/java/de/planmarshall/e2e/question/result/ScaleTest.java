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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.Set;


import de.planmarshall.e2e.question.result.Scale.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Scale: the revised scale of the measurement")
class ScaleTest {

    @ParameterizedTest
    @CsvSource({"0,0,NONE", "3,3,PASS", "3,0,FAIL", "3,2,EXTEND", "10,9,FAIL", "10,10,PASS"})
    @DisplayName("passes a group whose every run passed and extends one whose runs disagree")
    void verdicts(int runs, int passing, Verdict expected) {
        assertEquals(expected, Scale.verdict(runs, passing));
    }

    @Test
    @DisplayName("judges the passes of each run and refuses impossible counts")
    void verdictOfPasses() {
        assertAll(() -> assertEquals(Verdict.EXTEND, Scale.verdict(List.of(true, false, true))),
                () -> assertThrows(IllegalArgumentException.class, () -> Scale.verdict(2, 3)),
                () -> assertThrows(IllegalArgumentException.class, () -> Scale.verdict(-1, 0)),
                () -> assertEquals("extend to " + Scale.EXTENDED_RUNS, Verdict.EXTEND.text()),
                () -> assertEquals("pass", Verdict.PASS.text()));
    }

    @Test
    @DisplayName("states a verdict with its run count, its rate and the client versions")
    void statements() {
        assertAll(() -> assertEquals("pass, 3 runs, client 2.1.0",
                        Scale.statement(Verdict.PASS, 3, 3, List.of("2.1.0", "2.1.0"))),
                () -> assertEquals("extend to 10, 3 runs, 2 of 3 passed, client 1 and 2",
                        Scale.statement(Verdict.EXTEND, 3, 2, List.of("2", "1", " "))),
                () -> assertEquals("none, 1 run, " + Scale.VERSION_NOT_RECORDED,
                        Scale.statement(Verdict.NONE, 1, 0, List.of())));
    }

    @Test
    @DisplayName("runs the Q5 bounds sequentially while a bound was reached")
    void q5Bounds() {
        assertAll(() -> assertEquals(Optional.of(4), Scale.nextQ5Bound(3, true)),
                () -> assertEquals(Optional.empty(), Scale.nextQ5Bound(3, false)),
                () -> assertEquals(Optional.empty(), Scale.nextQ5Bound(Scale.Q5_LAST_BOUND, true)),
                () -> assertEquals(List.of(3, 4, 5), Scale.q5Bounds(Set.of(3, 4, 5))),
                () -> assertEquals(List.of(3), Scale.q5Bounds(Set.of())));
    }

    @Test
    @DisplayName("descends R1 and R2 only on a failure, from the longest duration")
    void durations() {
        assertAll(() -> assertEquals(Optional.of(1800L), Scale.nextDuration(3600, false)),
                () -> assertEquals(Optional.empty(), Scale.nextDuration(3600, true)),
                () -> assertEquals(Optional.empty(), Scale.nextDuration(300, false)),
                () -> assertThrows(IllegalArgumentException.class, () -> Scale.nextDuration(60, false)));
    }
}
