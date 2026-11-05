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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.question.driver.Fault;
import de.planmarshall.e2e.question.driver.QuestionDriver;
import de.planmarshall.e2e.question.stub.QuestionStub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ResultLimits: what each figure of a result does and does not show")
class ResultLimitsTest {

    private static ObjectNode result() {
        return QuestionStub.JSON.createObjectNode();
    }

    private static List<String> limits(ObjectNode result) {
        var texts = new ArrayList<String>();
        result.path(ResultLimits.LIMITS).forEach(limit -> texts.add(limit.asText()));
        return texts;
    }

    private static ObjectNode run(String host, String invocation) {
        var run = QuestionStub.JSON.createObjectNode();
        run.put("host", host);
        run.put(QuestionResults.INVOCATION, invocation);
        run.put("stub_dir", "/nonexistent/" + invocation + "/stub");
        return run;
    }

    private static ObjectNode invocation(String runTimeout) {
        var invocation = QuestionStub.JSON.createObjectNode();
        invocation.put(QuestionResults.RUN_TIMEOUT, runTimeout);
        return invocation;
    }

    @Test
    @DisplayName("appends each limit to the result's list")
    void appends() {
        var result = result();

        ResultLimits.limit(result, "one");
        ResultLimits.limit(result, "two");

        assertEquals(List.of("one", "two"), limits(result));
    }

    @Test
    @DisplayName("states the forcing text of a forced item, the controls of Q2 and nothing for an unforced item")
    void fixtures() {
        var q1 = result();
        var q2 = result();
        var r1 = result();

        ResultLimits.limitsOfFixtures(q1, "Q1");
        ResultLimits.limitsOfFixtures(q2, "Q2");
        ResultLimits.limitsOfFixtures(r1, "R1");

        assertAll(() -> assertEquals(2, limits(q1).size()),
                () -> assertTrue(limits(q1).get(1).contains(QuestionDriver.EVIDENCE_EXAMPLE)),
                () -> assertEquals(3, limits(q2).size()),
                () -> assertTrue(limits(r1).isEmpty()));
    }

    @Test
    @DisplayName("states the scenarios, the missing criterion and the drafts of R4")
    void r4() {
        var result = result();

        ResultLimits.limitsOfR4(result, 7);

        assertAll(() -> assertEquals(3, limits(result).size()),
                () -> assertTrue(limits(result).getFirst().contains("The other 7 scenario")));
    }

    @Test
    @DisplayName("names the OpenCode models of the global data home")
    void openCodeDataHome() {
        var result = result();

        ResultLimits.limitOfOpenCodeDataHome(result, List.of("b", "a"));

        assertTrue(limits(result).getFirst().contains("with a, b,"));
    }

    @Test
    @DisplayName("states different and unrecorded run limits, and nothing for one recorded limit")
    void runLimits() {
        var same = result();
        var different = result();
        var unrecorded = result();
        var runs = List.<JsonNode>of(run("claude", "i1"), run("opencode", "i2"));

        ResultLimits.limitOfRunLimits(same, runs, Map.of("i1", invocation("PT20M"), "i2", invocation("PT20M")));
        ResultLimits.limitOfRunLimits(different, runs, Map.of("i1", invocation("PT20M"), "i2", invocation("PT5M")));
        ResultLimits.limitOfRunLimits(unrecorded, List.of(run("claude", "i3")), Map.of());

        assertAll(() -> assertTrue(limits(same).isEmpty()),
                () -> assertTrue(limits(different).getFirst().contains("claude PT20M, opencode PT5M")),
                () -> assertEquals("The run limit of the invocations is not recorded.", limits(unrecorded).getFirst()));
    }

    @Test
    @DisplayName("names the runs continued by hold and states nothing without one")
    void continuationsByHold() {
        var result = result();
        var none = result();
        var held = run("claude", "i1");
        held.put(QuestionDriver.CONTINUED_BY_HOLD, true);

        ResultLimits.limitOfContinuationsByHold(result, List.of(held, run("claude", "i1")));
        ResultLimits.limitOfContinuationsByHold(none, List.of(run("claude", "i1")));

        assertAll(() -> assertTrue(limits(result).getFirst().contains("claude 1")),
                () -> assertTrue(limits(none).isEmpty()));
    }

