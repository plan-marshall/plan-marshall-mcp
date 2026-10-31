/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.e2e.question;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


import de.planmarshall.e2e.question.QuestionLoopMeasurement.Config;
import de.planmarshall.e2e.question.QuestionLoopMeasurement.RunPlan;
import de.planmarshall.e2e.question.QuestionLoopMeasurement.SetupStops;
import de.planmarshall.e2e.question.QuestionLoopMeasurement.Warmth;
import de.planmarshall.e2e.question.driver.Fault;
import de.planmarshall.e2e.question.driver.Host;
import de.planmarshall.e2e.question.driver.QuestionDriver;
import de.planmarshall.e2e.question.driver.QuestionDriver.OpenCodeDataHome;
import de.planmarshall.e2e.question.fixture.QuestionFixtures;
import de.planmarshall.e2e.question.fixture.QuestionFixtures.Fixture;
import de.planmarshall.e2e.question.fixture.QuestionFixtures.Reply;
import de.planmarshall.e2e.question.result.QuestionResults;
import de.planmarshall.e2e.question.result.RunView;
import de.planmarshall.e2e.question.result.Scale;
import de.planmarshall.e2e.question.stub.QuestionStub;
import de.planmarshall.e2e.question.stub.QuestionStub.Form;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("QuestionLoopMeasurement: the plans, records and runs of one invocation")
class QuestionLoopMeasurementTest {

    @TempDir
    Path directory;

    private Config config(List<Host> hosts, int runs, int largeRuns, Warmth warmth, OpenCodeDataHome dataHome,
            List<String> exec) {
        return new Config(hosts, Config.models(dataHome), runs, Integer.MAX_VALUE, 1, directory.resolve("cache"),
                directory.resolve("telemetry"), QuestionFixtures.TELEMETRY_REF, directory.resolve("clones"), largeRuns,
                directory.resolve("workspace"), directory.resolve("evidence"), directory.resolve("results"), exec,
                Duration.ofSeconds(5), warmth, dataHome, Scale.Q5_FIRST_BOUND);
    }

    private Config config() {
        return config(List.of(Host.CLAUDE, Host.OPENCODE), 2, 1, Warmth.BOTH, OpenCodeDataHome.GLOBAL,
                List.of("pm-exec"));
    }

    private static RunPlan plan(String item, Host host) {
        return RunPlan.of(item, 0, host, null, false, Form.FALLBACK, QuestionFixtures.probe(), "entity");
    }

