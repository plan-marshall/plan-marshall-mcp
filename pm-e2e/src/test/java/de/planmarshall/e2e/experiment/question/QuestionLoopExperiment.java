/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.e2e.experiment.question;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.experiment.question.QuestionDriver.Fault;
import de.planmarshall.e2e.experiment.question.QuestionDriver.Host;
import de.planmarshall.e2e.experiment.question.QuestionDriver.Launch;
import de.planmarshall.e2e.experiment.question.QuestionDriver.Worker;
import de.planmarshall.e2e.experiment.question.QuestionFixtures.Fixture;
import de.planmarshall.e2e.experiment.question.QuestionFixtures.Reply;
import de.planmarshall.e2e.experiment.question.QuestionStub.Form;
import de.planmarshall.e2e.experiment.question.QuestionStub.Quorum;
import de.planmarshall.e2e.experiment.question.QuestionStub.TaskSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The entry of the measurement (experiment only): runs one item of the work package, named by
 * {@code -Dpm.experiment.question=<item>} ({@code Q1} to {@code Q7}, {@code R1} to {@code R4}), with 20 runs per
 * fixture, host and form, and writes its figures and verdict to
 * {@code target/verification-results/question-loop/<item>.json}. Without the property it is disabled, so no regular
 * build runs it; it needs the harness logins on the operator's machine, the corpus cache, and for the large work of R4
 * a local checkout of {@code cuioss/plan-marshall-telemetry} with the branch {@code verification/question-items}
 * (read only) and the local clones of the scenarios' repositories (read only).
 * <p>
 * R1 and R2 run on the probe only. R4 runs the probe as small work with every continuation form and
 * {@code pm.experiment.runs} runs, and as large work the two scenarios of {@link QuestionFixtures#LARGE_SCENARIOS}
 * with {@code pm.experiment.r4.large-runs} (3) runs per host in the continuation forms hold and resume, whose tokens
 * and time R4 records. Either count may be {@code 0}: that part plans no run (the large work is then not read), so R4
 * runs as two invocations, small work only ({@code -Dpm.experiment.r4.large-runs=0}) and large work only
 * ({@code -Dpm.experiment.runs=0}).
 * <p>
 * Properties: {@code pm.experiment.hosts} (default {@code claude,opencode,antigravity}; Q7 runs on Claude Code
 * only), {@code pm.experiment.model.<host>} (default {@code claude-haiku-4-5} for Claude Code, the harness default
 * otherwise), {@code pm.experiment.runs} (20), {@code pm.experiment.fixtures} (a limit on the fixtures per item, all
 * by default), {@code pm.experiment.parallel} (4), {@code pm.experiment.corpus-cache}
 * ({@code pm-mcp-server/target/corpus-cache}), {@code pm.experiment.telemetry} (the checkout beside this repository's
 * main checkout), {@code pm.experiment.telemetry.ref} ({@value QuestionFixtures#TELEMETRY_REF}),
 * {@code pm.experiment.clones} (the directory of the local clones, the parent of this repository's main checkout; both
 * defaults are found from the git common directory, also inside a worktree:
 * {@link QuestionFixtures#siblingRepositories(Path)}), {@code pm.experiment.r4.large-runs} (3),
 * {@code pm.experiment.warmth} ({@code both}, {@code fresh} or {@code warm}, default {@code both}: which workers each
 * item's run matrix plans; another value fails the run; Q7 runs fresh only, {@link Warmth}),
 * {@code pm.experiment.workspace} (below the temporary directory, outside the repository),
 * {@code pm.experiment.run-timeout} ({@code PT20M}), {@code pm.experiment.q4.other-host},
 * {@code pm.experiment.q6.quorum-a} and {@code pm.experiment.q6.quorum-b} ({@code <host>:<model>}),
 * {@code pm.experiment.q7.open} ({@code PT1H}), {@code pm.experiment.r1.durations},
 * {@code pm.experiment.r2.ages} (ISO durations, comma-separated), {@code pm.experiment.r2.expired-session},
 * {@code pm.experiment.exe.claude} and {@code pm.experiment.exe.opencode} (the executable's path, else found on the
 * {@code PATH} or where the host installs itself: {@link QuestionDriver#executable(Host, Map)}), and the launch
 * settings of {@link QuestionDriver}.
 * <p>
 * A worker that exits, or for which {@code pm-exec} reports an error, before its relay connected ends the run at once
 * as {@code launch_failed}, with the exit code and the first line of its {@code stderr}: a failure of the run, never a
 * pass and never a timeout ({@link QuestionDriver#launchFailure()}).
 * <p>
 * Antigravity runs without further parameters on the launch verified on 2026-10-21 with agy against the stub
 * ({@link QuestionDriver#AGY_ARGV}, {@link QuestionDriver#AGY_CONFIG}); {@code -Dpm.experiment.agy.argv} (a JSON array
 * with {@code {prompt}}, {@code {model}}, {@code {session_id}}, {@code {mcp_config}}) and
 * {@code -Dpm.experiment.agy.config} still override it. What was verified, and what was not:
 * <ul>
 * <li>Model: {@code agy models} lists identifiers such as {@code gemini-3.8-flash-low},
 * {@code gemini-3.8-flash-medium}, {@code gemini-4-argon-medium}, {@code claude-sonnet-5-5-medium}; a run with
 * {@code --model gemini-3.8-flash-low} and a run without {@code --model} (the harness default, which the output does
 * not name) both worked. {@code pm.experiment.model.agy} sets it; without it the launch omits {@code --model}.</li>
 * <li>MCP configuration: {@code agy mcp add} writes only the global {@code ~/.gemini/config/mcp_config.json}
 * ({@code {"mcpServers": {name: {command, args, env, disabled}}}}); a worker HOME of its own loses the login
 * ({@code Please sign in}), and an {@code mcp_config.json} at the worker directory's root is not read. A workspace
 * plugin is: {@code .agents/plugins/<name>/plugin.json} and {@code mcp_config.json} below the worker directory reached
 * the stub ({@code connected}, then {@code pm_state} and {@code pm_wait} called by their plain names), with the
 * relay's identity in the server's {@code env}. The plugin is named {@code pm-question-stub}, because the operator has
 * a global plugin {@code plan-marshall} whose skills every agy worker loads beside it.</li>
 * <li>Session id: {@code --print ... --output-format json} ends with one JSON object whose {@code conversation_id}
 * names the conversation ({@code status}, {@code response}, {@code num_turns} and {@code usage} beside it).</li>
 * <li>Resume: {@code --conversation <id>} continued the conversation (it named the entity it had read; the same id,
 * {@code num_turns} 2) and restarted the MCP server.</li>
 * <li>Missing id: agy neither fails nor hangs. It prints {@code warning: conversation "<id>" not found} on
 * {@code stderr}, starts a new conversation with a new id, works on the prompt (it called {@code pm_state} and
 * {@code pm_do ack}), and exits 0; {@code --print-timeout} bounds that turn and also exits 0, with
 * {@code [agy] print timeout after ... returning partial output}. The R2 negative runs wait for the exit up to the run
 * timeout and classify the outcome ({@link QuestionResults#negativeOutcome}): {@code negative_new_session},
 * {@code failed_at_once}, {@code hung}, or {@code exited_without_failure}.</li>
 * <li>Expired id: not verified. agy states no age limit for a conversation and none could be produced; the Antigravity
 * runs of an expired session are not measured unless {@code pm.experiment.r2.expired-session} names one.</li>
 * <li>The held call: a {@code pm_wait} the stub held for the bounded wait of 150 s returned {@code wait: again} after
 * 150.0 s, uncut by agy.</li>
 * <li>Not verified: the launch through {@code pm-exec} (the probe started agy directly), {@code --output-format
 * stream-json}, and whether agy passes its own environment to an MCP server (the relay's identity is written into
 * the server's {@code env}).</li>
 * </ul>
 */
@DisplayName("Question loop experiment: one item with -Dpm.experiment.question=<item>")
@EnabledIfSystemProperty(named = "pm.experiment.question", matches = ".+")
class QuestionLoopExperiment {

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ROLE = "implement";
    private static final String TASK_ID = "T-1";
    private static final String EVENT = "event";
    private static final String DECISION = "decision";
    private static final Duration POLL = Duration.ofSeconds(1);
    private static final Duration ANSWER_WAIT = Duration.ofMinutes(15);
    private static final Map<String, Quorum> QUORUMS = Map.of(
            "interpretation", new Quorum(List.of("quorum-a", "quorum-b"), 90),
            "approach", new Quorum(List.of("quorum-a", "quorum-b"), 90),
            "granularity", new Quorum(List.of("quorum-a", "quorum-b"), 90),
            "placement", new Quorum(List.of("quorum-a", "quorum-b"), 90),
            "test_strategy", new Quorum(List.of("quorum-a", "quorum-b"), 90));

    /**
     * The measurement's configuration, from the system properties.
     *
     * @param hosts      the hosts
     * @param models     the model per host, absent for the harness default
     * @param runs       runs per fixture, host and form
     * @param fixtures   the most fixtures per item
     * @param parallel   runs at once
     * @param cache        the corpus cache
     * @param telemetry    the telemetry checkout, read only
     * @param telemetryRef the ref of the branch of the scenario recipes in it
     * @param clones       the directory of the local clones of the scenarios' repositories, read only
     * @param largeRuns    runs per large scenario, host, form and continuation (R4)
     * @param workspace    the root of the run workspaces, outside the repository
     * @param results      the results directory ({@code runs.jsonl} and the per-run logs)
     * @param exec         the {@code pm-exec} command line
     * @param runTimeout   the bound of one run beside its holds
     * @param warmth       the warm/fresh axis of the run matrix
     */
    record Config(List<Host> hosts, Map<Host, String> models, int runs, int fixtures, int parallel, Path cache,
    Path telemetry, String telemetryRef, Path clones, int largeRuns, Path workspace, Path results,
    List<String> exec, Duration runTimeout, Warmth warmth) {

        Config {
            if (runs < 0 || largeRuns < 0) {
                throw new IllegalArgumentException("pm.experiment.runs (" + runs + ") and pm.experiment.r4.large-runs ("
                        + largeRuns + ") must be 0 or more");
            }
        }

        static Config load() throws IOException {
            var telemetry = System.getProperty("pm.experiment.telemetry");
            var clones = System.getProperty("pm.experiment.clones");
            var siblings = telemetry == null || clones == null ? QuestionFixtures.siblingRepositories(ROOT) : null;
            var hosts = new ArrayList<Host>();
            for (var name : System.getProperty("pm.experiment.hosts", "claude,opencode,antigravity").split(",")) {
                hosts.add(Host.of(name));
            }
            var models = new EnumMap<Host, String>(Host.class);
            for (var host : Host.values()) {
                var model = System.getProperty("pm.experiment.model." + host.executable(),
                        host == Host.CLAUDE ? "claude-haiku-4-5" : null);
                if (model != null) {
                    models.put(host, model);
                }
            }
            var stamp = Instant.now().toString().replace(":", "").replace(".", "");
            return new Config(List.copyOf(hosts), models, Integer.getInteger("pm.experiment.runs", 20),
                    Integer.getInteger("pm.experiment.fixtures", Integer.MAX_VALUE),
                    Integer.getInteger("pm.experiment.parallel", 4),
                    Path.of(System.getProperty("pm.experiment.corpus-cache",
                            ROOT.resolve("pm-mcp-server/target/corpus-cache").toString())),
                    telemetry == null ? siblings.resolve("plan-marshall-telemetry") : Path.of(telemetry),
                    System.getProperty("pm.experiment.telemetry.ref", QuestionFixtures.TELEMETRY_REF),
                    clones == null ? siblings : Path.of(clones),
                    Integer.getInteger("pm.experiment.r4.large-runs", 3),
                    Path.of(System.getProperty("pm.experiment.workspace",
                            Path.of(System.getProperty("java.io.tmpdir"), "pm-qlv-" + stamp).toString())),
                    Path.of("target", "question-loop").toAbsolutePath(), QuestionDriver.resolveExec(),
                    Duration.parse(System.getProperty("pm.experiment.run-timeout", "PT20M")),
                    Warmth.of(System.getProperty(Warmth.PROPERTY)));
        }

        String model(Host host) {
            return models.get(host);
        }
    }

    /**
     * One planned run.
     *
     * @param item         the item
     * @param index        the run index within its fixture, host and form
     * @param host         the host
     * @param model        the model, {@code null} for the harness default
     * @param warm         whether the asker is a warm worker
     * @param form         how the asking task continues
     * @param fixture      the fixture
     * @param kind         {@code entity}, {@code envelope}, {@code vote} or {@code consult}
     * @param reply        how the operator answers
     * @param hold         how long the answer takes (the R1 duration, the R2 age, the Q7 open time)
     * @param fault        the R3 fault, or {@code null}
     * @param restart      whether the stub is restarted while the record is open
     * @param bound        whether the Q5 run measures the reached bound
     * @param negative     the session id of an R2 resume that must fail, or {@code null}
     * @param continueHost the host of the continuing task, or {@code null} for the asker's
     */
    record RunPlan(String item, int index, Host host, String model, boolean warm, Form form, Fixture fixture,
    String kind, Reply reply, Duration hold, Fault fault, boolean restart, boolean bound, String negative,
    Host continueHost) {

        static RunPlan of(String item, int index, Host host, String model, boolean warm, Form form, Fixture fixture,
                String kind) {
            return new RunPlan(item, index, host, model, warm, form, fixture, kind, Reply.OPTION, Duration.ZERO,
                    null, false, false, null, null);
        }

        RunPlan with(Reply value) {
            return new RunPlan(item, index, host, model, warm, form, fixture, kind, value, hold, fault, restart, bound,
                    negative, continueHost);
        }

        RunPlan holding(Duration value) {
            return new RunPlan(item, index, host, model, warm, form, fixture, kind, reply, value, fault, restart,
                    bound, negative, continueHost);
        }

        RunPlan faulting(Fault value) {
            return new RunPlan(item, index, host, model, warm, form, fixture, kind, reply, hold, value, restart,
                    bound, negative, continueHost);
        }

        RunPlan restarting() {
            return new RunPlan(item, index, host, model, warm, form, fixture, kind, reply, hold, fault, true, bound,
                    negative, continueHost);
        }

        RunPlan bounded() {
            return new RunPlan(item, index, host, model, warm, form, fixture, kind, reply, hold, fault, restart, true,
                    negative, continueHost);
        }

        RunPlan resumingSession(String value) {
            return new RunPlan(item, index, host, model, warm, form, fixture, kind, reply, hold, fault, restart,
                    bound, value, continueHost);
        }

        RunPlan continuingOn(Host value) {
            return new RunPlan(item, index, host, model, warm, form, fixture, kind, reply, hold, fault, restart,
                    bound, negative, value);
        }
    }

    @Test
    @DisplayName("measures the item named by pm.experiment.question and writes its figures and verdict")
    void measure() throws Exception {
        var item = System.getProperty("pm.experiment.question").strip().toUpperCase(Locale.ROOT);
        var config = Config.load();
        var plans = plans(item, config);
        assertFalse(plans.isEmpty(), "no run planned for " + item);

        execute(plans, config);
        var result = QuestionResults.evaluate(item, QuestionResults.runs(config.results(), item));
        if ("R4".equals(item)) {
            QuestionResults.limitsOfR4(result, QuestionFixtures.unusedScenarios(telemetry(config)).size(),
                    config.largeRuns(), config.runs());
        }
        var file = QuestionResults.write(item, result);

        assertTrue(Files.isRegularFile(file), "no result written for " + item);
    }

    // --- plans ---

    private static List<RunPlan> plans(String item, Config config) throws IOException {
        var plans = new ArrayList<RunPlan>();
        switch (item) {
            case "Q1", "Q2", "Q3", "Q4" -> {
                var fixtures = limit(QuestionFixtures.applyAnswer(config.cache()), config);
                if ("Q2".equals(item)) {
                    fixtures.addAll(limit(QuestionFixtures.controls(config.cache()), config));
                }
                var other = Optional.ofNullable(System.getProperty("pm.experiment.q4.other-host")).map(Host::of);
                each(config, config.hosts(), fixtures, (host, warm, fixture, index) -> {
                    for (var kind : fixture.control() ? List.of("entity") : List.of("entity", "envelope")) {
                        var plan = RunPlan.of(item, index, host, config.model(host), warm, Form.FALLBACK, fixture,
                                kind);
                        if ("Q4".equals(item)) {
                            plan = plan.with(Reply.values()[index % 3]);
                            if (other.isPresent() && index % 2 == 1) {
                                plan = plan.continuingOn(other.get());
                            }
                        }
                        plans.add(plan);
                    }
                });
            }
            case "Q5" -> each(config, config.hosts(), limit(QuestionFixtures.twoFlaws(config.cache()), config),
                    (host, warm, fixture, index) -> {
                        var plan = RunPlan.of(item, index, host, config.model(host), warm, Form.FALLBACK, fixture,
                                "entity");
                        plans.add(plan.with(Reply.FIRST_ONLY));
                        plans.add(plan.with(Reply.RESTATE_OPTIONS).bounded());
                    });
            case "Q6" -> each(config, config.hosts(), limit(QuestionFixtures.applyAnswer(config.cache()), config),
                    (host, warm, fixture, index) -> {
                        plans.add(RunPlan.of(item, index, host, config.model(host), warm, Form.FALLBACK, fixture,
                                "vote"));
                        plans.add(RunPlan.of(item, index, host, config.model(host), warm, Form.FALLBACK, fixture,
                                "consult"));
                    });
            case "Q7" -> {
                var open = Duration.parse(System.getProperty("pm.experiment.q7.open", "PT1H"));
                var fixtures = limit(QuestionFixtures.applyAnswer(config.cache()), config);
                for (var fixture : fixtures) {
                    for (var index = 0; index < config.runs(); index++) {
                        plans.add(RunPlan.of(item, index, Host.CLAUDE, config.model(Host.CLAUDE), false,
                                Form.FALLBACK, fixture, "entity").holding(open).restarting());
                    }
                }
            }
            case "R1" -> each(config, config.hosts(), probe(), (host, warm, fixture, index) -> {
                for (var duration : durations("pm.experiment.r1.durations", host.boundedWait().toString() + ","
                        + host.boundedWait().multipliedBy(2) + ",PT5M,PT15M,PT30M,PT60M")) {
                    plans.add(RunPlan.of(item, index, host, config.model(host), warm, Form.HOLD, fixture, "entity")
                            .holding(duration));
                }
            });
            case "R2" -> each(config, config.hosts(), probe(), (host, warm, fixture, index) -> {
                for (var age : durations("pm.experiment.r2.ages", "PT5M,PT1H,PT24H")) {
                    plans.add(RunPlan.of(item, index, host, config.model(host), warm, Form.RESUME, fixture, "entity")
                            .holding(age));
                }
                var base = RunPlan.of(item, index, host, config.model(host), warm, Form.RESUME, fixture, "entity");
                plans.add(base.holding(Duration.ofMinutes(5)).restarting());
                plans.add(base.resumingSession(UUID.randomUUID().toString()));
                var expired = System.getProperty("pm.experiment.r2.expired-session");
                if (expired != null) {
                    plans.add(base.resumingSession(expired));
                }
            });
            case "R3" -> each(config, config.hosts(), limit(QuestionFixtures.applyAnswer(config.cache()), config),
                    (host, warm, fixture, index) -> {
                        for (var fault : Fault.values()) {
                            var form = fault == Fault.RESUMED_EXIT ? Form.RESUME : Form.HOLD;
                            plans.add(RunPlan.of(item, index, host, config.model(host), warm, form, fixture, "entity")
                                    .holding(Duration.ofSeconds(30)).faulting(fault));
                        }
                    });
            case "R4" -> plans.addAll(r4(config,
                    () -> limit(QuestionFixtures.largeScenarios(telemetry(config), config.clones()), config)));
            default -> throw new IllegalArgumentException("unknown item " + item + ": Q1 to Q7, R1 to R4");
        }
        return plans;
    }

    /** The large work of R4, loaded only when it has runs. */
    @FunctionalInterface
    interface LargeWork {
        List<Fixture> load() throws IOException;
    }

    /**
     * Plans R4: the probe as small work with every continuation form and {@link Config#runs()} runs, and the large
     * work with {@link Config#largeRuns()} runs in the continuation forms hold and resume. A part with zero runs plans
     * no run, and the large work is not loaded then, so R4 can run as two invocations, small work only and large work
     * only.
     *
     * @param config the configuration
     * @param large  the large work
     * @return the planned runs
     * @throws IOException if the large work cannot be loaded
     */
    static List<RunPlan> r4(Config config, LargeWork large) throws IOException {
        var plans = new ArrayList<RunPlan>();
        each(config, config.hosts(), probe(), (host, warm, fixture, index) -> {
            for (var form : Form.values()) {
                plans.add(RunPlan.of("R4", index, host, config.model(host), warm, form, fixture, "entity")
                        .holding(Duration.ofMinutes(1)));
            }
        });
        if (config.largeRuns() > 0) {
            each(config.largeRuns(), config.warmth(), config.hosts(), large.load(),
                    (host, warm, fixture, index) -> {
                        for (var form : List.of(Form.HOLD, Form.RESUME)) {
                            plans.add(RunPlan.of("R4", index, host, config.model(host), warm, form, fixture,
                                    "entity").holding(Duration.ofMinutes(1)));
                        }
                    });
        }
        return plans;
    }

    /**
     * The warm/fresh axis of the run matrix, set by {@code pm.experiment.warmth}: {@code both} (the default), {@code
     * fresh} or {@code warm}.
     */
    enum Warmth {
        /** Fresh and warm workers. */
        BOTH(List.of(false, true)),
        /** Fresh workers only. */
        FRESH(List.of(false)),
        /** Warm workers only. */
        WARM(List.of(true));

        /** The system property that selects the axis. */
        static final String PROPERTY = "pm.experiment.warmth";

        private final List<Boolean> planned;

        Warmth(List<Boolean> planned) {
            this.planned = planned;
        }

        /** @return the values of {@link RunPlan#warm()} this selection plans, fresh first */
        List<Boolean> planned() {
            return planned;
        }

        /**
         * @param value {@code both}, {@code fresh} or {@code warm}, in any case; {@code null} for the default
         * @return the selection
         * @throws IllegalArgumentException for any other value
         */
        static Warmth of(String value) {
            if (value == null) {
                return BOTH;
            }
            for (var warmth : values()) {
                if (warmth.name().equalsIgnoreCase(value.strip())) {
                    return warmth;
                }
            }
            throw new IllegalArgumentException("unknown -D" + PROPERTY + "=" + value + ": both, fresh or warm");
        }
    }

    @FunctionalInterface
    private interface PlanStep {
        void plan(Host host, boolean warm, Fixture fixture, int index);
    }

    private static void each(Config config, List<Host> hosts, List<Fixture> fixtures, PlanStep step) {
        each(config.runs(), config.warmth(), hosts, fixtures, step);
    }

    private static void each(int runs, Warmth warmth, List<Host> hosts, List<Fixture> fixtures, PlanStep step) {
        for (var host : hosts) {
            for (var warm : warmth.planned()) {
                for (var fixture : fixtures) {
                    for (var index = 0; index < runs; index++) {
                        step.plan(host, warm, fixture, index);
                    }
                }
            }
        }
    }

    /** @return the probe, the only fixture of R1 and R2 and the small work of R4 */
    private static List<Fixture> probe() {
        return List.of(QuestionFixtures.probe());
    }

    private static QuestionFixtures.Source telemetry(Config config) {
        return QuestionFixtures.telemetry(config.telemetry(), config.telemetryRef());
    }

    private static List<Fixture> limit(List<Fixture> fixtures, Config config) {
        return new ArrayList<>(fixtures.subList(0, Math.min(fixtures.size(), config.fixtures())));
    }

    private static List<Duration> durations(String property, String defaults) {
        var durations = new ArrayList<Duration>();
        for (var value : System.getProperty(property, defaults).split(",")) {
            durations.add(Duration.parse(value.strip()));
        }
        return durations;
    }

    // --- execution ---

    private static void execute(List<RunPlan> plans, Config config) throws InterruptedException,
            ExecutionException {
        try (var executor = Executors.newFixedThreadPool(config.parallel())) {
            var futures = new ArrayList<Future<?>>();
            for (var plan : plans) {
                futures.add(executor.submit(() -> {
                    runOne(plan, config);
                    return null;
                }));
            }
            for (var future : futures) {
                future.get();
            }
        }
    }

    private static void runOne(RunPlan plan, Config config) throws IOException {
        var bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        var suffix = HexFormat.of().formatHex(bytes);
        var id = "%s-%s-%s-%s-%03d-%s".formatted(plan.item(), plan.host().executable(), plan.warm() ? "warm" : "fresh",
                plan.fixture().id().replace('/', '_'), plan.index(), suffix);
        var base = config.workspace().resolve(id);
        var stubDir = base.resolve("stub");
        var stubSettings = QuestionStub.Settings.of(stubDir, Path.of("/tmp", "pmq-" + suffix + ".sock"))
                .withWindow(plan.host().boundedWait()).withQuorums("vote".equals(plan.kind()) ? QUORUMS : Map.of());
        var record = runRecord(plan, stubDir);
        try (var driver = QuestionDriver.start(stubSettings, new QuestionDriver.Settings(plan.host(), plan.model(),
                     base.resolve("workers"), config.results().resolve("runs").resolve(id), config.exec()))) {
            new Session(driver, plan, config, record).run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record.put("error", "interrupted");
        } catch (IOException | IllegalStateException e) {
            record.put("error", String.valueOf(e.getMessage()));
        }
        QuestionResults.recordRun(config.results(), record);
    }

    private static ObjectNode runRecord(RunPlan plan, Path stubDir) {
        var record = QuestionStub.JSON.createObjectNode();
        record.put("item", plan.item());
        record.put("run", plan.index());
        record.put("host", plan.host().executable());
        record.put("model", String.valueOf(plan.model()));
        record.put("form", plan.warm() ? "warm" : "fresh");
        record.put("continuation", plan.form().name().toLowerCase(Locale.ROOT));
        record.put("fixture", plan.fixture().id());
        record.put("category", plan.fixture().category());
        record.put("control", plan.fixture().control());
        record.put("kind", plan.kind());
        record.put("reply", plan.reply().name().toLowerCase(Locale.ROOT));
        record.put("reference", plan.fixture().check() == null ? "" : plan.fixture().check().reference());
        record.put("duration_s", plan.hold().toSeconds());
        record.put("age_s", plan.hold().toSeconds());
        record.put("fault", plan.fault() == null ? "" : plan.fault().name());
        record.put("size", plan.fixture().size());
        record.put("bound", plan.bound());
        record.put("negative", plan.negative() != null);
        record.put("restart", plan.restart());
        record.put("continue_host", plan.continueHost() == null ? "" : plan.continueHost().executable());
        record.put("task_id", TASK_ID);
        record.put("stub_dir", stubDir.toString());
        return record;
    }

    /** One run: the asking task, its questions, their answers, and its continuation until the entry is done. */
    private static final class Session {

        private final QuestionDriver driver;
        private final RunPlan plan;
        private final Config config;
        private final ObjectNode record;
        private final List<QuestionDriver> siblings = new ArrayList<>();
        private final Set<String> seenDecisions = new HashSet<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private int generation = 1;
        private boolean launchFailed;

        Session(QuestionDriver driver, RunPlan plan, Config config, ObjectNode record) {
            this.driver = driver;
            this.plan = plan;
            this.config = config;
            this.record = record;
        }

        void run() throws IOException, InterruptedException {
            var fixture = plan.fixture();
            var canary = QuestionFixtures.prepare(fixture, driver.settings().workspace().resolve("w1"));
            record.put("canary", canary);
            var spec = spec(fixture);
            var helpers = startHelpers();
            var current = start(spec);
            try {
                if (plan.negative() != null) {
                    negativeResume(current);
                    return;
                }
                var deadline = System.currentTimeMillis() + config.runTimeout().toMillis()
                        + plan.hold().toMillis() * 4;
                var rounds = 0;
                while (!launchFailed && !entryDone() && System.currentTimeMillis() < deadline
                        && rounds <= QuestionStub.QUESTION_BOUND) {
                    var asked = nextAsked(deadline);
                    if (asked.isEmpty()) {
                        break;
                    }
                    rounds++;
                    if (rounds == 1) {
                        driver.worktreeDigest(current);
                        if (plan.form() == Form.FALLBACK) {
                            awaitStop(current, asked.get());
                            driver.worktreeDigest(current);
                        }
                    }
                    if (plan.fault() == Fault.STOP_BEFORE_ANSWER) {
                        driver.inject(current, Fault.STOP_BEFORE_ANSWER);
                    }
                    if (!answer()) {
                        break;
                    }
                    current = continueAfter(asked.get(), current);
                }
                awaitEntry(deadline);
                record.put("timed_out", !launchFailed && !entryDone());
            } finally {
                running.set(false);
                for (var helper : helpers) {
                    helper.join(POLL.toMillis() * 5);
                }
                endWarm();
                record.set("workers", workers());
                for (var sibling : siblings) {
                    sibling.close();
                }
            }
        }

        private TaskSpec spec(Fixture fixture) {
            return switch (plan.kind()) {
                case "envelope" -> QuestionFixtures.envelopeTask(fixture, TASK_ID, ROLE, plan.form());
                case "consult" -> {
                    var spec = QuestionFixtures.consultingTask(fixture, TASK_ID, ROLE, plan.form(), List.of("advise"));
                    spec.facts().put("procedure", "Before you ask the operator, consult the role advise on how to "
                            + "read the contract (link consult:advise), then ask the operator what the consultation "
                            + "leaves open.");
                    yield spec;
                }
                default -> QuestionFixtures.entityTask(fixture, TASK_ID, ROLE, plan.form());
            };
        }

        private Worker start(TaskSpec spec) throws IOException {
            var launch = launch("w1", 1, ROLE, plan.host(), plan.fault() == Fault.CALL_CUT
                    ? Duration.ofSeconds(60) : null);
            if (plan.warm()) {
                driver.stub().queue(spec);
                return driver.startWarm(launch);
            }
            return driver.startFresh(spec, launch);
        }

        private Launch launch(String worker, int launchGeneration, String role, Host host, Duration toolTimeout) {
            var extra = "Your working directory is your worktree; files you write stay there.";
            return new Launch(worker, launchGeneration, role, QuestionDriver.contract(role, extra),
                    plan.form() == Form.RESUME, toolTimeout, workTools(host), null);
        }

        private static List<String> workTools(Host host) {
            return switch (host) {
                case CLAUDE -> List.of("Read", "Write", "Edit", "Glob", "Grep");
                case OPENCODE -> List.of("read", "write", "edit", "glob", "grep");
                case ANTIGRAVITY -> List.of();
            };
        }

        private boolean answer() throws InterruptedException, IOException {
            var hold = plan.hold();
            if (plan.restart()) {
                Thread.sleep(hold.dividedBy(2));
                driver.restartStub();
                hold = hold.dividedBy(2);
            }
            var answered = driver.answerNext(ANSWER_WAIT, hold,
                    decision -> QuestionFixtures.answer(plan.fixture(), decision, plan.reply()));
            return answered.isPresent();
        }

        private Worker continueAfter(JsonNode asked, Worker current) throws IOException, InterruptedException {
            var decision = asked.path(DECISION).asText();
            var task = asked.path("task").asText();
            return switch (plan.form()) {
                case HOLD -> hold(current, decision);
                case RESUME -> resume(current, task);
                case FALLBACK -> fallback(current, decision);
            };
        }

        private Worker hold(Worker current, String decision) throws IOException, InterruptedException {
            if (plan.fault() == Fault.MODEL_HANG || plan.fault() == Fault.KILL) {
                driver.awaitEvent(event -> "answer_delivered".equals(event.path(EVENT).asText())
                                && current.id().equals(event.path("worker").asText()),
                        System.currentTimeMillis() + plan.host().silenceLimit().toMillis());
                driver.inject(current, plan.fault());
            }
            if (driver.superviseAnswer(current, plan.host().silenceLimit().plus(Duration.ofMinutes(1)))) {
                return current;
            }
            return continuation(decision, current);
        }

        private Worker resume(Worker current, String task) throws IOException, InterruptedException {
            driver.awaitExit(current, plan.host().silenceLimit());
            var session = driver.sessionId(current);
            if (session.isEmpty()) {
                record.put("no_session", true);
                return current;
            }
            var resumed = driver.resume(current, task, session.get(), ++generation);
            if (plan.fault() == Fault.RESUMED_EXIT) {
                driver.inject(resumed, Fault.RESUMED_EXIT);
            }
            if (driver.superviseAnswer(resumed, Duration.ofMinutes(2))) {
                return resumed;
            }
            var again = driver.resume(current, task, session.get(), ++generation);
            driver.superviseAnswer(again, Duration.ofMinutes(2));
            return again;
        }

        private Worker fallback(Worker current, String decision) throws IOException, InterruptedException {
            if (plan.warm() && plan.continueHost() == null) {
                return current;
            }
            if (plan.warm()) {
                driver.stub().end(current.id(), current.generation());
            }
            return continuation(decision, current);
        }

        /** Starts a fresh generation of the asking worker on the task that continues the decision. */
        private Worker continuation(String decision, Worker current) throws IOException, InterruptedException {
            var continued = driver.awaitEvent(event -> "continued".equals(event.path(EVENT).asText())
                    && decision.equals(event.path(DECISION).asText()), System.currentTimeMillis()
                    + ANSWER_WAIT.toMillis());
            if (continued.isEmpty()) {
                return current;
            }
            var host = plan.continueHost() == null ? plan.host() : plan.continueHost();
            var target = host == plan.host() ? driver : sibling(host);
            var launch = launch(current.id(), ++generation, ROLE, host, null);
            return target.startLeased(continued.get().path("task").asText(),
                    continued.get().path("entity").asText(), launch);
        }

        /**
         * Resumes a missing or over-age session and waits for its end up to the run timeout, so that a harness that
         * starts a new conversation instead of failing (Antigravity: a warning on {@code stderr}, a new id, exit 0 at
         * the end of the turn) is told apart from one that hangs. The run records the exit, the session id the
         * generation reports, and whether it started a new session: a reported id other than the one named, or the
         * harness's warning that the named one was not found.
         */
        private void negativeResume(Worker current) throws IOException, InterruptedException {
            nextAsked(System.currentTimeMillis() + config.runTimeout().toMillis());
            if (launchFailed) {
                return;
            }
            driver.awaitExit(current, plan.host().silenceLimit());
            var resumed = driver.resumeWithoutTask(current, plan.negative(), ++generation);
            var exit = driver.awaitExit(resumed, config.runTimeout());
            if (exit.isEmpty()) {
                driver.terminate(resumed);
            }
            record.put("negative_exit", exit.map(String::valueOf).orElse("none"));
            var reported = driver.sessionId(resumed);
            record.put("negative_session", reported.orElse(""));
            var warned = QuestionResults.warnsMissingSession(driver.stderr(resumed), plan.negative());
            record.put("negative_not_found_warning", warned);
            record.put("negative_new_session", warned
                    || reported.isPresent() && !plan.negative().equals(reported.get()));
        }

        private Optional<JsonNode> nextAsked(long deadline) throws IOException, InterruptedException {
            var asked = driver.awaitEventWhileLaunched(event -> "asked".equals(event.path(EVENT).asText())
                    && !seenDecisions.contains(event.path(DECISION).asText()) || entryDone(), deadline);
            if (asked.isEmpty() && launchFailed()) {
                return Optional.empty();
            }
            asked.filter(event -> "asked".equals(event.path(EVENT).asText()))
                    .ifPresent(event -> seenDecisions.add(event.path(DECISION).asText()));
            return asked.filter(event -> "asked".equals(event.path(EVENT).asText()));
        }

        /**
         * Records the run as {@code launch_failed} once a worker failed to launch, with its exit code and the first
         * line of its {@code stderr}: a failure of the run, never a pass and never a timeout.
         *
         * @return whether a worker of the run failed to launch
         */
        private boolean launchFailed() throws IOException, InterruptedException {
            if (!launchFailed) {
                var failure = driver.launchFailure();
                if (failure.isPresent()) {
                    launchFailed = true;
                    var value = failure.get();
                    record.put("launch_failed", true);
                    record.put("launch_worker", value.worker());
                    record.put("launch_generation", value.generation());
                    record.put("launch_exit", value.exit());
                    record.put("launch_stderr", value.stderr());
                    record.put("error", "launch_failed: exit " + value.exit() + ": " + value.stderr());
                }
            }
            return launchFailed;
        }

        /**
         * Waits until the asker stopped after its ask: a fresh worker exits, a warm worker calls {@code pm_wait}
         * (Q3), before the answer can start a continuation that changes the worktree.
         */
        private void awaitStop(Worker asker, JsonNode asked) throws IOException, InterruptedException {
            var at = asked.path("at").asLong();
            if (plan.warm()) {
                driver.awaitEvent(event -> "wait_started".equals(event.path(EVENT).asText())
                                && event.path("at").asLong() >= at && asker.id().equals(event.path("worker").asText())
                                && asker.generation() == event.path("generation").asInt(),
                        System.currentTimeMillis() + plan.host().silenceLimit().toMillis());
            } else {
                driver.awaitExit(asker, plan.host().silenceLimit());
            }
        }

        private void awaitEntry(long deadline) throws IOException, InterruptedException {
            while (!entryDone() && System.currentTimeMillis() < deadline && !launchFailed()) {
                Thread.sleep(POLL);
            }
        }

        /** The entry is done when every task of it is closed and one of them completed or was reported infeasible. */
        private boolean entryDone() {
            var stub = driver.stub();
            var states = stub.taskIds().stream().filter(id -> id.startsWith(TASK_ID))
                    .map(id -> stub.state(id).orElse("")).toList();
            var closed = states.stream().allMatch(state -> state.endsWith(".completed")
                    || Set.of("task.asked", "task.infeasible", "task.lost").contains(state));
            var finished = states.stream().anyMatch(state -> state.endsWith(".completed")
                    || "task.infeasible".equals(state));
            return !states.isEmpty() && closed && finished;
        }

        private List<Thread> startHelpers() {
            var helpers = new ArrayList<Thread>();
            if ("vote".equals(plan.kind())) {
                helpers.add(Thread.ofVirtual().start(this::serveVotes));
            }
            if ("consult".equals(plan.kind())) {
                helpers.add(Thread.ofVirtual().start(this::answerConsultations));
            }
            if ("Q7".equals(plan.item())) {
                helpers.add(Thread.ofVirtual().start(this::idleWarmWorker));
            }
            return helpers;
        }

        /** Starts a fresh worker on every vote task, on the harness and model of its role. */
        private void serveVotes() {
            var started = new HashSet<String>();
            try {
                while (running.get()) {
                    var stub = driver.stub();
                    for (var id : stub.taskIds()) {
                        if (id.startsWith("vote-") && "task.queued".equals(stub.state(id).orElse(""))
                                && started.add(id)) {
                            var role = id.substring(id.lastIndexOf("quorum-"));
                            var assignment = System.getProperty("pm.experiment.q6." + role,
                                    "quorum-a".equals(role) ? "claude:claude-haiku-4-5"
                                            : "opencode:opencode-go/muse-spark-1.3-contributor").split(":", 2);
                            var host = Host.of(assignment[0]);
                            var voter = sibling(host, assignment.length > 1 ? assignment[1] : null);
                            var launch = new Launch(id, 1, role, QuestionDriver.contract(role, "You vote blind on the "
                                            + "questions in facts.questions: follow submit:vote with answers, one item per "
                                            + "question: {question_id, choice (a branch_key), confidence (0..100), rationale}."),
                                    false, null, List.of(), null);
                            voter.startLeased(id, id, launch);
                        }
                    }
                    Thread.sleep(POLL);
                }
            } catch (IOException e) {
                record.put("vote_error", String.valueOf(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private QuestionDriver sibling(Host host) {
            return sibling(host, config.model(host));
        }

        private QuestionDriver sibling(Host host, String model) {
            synchronized (siblings) {
                var sibling = driver.sibling(host, model);
                siblings.add(sibling);
                return sibling;
            }
        }

        /** Answers every consultation as the scripted consulted role. */
        private void answerConsultations() {
            var answered = new HashSet<String>();
            try {
                while (running.get()) {
                    for (var event : QuestionResults.readJsonLines(driver.stub().events())) {
                        var consultation = event.path("consultation").asText();
                        if ("consulted".equals(event.path(EVENT).asText()) && answered.add(consultation)) {
                            driver.stub().answerConsultation(consultation, "Where the description and the examples "
                                    + "disagree, the consultation cannot settle which is meant: that is the "
                                    + "operator's decision.");
                        }
                    }
                    Thread.sleep(POLL);
                }
            } catch (IOException e) {
                record.put("consult_error", String.valueOf(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** Q7: a warm worker of the role waits beside the open record and is recycled after its idle wake-ups. */
        private void idleWarmWorker() {
            try {
                var warm = driver.startWarm(launch("w-warm", 1, ROLE, plan.host(), null));
                record.put("warm_worker", warm.id());
                while (running.get() && warm.process().isAlive()) {
                    var idle = QuestionResults.readJsonLines(driver.stub().events()).stream()
                            .filter(event -> "wait_again".equals(event.path(EVENT).asText())
                                    && warm.id().equals(event.path("worker").asText()))
                            .count();
                    if (idle >= 3) {
                        driver.stub().end(warm.id(), warm.generation());
                        return;
                    }
                    Thread.sleep(POLL);
                }
            } catch (IOException e) {
                record.put("warm_error", String.valueOf(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void endWarm() throws InterruptedException {
            for (var worker : driver.workers()) {
                if (worker.process().isAlive()) {
                    driver.stub().end(worker.id(), worker.generation());
                    driver.awaitExit(worker, Duration.ofSeconds(30));
                }
            }
        }

        private ArrayNode workers() {
            var array = QuestionStub.JSON.createArrayNode();
            var all = new ArrayList<>(driver.workers());
            synchronized (siblings) {
                siblings.forEach(sibling -> all.addAll(sibling.workers()));
            }
            for (var worker : all) {
                var node = array.addObject();
                node.put("worker", worker.id());
                node.put("generation", worker.generation());
                node.put("host", worker.host().executable());
                node.put("output", worker.output().toString());
                node.put("relay_log", worker.relayLog().toString());
            }
            return array;
        }
    }
}