    @Test
    @DisplayName("states the fixed limits of Q3 and Q5")
    void q3AndQ5() {
        var q3 = result();
        var q5 = result();

        ResultLimits.limitOfQ3Times(q3);
        ResultLimits.limitOfQ5Bound(q5);

        assertAll(() -> assertFalse(limits(q3).isEmpty()), () -> assertFalse(limits(q5).isEmpty()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Q1", "Q2", "Q3", "Q4", "Q5", "Q6", "Q7", "R1", "R2", "R3", "R4"})
    @DisplayName("adds the limits of every item over runs without evidence")
    void addAll(String item) {
        var result = result();
        var runs = List.<JsonNode>of(run("opencode", "i1"));
        var global = invocation("PT20M");
        global.put("opencode_data_home", "global");

        assertDoesNotThrow(() -> ResultLimits.addAll(result, item, runs, Map.of("i1", global), 3));
        assertTrue(limits(result).stream().anyMatch(text -> text.contains("global data home")));
    }

    @Test
    @DisplayName("names failed setups with their reasons and the runs their invocations did not start")
    void setupFailures() {
        var failed = run("claude", "i1");
        failed.put(RunView.SETUP_FAILED, true);
        failed.put("fixture", "f-1");
        failed.put(RunView.SETUP_FAILED_REASON, "fatal: bad ref");
        var stopped = invocation("PT20M");
        stopped.put(QuestionResults.INVOCATION, "i2");
        stopped.put(QuestionResults.SETUP_STOPPED_RUNS, 2);
        var none = result();
        var onlyFailed = result();
        var onlyStopped = result();

        ResultLimits.limitOfSetupFailures(none, List.of(run("claude", "i1")), List.of(invocation("PT20M")));
        ResultLimits.limitOfSetupFailures(onlyFailed, List.of(failed), List.of());
        ResultLimits.limitOfSetupFailures(onlyStopped, List.of(), List.of(stopped));

        assertAll(() -> assertEquals(List.of(), limits(none)),
                () -> assertTrue(limits(onlyFailed).getFirst().contains("i1 (f-1: fatal: bad ref)")),
                () -> assertTrue(limits(onlyFailed).getFirst().endsWith("of that scenario.")),
                () -> assertTrue(limits(onlyStopped).getFirst().contains("i2 2")));
    }

    @Test
    @DisplayName("names the runs the usage limit refused with their reset and the runs not started after them")
    void usageLimit() throws Exception {
        var refused = run("claude", "i1");
        refused.put(RunView.NOT_RUN, true);
        refused.put(QuestionDriver.QUOTA_ERROR, true);
        refused.put(QuestionDriver.QUOTA_RULE, QuestionDriver.RATE_LIMIT_EVENT);
        refused.put(QuestionDriver.RESETS_AT, "2026-11-01T08:00:00Z");
        var unnamed = run("claude", "i3");
        unnamed.put(RunView.NOT_RUN, true);
        var stopped = invocation("PT20M");
        stopped.put(QuestionResults.INVOCATION, "i2");
        stopped.put(QuestionResults.USAGE_LIMIT_STOPPED_RUNS, 5);
        var none = result();
        var onlyRefused = result();
        var onlyStopped = result();
        var quota = result();

        ResultLimits.limitOfUsageLimit(none, List.of(run("claude", "i1")), List.of(invocation("PT20M")));
        ResultLimits.limitOfUsageLimit(onlyRefused, List.of(refused, unnamed), List.of());
        ResultLimits.limitOfUsageLimit(onlyStopped, List.of(), List.of(stopped));
        ResultLimits.limitOfQuotaErrors(quota, List.of(refused));

        assertAll(() -> assertEquals(List.of(), limits(none)),
                () -> assertTrue(limits(onlyRefused).getFirst().contains("i1 (resets 2026-11-01T08:00:00Z)")),
                () -> assertTrue(limits(onlyRefused).getFirst().contains("i3 (resets not named)")),
                () -> assertTrue(limits(onlyRefused).getFirst().endsWith("remaining runs.")),
                () -> assertTrue(limits(onlyStopped).getFirst().contains("i2 5")),
                () -> assertEquals(List.of(), limits(quota)));
    }

    @Test
    @DisplayName("names continuations without a worker and warm askers that exited, each on its own")
    void continuationGaps() {
        var gap = run("claude", "i1");
        gap.put(QuestionDriver.CONTINUATION_GAP, QuestionDriver.HELD_BY_ENDED_GENERATION);
        var exited = run("opencode", "i2");
        exited.put(QuestionDriver.WARM_ASKER_EXITED, true);
        var none = result();
        var gaps = result();
        var exits = result();

        ResultLimits.limitOfContinuationGaps(none, List.of(run("claude", "i3")));
        ResultLimits.limitOfContinuationGaps(gaps, List.of(gap));
        ResultLimits.limitOfContinuationGaps(exits, List.of(exited));

        assertAll(() -> assertEquals(List.of(), limits(none)),
                () -> assertTrue(limits(gaps).getFirst().contains(QuestionDriver.HELD_BY_ENDED_GENERATION)),
                () -> assertFalse(limits(gaps).getFirst().contains("Warm asker exited, continuation")),
                () -> assertTrue(limits(exits).getFirst().contains("Warm asker exited, continuation")),
                () -> assertFalse(limits(exits).getFirst().contains("Continuation without a worker")));
    }

    @Test
    @DisplayName("counts the composed answers and says when none fell back")
    void answerFallbacks() {
        var composed = run("claude", "i1");
        composed.putArray(AnswerAudit.ANSWER_COMPOSITIONS);
        var none = result();
        var noFallback = result();

        ResultLimits.limitOfAnswerFallbacks(none, List.of(run("claude", "i1")));
        ResultLimits.limitOfAnswerFallbacks(noFallback, List.of(composed, run("claude", "i2")));

        assertAll(() -> assertEquals(List.of(), limits(none)),
                () -> assertTrue(limits(noFallback).getFirst().contains("1 of 2 records")),
                () -> assertTrue(limits(noFallback).getFirst().endsWith("no answer fell back.")));
    }

    @Nested
    @DisplayName("the limits of R3 and of continuation early exits")
    class R3AndContinuations {

        private ObjectNode faultRun(Fault fault) {
            var run = run("claude", "i1");
            run.put("fault", fault.name());
            return run;
        }

        private ObjectNode selection(String id, Fault... faults) {
            var invocation = invocation("PT20M");
            invocation.put(QuestionResults.INVOCATION, id);
            var array = invocation.putArray(QuestionResults.R3_FAULTS);
            for (var fault : faults) {
                array.add(fault.name());
            }
            return invocation;
        }

        @Test
        @DisplayName("names a fault selection, the faults without a group, and nothing for a full selection")
        void faultSelection() {
            var partial = result();
            var covered = result();
            var full = result();
            var allRuns = Arrays.stream(Fault.values()).map(fault -> (JsonNode) faultRun(fault)).toList();

            ResultLimits.limitOfR3FaultSelection(partial, List.of(faultRun(Fault.CALL_CUT)),
                    List.of(selection("i1", Fault.CALL_CUT)));
            ResultLimits.limitOfR3FaultSelection(covered, allRuns, List.of(selection("i1", Fault.CALL_CUT)));
            ResultLimits.limitOfR3FaultSelection(full, allRuns, List.of(selection("i2", Fault.values()),
                    invocation("PT20M")));

            assertAll(() -> assertTrue(limits(partial).getFirst().contains("only the faults CALL_CUT were measured "
                            + "by invocation i1")),
                    () -> assertTrue(limits(partial).getFirst().contains("a verdict over the faults CALL_CUT only")),
                    () -> assertTrue(limits(covered).getFirst().endsWith("every fault has a group from the "
                            + "aggregated invocations.")),
                    () -> assertEquals(List.of(), limits(full)));
        }

        @Test
        @DisplayName("counts the stops while holding, the cuts per host, the stub's first losses and its exit handling")
        void r3() {
            var stopped = faultRun(Fault.STOP_BEFORE_ANSWER);
            stopped.put("fault_held", true);
            var cut = faultRun(Fault.CALL_CUT);
            cut.put(QuestionResults.CALL_CUT_FIELD, true);
            cut.put("loss_by", "driver");
            cut.put("loss_first_by", "stub");
            cut.put(QuestionResults.STUB_LOSE_ON_EXIT, false);
            var uncut = faultRun(Fault.CALL_CUT);
            var result = result();
            var none = result();

            ResultLimits.limitsOfR3(result, List.of(stopped, cut, uncut));
            ResultLimits.limitsOfR3(none, List.of());

            var texts = limits(result);
            assertAll(() -> assertTrue(texts.get(0).endsWith("Stopped while holding: 1 of 1 runs.")),
                    () -> assertTrue(texts.get(1).contains("Cut: claude 1 of 2.")),
                    () -> assertTrue(texts.get(2).startsWith(ResultLimits.CUT_SURVIVED_LIMIT)),
                    () -> assertTrue(texts.get(3).startsWith("In 1 of 1 runs")),
                    () -> assertTrue(texts.get(4).endsWith("Runs with this stub: 1 of 3.")),
                    () -> assertTrue(limits(none).get(1).contains("Cut: no run.")),
                    () -> assertEquals(4, limits(none).size()));
        }

        @Test
        @DisplayName("names the runs whose continuation exited early, with the groups whose verdict differs")
        void continuationEarlyExits() throws Exception {
            var exited = run("claude", "i1");
            exited.put(QuestionDriver.CONTINUATION_EARLY_EXIT, true);
            var differing = result();
            differing.putArray(QuestionResults.GROUPS).addObject().put(QuestionResults.GROUP, "g-1")
                    .put(QuestionResults.VERDICT_WITHOUT_CONTINUATION_EARLY_EXIT, "pass");
            ((ArrayNode) differing.path(QuestionResults.GROUPS)).addObject().put(QuestionResults.GROUP, "g-2");
            var same = result();
            var none = result();

            ResultLimits.limitOfContinuationEarlyExits(differing, List.of(exited, run("claude", "i2")));
            ResultLimits.limitOfContinuationEarlyExits(same, List.of(exited));
            ResultLimits.limitOfContinuationEarlyExits(none, List.of(run("claude", "i2")));

            assertAll(() -> assertTrue(limits(differing).getFirst().contains("claude 1")),
                    () -> assertTrue(limits(differing).getFirst().endsWith("where it reads differently: g-1.")),
                    () -> assertTrue(limits(same).getFirst().endsWith("no group's verdict reads differently without "
                            + "them.")),
                    () -> assertEquals(List.of(), limits(none)));
        }
    }
}