    private static Fixture control() {
        return new Fixture("control/one", "control", "task", "reply", null, true, null);
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("refuses negative run counts and a parallel level below one")
        void refusesInvalidCounts() {
            assertAll(() -> assertThrows(IllegalArgumentException.class,
                            () -> config(List.of(Host.CLAUDE), -1, 0, Warmth.BOTH, OpenCodeDataHome.GLOBAL, List.of())),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> config(List.of(Host.CLAUDE), 1, -1, Warmth.BOTH, OpenCodeDataHome.GLOBAL,
                                    List.of())),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Config(List.of(), Map.of(), 1, 1, 0,
                            directory, directory, "", directory, 0, directory, directory, directory, List.of(),
                            Duration.ZERO, Warmth.BOTH, OpenCodeDataHome.GLOBAL, 3)));
        }

        @ParameterizedTest
        @CsvSource({"fresh,FRESH", "' WARM ',WARM", "both,BOTH"})
        @DisplayName("reads the warmth in any case")
        void readsWarmth(String value, Warmth expected) {
            assertEquals(expected, Warmth.of(value));
        }

        @Test
        @DisplayName("plans fresh and warm without the warmth, and refuses an unknown one")
        void warmthDefaults() {
            assertAll(() -> assertEquals(Warmth.BOTH, Warmth.of(null)),
                    () -> assertEquals(List.of(false, true), Warmth.BOTH.planned()),
                    () -> assertThrows(IllegalArgumentException.class, () -> Warmth.of("lukewarm")));
        }

        @Test
        @DisplayName("runs the default model of each host on its data home")
        void defaultModels() {
            assertAll(() -> assertEquals(QuestionLoopMeasurement.CLAUDE_MODEL,
                            QuestionLoopMeasurement.defaultModel(Host.CLAUDE, OpenCodeDataHome.WORKER)),
                    () -> assertEquals(QuestionLoopMeasurement.OPENCODE_GLOBAL_MODEL,
                            QuestionLoopMeasurement.defaultModel(Host.OPENCODE, OpenCodeDataHome.GLOBAL)),
                    () -> assertNull(QuestionLoopMeasurement.defaultModel(Host.OPENCODE, OpenCodeDataHome.WORKER)),
                    () -> assertNull(QuestionLoopMeasurement.defaultModel(Host.ANTIGRAVITY, OpenCodeDataHome.GLOBAL)),
                    () -> assertFalse(Config.models(OpenCodeDataHome.GLOBAL).containsKey(Host.ANTIGRAVITY)));
        }

        @Test
        @DisplayName("assigns the quorum roles of Q6 to Claude Code and OpenCode")
        void quorumAssignments() {
            assertAll(() -> assertEquals("claude:" + QuestionLoopMeasurement.CLAUDE_MODEL,
                            QuestionLoopMeasurement.q6Assignment("quorum-a")),
                    () -> assertTrue(QuestionLoopMeasurement.q6Assignment("quorum-b").startsWith("opencode:")));
        }

        @Test
        @DisplayName("reads durations in the order named, the longest of the revised scale by default")
        void readsDurations() {
            assertEquals(List.of(Duration.ofHours(1)),
                    QuestionLoopMeasurement.durations("pm.experiment.test.none", QuestionLoopMeasurement.LONGEST));
        }
    }

    @Nested
    @DisplayName("plans")
    class Plans {

        @Test
        @DisplayName("plans Q1 with entity and envelope runs and a control with entity runs only")
        void plansApplyAnswer() {
            var plans = QuestionLoopMeasurement.applyAnswer("Q1", config(), List.of(QuestionFixtures.probe(),
                    control()), Optional.empty());

            assertAll(() -> assertEquals(2 * 2 * 2 * 3, plans.size()),
                    () -> assertEquals(16, plans.stream().filter(plan -> "control/one".equals(plan.fixture().id()))
                            .count() * 2),
                    () -> assertTrue(plans.stream().allMatch(plan -> plan.form() == Form.FALLBACK)));
        }

        @Test
        @DisplayName("rotates the reply of Q4 and continues every second run on the other host")
        void plansQ4() {
            var plans = QuestionLoopMeasurement.applyAnswer("Q4", config(), List.of(QuestionFixtures.probe()),
                    Optional.of(Host.ANTIGRAVITY));

            assertAll(() -> assertEquals(Reply.OPTION, plans.getFirst().reply()),
                    () -> assertTrue(plans.stream().filter(plan -> plan.index() == 1)
                            .allMatch(plan -> plan.continueHost() == Host.ANTIGRAVITY && plan.reply() == Reply.TEXT)),
                    () -> assertTrue(plans.stream().filter(plan -> plan.index() == 0)
                            .allMatch(plan -> plan.continueHost() == null)));
        }

        @Test
        @DisplayName("plans Q5 with a first-only run and a bounded run per group")
        void plansQ5() {
            var plans = QuestionLoopMeasurement.q5(config(), List.of(QuestionFixtures.probe()));

            assertAll(() -> assertEquals(16, plans.size()),
                    () -> assertEquals(8, plans.stream().filter(RunPlan::bound).count()),
                    () -> assertTrue(plans.stream().filter(RunPlan::bound)
                            .allMatch(plan -> plan.reply() == Reply.RESTATE_OPTIONS)));
        }

        @Test
        @DisplayName("plans Q6 with a vote and a consult run, Q7 fresh on Claude Code with a restart")
        void plansQ6AndQ7() {
            var q6 = QuestionLoopMeasurement.q6(config(), List.of(QuestionFixtures.probe()));
            var q7 = QuestionLoopMeasurement.q7(config(), List.of(QuestionFixtures.probe()), Duration.ofMinutes(1));

            assertAll(() -> assertEquals(8, q6.stream().filter(plan -> "vote".equals(plan.kind())).count()),
                    () -> assertEquals(8, q6.stream().filter(plan -> "consult".equals(plan.kind())).count()),
                    () -> assertEquals(2, q7.size()),
                    () -> assertTrue(q7.stream().allMatch(plan -> plan.host() == Host.CLAUDE && plan.restart()
                            && !plan.warm() && QuestionLoopMeasurement.endsAskerBeforeOpenTime(plan))));
        }

        @Test
        @DisplayName("plans R1 holds and R2 resumes on the probe, with the restart and the missing session")
        void plansR1AndR2() {
            var r1 = QuestionLoopMeasurement.r1(config(), List.of(Duration.ofHours(1), Duration.ofMinutes(30)));
            var r2 = QuestionLoopMeasurement.r2(config(), List.of(Duration.ofHours(1)), Optional.of("expired"));

            assertAll(() -> assertEquals(16, r1.size()),
                    () -> assertTrue(r1.stream().allMatch(plan -> plan.form() == Form.HOLD)),
                    () -> assertEquals(8 * 4, r2.size()),
                    () -> assertEquals(8, r2.stream().filter(RunPlan::restart).count()),
                    () -> assertEquals(8, r2.stream().filter(plan -> "expired".equals(plan.negative())).count()),
                    () -> assertTrue(r2.stream().filter(plan -> plan.host() == Host.OPENCODE
                            && plan.negative() != null && !"expired".equals(plan.negative()))
                            .allMatch(plan -> plan.negative().startsWith("ses_"))));
        }

        @Test
        @DisplayName("plans R3 with the selected faults only, each in its form and with its hold")
        void plansR3() {
            var plans = QuestionLoopMeasurement.r3(config(), List.of(QuestionFixtures.probe()),
                    List.of(Fault.RESUMED_EXIT, Fault.KILL));

            assertAll(() -> assertEquals(16, plans.size()),
                    () -> assertTrue(plans.stream().filter(plan -> plan.fault() == Fault.RESUMED_EXIT)
                            .allMatch(plan -> plan.form() == Form.RESUME)),
                    () -> assertTrue(plans.stream().filter(plan -> plan.fault() == Fault.KILL)
                            .allMatch(plan -> plan.form() == Form.HOLD && plan.hold().equals(Fault.HOLD))));
        }

        @Test
        @DisplayName("plans R4 small work in every form and loads the large work only when it has runs")
        void plansR4() throws Exception {
            var small = QuestionLoopMeasurement.r4(config(List.of(Host.CLAUDE), 1, 0, Warmth.FRESH,
                    OpenCodeDataHome.GLOBAL, List.of()), () -> {
                throw new IOException("large work loaded");
            });
            var large = QuestionLoopMeasurement.r4(config(List.of(Host.CLAUDE), 0, 2, Warmth.FRESH,
                    OpenCodeDataHome.GLOBAL, List.of()), () -> List.of(QuestionFixtures.probe()));

            assertAll(() -> assertEquals(3, small.size()),
                    () -> assertEquals(4, large.size()),
                    () -> assertTrue(large.stream().noneMatch(plan -> plan.form() == Form.FALLBACK)));
        }

        @Test
        @DisplayName("plans R1 by the item name and refuses an unknown item")
        void plansByItem() throws Exception {
            var plans = QuestionLoopMeasurement.plans("R1", config());

            assertAll(() -> assertEquals(8, plans.size()),
                    () -> assertTrue(plans.stream().allMatch(plan -> plan.hold().equals(Duration.ofHours(1)))),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> QuestionLoopMeasurement.plans("Q9", config())));
        }

        @Test
        @DisplayName("knows which runs start OpenCode and which models they run")
        void openCodeRuns() {
            var claude = plan("Q1", Host.CLAUDE);
            var continued = claude.continuingOn(Host.OPENCODE);
            var vote = RunPlan.of("Q6", 0, Host.CLAUDE, null, false, Form.FALLBACK, QuestionFixtures.probe(), "vote");

            assertAll(() -> assertFalse(QuestionLoopMeasurement.usesOpenCode(claude)),
                    () -> assertTrue(QuestionLoopMeasurement.usesOpenCode(plan("Q1", Host.OPENCODE))),
                    () -> assertTrue(QuestionLoopMeasurement.usesOpenCode(continued)),
                    () -> assertTrue(QuestionLoopMeasurement.usesOpenCode(vote)),
                    () -> assertEquals(Set.of(QuestionLoopMeasurement.OPENCODE_GLOBAL_MODEL),
                            QuestionLoopMeasurement.openCodeModels(List.of(continued), config())),
                    () -> assertTrue(QuestionLoopMeasurement.openCodeModels(List.of(vote), config()).stream()
                            .anyMatch(model -> model.startsWith("opencode-go/"))),
                    () -> assertTrue(QuestionLoopMeasurement.serialOpenCode(config())));
        }
    }

    @Nested
    @DisplayName("stub settings, launches and records")
    class Records {

        @Test
        @DisplayName("gives a Q5 stub the question bound, an R3 stub no loss at exit and a vote stub its quorums")
        void stubSettings() {
            var socket = Path.of("/tmp", "pmq-test.sock");
            var q5 = QuestionLoopMeasurement.stubSettings(plan("Q5", Host.CLAUDE), config(), directory, socket);
            var r3 = QuestionLoopMeasurement.stubSettings(plan("R3", Host.CLAUDE).faulting(Fault.CALL_CUT), config(),
                    directory, socket);
            var vote = QuestionLoopMeasurement.stubSettings(RunPlan.of("Q6", 0, Host.CLAUDE, null, false,
                    Form.FALLBACK, QuestionFixtures.probe(), "vote"), config(), directory, socket);
            var plain = QuestionLoopMeasurement.stubSettings(plan("Q1", Host.CLAUDE), config(), directory, socket);

            assertAll(() -> assertEquals(Scale.Q5_FIRST_BOUND, q5.questionBound()),
                    () -> assertFalse(r3.loseOnExit()),
                    () -> assertEquals(Host.CLAUDE.cutSetting().window(Host.CLAUDE), r3.window()),
                    () -> assertFalse(vote.quorums().isEmpty()),
                    () -> assertTrue(plain.loseOnExit()),
                    () -> assertEquals(Host.CLAUDE.boundedWait(), plain.window()),
                    () -> assertEquals(QuestionStub.QUESTION_BOUND, plain.questionBound()));
        }

        @Test
        @DisplayName("launches the call cut with the host's tool timeout and every other run with the default")
        void toolTimeouts() {
            assertAll(() -> assertEquals(Host.CLAUDE.cutSetting().toolTimeout(),
                            QuestionLoopMeasurement.toolTimeout(plan("R3", Host.CLAUDE).faulting(Fault.CALL_CUT))),
                    () -> assertNull(QuestionLoopMeasurement.toolTimeout(plan("R3", Host.CLAUDE)
                            .faulting(Fault.KILL))));
        }

        @Test
        @DisplayName("launches workers with their host's work tools and voters without")
        void launches() {
            var worker = QuestionLoopMeasurement.workerLaunch("w1", 2, "implement", Host.OPENCODE, true, null);
            var voter = QuestionLoopMeasurement.voterLaunch("vote-s-quorum-a", "quorum-a");

            assertAll(() -> assertEquals(QuestionLoopMeasurement.workTools(Host.OPENCODE), worker.workTools()),
                    () -> assertTrue(worker.prompt().endsWith(QuestionLoopMeasurement.WORKTREE_NOTE)),
                    () -> assertTrue(worker.resumable()),
                    () -> assertTrue(voter.workTools().isEmpty()),
                    () -> assertTrue(voter.prompt().contains("submit:vote")),
                    () -> assertTrue(QuestionLoopMeasurement.workTools(Host.ANTIGRAVITY).isEmpty()),
                    () -> assertEquals(5, QuestionLoopMeasurement.workTools(Host.CLAUDE).size()));
        }

        @Test
        @DisplayName("records the plan, the parallel level and the question bound of a Q5 run")
        void runRecord() {
            var record = QuestionLoopMeasurement.runRecord(plan("Q5", Host.CLAUDE).bounded()
                    .with(Reply.RESTATE_OPTIONS), config(), directory);
            var r3 = QuestionLoopMeasurement.runRecord(plan("R3", Host.CLAUDE).faulting(Fault.KILL)
                    .continuingOn(Host.OPENCODE).resumingSession("s"), config(), directory);

            assertAll(() -> assertEquals(1, record.path(QuestionResults.PARALLEL).asInt()),
                    () -> assertEquals(Scale.Q5_FIRST_BOUND, record.path(QuestionResults.QUESTION_BOUND).asInt()),
                    () -> assertEquals(RunView.HARNESS_DEFAULT, record.path("model").asText()),
                    () -> assertEquals("restate_options", record.path("reply").asText()),
                    () -> assertTrue(record.path("bound").asBoolean()),
                    () -> assertFalse(r3.has(QuestionResults.QUESTION_BOUND)),
                    () -> assertEquals("KILL", r3.path("fault").asText()),
                    () -> assertEquals("opencode", r3.path("continue_host").asText()),
                    () -> assertTrue(r3.path("negative").asBoolean()));
        }

        @Test
        @DisplayName("records the settings of an invocation with its planned runs")
        void invocation() {
            var q5Plans = QuestionLoopMeasurement.q5(config(), List.of(QuestionFixtures.probe()));
            var r3Plans = QuestionLoopMeasurement.r3(config(), List.of(QuestionFixtures.probe()),
                    List.of(Fault.KILL, Fault.CALL_CUT));

            var q5 = QuestionLoopMeasurement.invocation("Q5", config(), q5Plans);
            var r3 = QuestionLoopMeasurement.invocation("R3", config(), r3Plans);

            assertAll(() -> assertTrue(q5.path(QuestionResults.INVOCATION).asText().startsWith("Q5-")),
                    () -> assertEquals(q5Plans.size(), q5.path(QuestionResults.PLANNED_RUNS).asInt()),
                    () -> assertEquals(1, q5.path(QuestionResults.PARALLEL).asInt()),
                    () -> assertEquals(Scale.Q5_FIRST_BOUND, q5.path(QuestionResults.QUESTION_BOUND).asInt()),
                    () -> assertTrue(q5.path("fixtures_limit").isNull()),
                    () -> assertTrue(q5.path("opencode_serial").asBoolean()),
                    () -> assertEquals("[\"CALL_CUT\",\"KILL\"]", r3.path(QuestionResults.R3_FAULTS).toString()),
                    () -> assertFalse(q5.has(QuestionResults.R3_FAULTS)));
        }

        @Test
        @DisplayName("records a limited fixture count")
        void invocationWithFixtureLimit() {
            var limited = new Config(List.of(Host.CLAUDE), Map.of(), 1, 2, 1, directory, directory, "", directory, 0,
                    directory, directory, directory, List.of(), Duration.ofSeconds(1), Warmth.FRESH,
                    OpenCodeDataHome.WORKER, 3);

            var record = QuestionLoopMeasurement.invocation("R1", limited, List.of(plan("R1", Host.CLAUDE)));

            assertAll(() -> assertEquals(2, record.path("fixtures_limit").asInt()),
                    () -> assertFalse(record.path("opencode_serial").asBoolean()));
        }

        @Test
        @DisplayName("stops the remaining runs of a scenario whose setup failed and records the counts")
        void setupStops() {
            var stops = new SetupStops();
            var record = QuestionStub.JSON.createObjectNode();

            var before = stops.stop("s1");
            stops.failed("s1");
            QuestionLoopMeasurement.recordSetupFailure(record, new QuestionFixtures.SetupFailure("no clone",
                    "the clone is missing", null));
            var after = stops.stop("s1");
            var invocation = stops.applyTo(QuestionStub.JSON.createObjectNode());

            assertAll(() -> assertFalse(before), () -> assertTrue(after),
                    () -> assertEquals(1, invocation.path(RunView.SETUP_FAILED).asInt()),
                    () -> assertEquals(1, invocation.path(QuestionResults.SETUP_STOPPED_RUNS).asInt()),
                    () -> assertTrue(invocation.path("setup_stop_message").asText().contains("s1")),
                    () -> assertTrue(record.path(RunView.SETUP_FAILED).asBoolean()),
                    () -> assertEquals("no clone", record.path(RunView.SETUP_FAILED_REASON).asText()));
        }

        @Test
        @DisplayName("keeps a run's stub logs below the invocation's evidence root")
        void stubDirectory() {
            assertEquals(directory.resolve("evidence").resolve("run-1").resolve("stub"),
                    QuestionLoopMeasurement.stubDirectory(config(), "run-1"));
        }
    }

    @Nested
    @DisplayName("a run whose harness fails to launch")
    class FailedLaunch {

        private static final String EXECUTABLE = Host.EXECUTABLE_PROPERTY + "claude";

        private Path script(String name, String body) throws IOException {
            var file = directory.resolve(name);
            Files.writeString(file, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
            assertTrue(file.toFile().setExecutable(true));
            return file;
        }

        @Test
        @DisplayName("is recorded as launch_failed with the client version, never as a timeout, and gets a result")
        void recordsLaunchFailure() throws Exception {
            var exec = script("pm-exec", "echo 'harness refused' >&2\nexit 3");
            var claude = script("claude", "echo 9.9.9-fake");
            var previous = System.getProperty(EXECUTABLE);
            System.setProperty(EXECUTABLE, claude.toString());
            try {
                var config = config(List.of(Host.CLAUDE), 1, 0, Warmth.FRESH, OpenCodeDataHome.GLOBAL,
                        List.of(exec.toString()));
                var plans = QuestionLoopMeasurement.r1(config, List.of(Duration.ofSeconds(1)));
                var target = new QuestionResults.ResultTarget(directory.resolve("verification"), "launch", Set.of(), true);

                var file = QuestionLoopMeasurement.measure("R1", config, plans, target);

                var run = QuestionResults.runs(config.results(), "R1").getFirst();
                assertAll(() -> assertTrue(Files.isRegularFile(file)),
                        () -> assertTrue(run.path(QuestionDriver.LAUNCH_FAILED).asBoolean(), run::toString),
                        () -> assertEquals(3, run.path("launch_exit").asInt()),
                        () -> assertFalse(run.path(QuestionResults.TIMED_OUT).asBoolean()),
                        () -> assertEquals("9.9.9-fake", run.path(QuestionResults.CLIENT_VERSION).asText()),
                        () -> assertEquals(1, run.path(QuestionResults.PARALLEL).asInt()),
                        () -> assertEquals(1, run.path("workers").size()));
            } finally {
                if (previous == null) {
                    System.clearProperty(EXECUTABLE);
                } else {
                    System.setProperty(EXECUTABLE, previous);
                }
            }
        }
    }
}
