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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;


import de.planmarshall.e2e.question.stub.QuestionStub.Form;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Fault: the faults of R3 with their placement")
class FaultTest {

    @Test
    @DisplayName("stops the tree for the stop and the hang, kills it for the kill and the resumed exit")
    void treeActions() {
        assertAll(() -> assertTrue(Fault.STOP_BEFORE_ANSWER.stopsTree()),
                () -> assertTrue(Fault.MODEL_HANG.stopsTree()),
                () -> assertFalse(Fault.KILL.stopsTree()),
                () -> assertTrue(Fault.KILL.killsTree()),
                () -> assertTrue(Fault.RESUMED_EXIT.killsTree()),
                () -> assertFalse(Fault.CALL_CUT.stopsTree() || Fault.CALL_CUT.killsTree()));
    }

    @Test
    @DisplayName("continues the resumed exit by resume and every other fault by hold")
    void forms() {
        assertAll(() -> assertEquals(Form.RESUME, Fault.RESUMED_EXIT.form()),
                () -> assertEquals(Form.HOLD, Fault.KILL.form()),
                () -> assertEquals(Form.HOLD, Fault.CALL_CUT.form()));
    }

    @Test
    @DisplayName("holds the call cut past the host's cut and every other fault for the fixed hold")
    void holds() {
        assertAll(() -> assertEquals(Fault.HOLD, Fault.KILL.hold(Host.CLAUDE)),
                () -> assertEquals(Host.OPENCODE.cutSetting().hold(), Fault.CALL_CUT.hold(Host.OPENCODE)),
                () -> assertTrue(Fault.CALL_CUT.hold(Host.CLAUDE).compareTo(Host.CLAUDE.cutSetting().hostCut()) > 0));
    }

    @Test
    @DisplayName("selects every fault without the property and the named ones in enum order otherwise")
    void selects() {
        assertAll(() -> assertEquals(List.of(Fault.values()), Fault.select(null)),
                () -> assertEquals(List.of(Fault.CALL_CUT, Fault.KILL), Fault.select(" kill ,call_cut,,KILL")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nothing", ",", "kill,nothing"})
    @DisplayName("refuses a value that names no fault or an unknown one, listing the valid names")
    void refuses(String value) {
        var failure = assertThrows(IllegalArgumentException.class, () -> Fault.select(value));

        assertTrue(failure.getMessage().contains("STOP_BEFORE_ANSWER"));
    }
}
