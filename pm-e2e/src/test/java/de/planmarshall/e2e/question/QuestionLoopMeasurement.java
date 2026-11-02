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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.question.driver.Fault;
import de.planmarshall.e2e.question.driver.Host;
import de.planmarshall.e2e.question.driver.QuestionDriver;
import de.planmarshall.e2e.question.driver.QuestionDriver.Launch;
import de.planmarshall.e2e.question.driver.QuestionDriver.OpenCodeDataHome;
import de.planmarshall.e2e.question.driver.QuestionDriver.RecoveryStep;
import de.planmarshall.e2e.question.driver.QuestionDriver.Worker;
import de.planmarshall.e2e.question.fixture.QuestionFixtures;
import de.planmarshall.e2e.question.fixture.QuestionFixtures.Fixture;
import de.planmarshall.e2e.question.fixture.QuestionFixtures.Reply;
import de.planmarshall.e2e.question.result.AnswerAudit;
import de.planmarshall.e2e.question.result.QuestionEvidence;
import de.planmarshall.e2e.question.result.QuestionResults;
import de.planmarshall.e2e.question.result.RunReadings;
import de.planmarshall.e2e.question.result.RunView;
import de.planmarshall.e2e.question.result.Scale;
import de.planmarshall.e2e.question.stub.QuestionStub;
import de.planmarshall.e2e.question.stub.QuestionStub.Form;
import de.planmarshall.e2e.question.stub.QuestionStub.Quorum;
import de.planmarshall.e2e.question.stub.QuestionStub.TaskSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The entry of the measurement: runs one item of the work package {@code question-loop-follow-up}, named by
 * {@code -Dpm.experiment.question=<item>} ({@code Q1} to {@code Q7}, {@code R1} to {@code R4}), at the revised scale
 * ({@link Scale}), and writes its figures and verdict to {@code target/verification-results/question-loop/<item>.json}
 * ({@link QuestionResults}). Without the property it is disabled, and the build's surefire configuration passes the
 * property empty, so no regular build starts a harness ({@code MeasurementGateTest}). It is run on its own:
 * {@code -pl pm-e2e -Dtest=QuestionLoopMeasurement -Dpm.experiment.question=<item>}; it needs the harness logins on
 * the operator's machine, the corpus cache, and for the large work of R4 a checkout of
 * {@code cuioss/plan-marshall-telemetry} and the local clones of the scenarios' repositories (both read only).
 * <p>
 * Run properties, each with its default:
 * <ul>
 * <li>{@code pm.experiment.hosts}: {@code claude,opencode,antigravity}; Q7 runs on Claude Code only.</li>
 * <li>{@code pm.experiment.model.<host>}: {@value #CLAUDE_MODEL} for Claude Code, {@value #OPENCODE_GLOBAL_MODEL}
 * for OpenCode on the global data home, the harness default otherwise ({@link #defaultModel}).</li>
 * <li>{@value QuestionDriver.OpenCodeDataHome#PROPERTY}: {@code global} or {@code worker}.</li>
 * <li>{@code pm.experiment.runs}: {@value Scale#RUNS_PER_GROUP} runs per group.</li>
 * <li>{@code pm.experiment.fixtures}: a limit on the fixtures per item, all by default.</li>
 * <li>{@value #PARALLEL_PROPERTY}: {@value #DEFAULT_PARALLEL} runs at once; recorded on every run record
 * ({@value QuestionResults#PARALLEL}) and on the invocation. The runs that start OpenCode on the global data home run
 * one at a time ({@link #serialOpenCode}).</li>
 * <li>{@code pm.experiment.corpus-cache}: {@code pm-mcp-server/target/corpus-cache}.</li>
 * <li>{@code pm.experiment.telemetry} and {@code pm.experiment.telemetry.ref}: the telemetry checkout beside this
 * repository's main checkout and {@value QuestionFixtures#TELEMETRY_REF}; {@code pm.experiment.clones}: the parent of
 * the main checkout ({@link QuestionFixtures#siblingRepositories(Path)}).</li>
 * <li>{@code pm.experiment.r4.large-runs}: 3 runs per large scenario; either this or {@code pm.experiment.runs} may be
 * 0, so R4 runs as two invocations.</li>
 * <li>{@value Warmth#PROPERTY}: {@code both}, {@code fresh} or {@code warm}; Q7 runs fresh only.</li>
 * <li>{@code pm.experiment.workspace}: the worker directories below the temporary directory.</li>
 * <li>{@value QuestionEvidence#WORK_PROPERTY} and {@value QuestionEvidence#ARCHIVE_PROPERTY}: the persistent root of
 * the stub logs and the store directory every invocation writes ({@link QuestionEvidence#store}).</li>
 * <li>{@code pm.experiment.result.suffix}, {@code pm.experiment.result.invocations} and
 * {@code pm.experiment.result.allow-missing-evidence}: the target of the result
 * ({@link QuestionResults.ResultTarget}).</li>
 * <li>{@value Fault#PROPERTY}: the faults an R3 invocation measures ({@link Fault#select(String)}).</li>
 * <li>{@code pm.experiment.run-timeout}: {@code PT20M}, the bound of one run beside its holds.</li>
 * <li>{@value #Q5_BOUND_PROPERTY}: the question bound of a Q5 invocation, {@value Scale#Q5_FIRST_BOUND}; the next bound
 * runs only where a run reached the bound before ({@link Scale#nextQ5Bound}), recorded per run as
 * {@value QuestionResults#QUESTION_BOUND}.</li>
 * <li>{@code pm.experiment.q4.other-host}, {@code pm.experiment.q6.quorum-a} and {@code pm.experiment.q6.quorum-b}
 * ({@code <host>:<model>}), {@value #Q6_KINDS_PROPERTY} ({@code vote,consult}; {@code consult} runs the consultation
 * part of Q6 without the vote runs), {@code pm.experiment.q7.open} ({@code PT1H}).</li>
 * <li>{@code pm.experiment.r1.durations} and {@value #R2_AGES} (ISO durations, comma-separated): {@code PT1H}, the
 * longest first; a shorter one runs only after a failure ({@link Scale#nextDuration}).</li>
 * <li>{@code pm.experiment.r2.expired-session}: a session id of an expired session, none by default.</li>
 * <li>{@code pm.experiment.exe.<host>} and {@code pm.experiment.exec}: the harness executable and the {@code pm-exec}
 * command line ({@link Host#resolveExecutable}, {@link QuestionDriver#resolveExec()}), and the launch settings of
 * {@link Host}.</li>
 * </ul>
 * <p>
 * Every run record carries its invocation, the client version of its harness ({@value QuestionResults#CLIENT_VERSION},
 * {@link QuestionDriver#clientVersion}) and the parallel level; every invocation record its planned runs
 * ({@value QuestionResults#PLANNED_RUNS}). How the driver classifies a run that ended at once (launch failure, early
 * exit, continuation early exit, quota error), how it answers on the operator's behalf, continues a decision by hold,
 * resume or fallback, and recovers after a loss is the driver's ({@link QuestionDriver}); how each operator answer was
 * composed is recorded per run ({@link AnswerAudit#recordComposition}) and logged as {@value #ANSWER_COMPOSED}. A run
 * whose fixture setup fails is recorded as {@value RunView#SETUP_FAILED}, and the invocation does not start its
 * remaining runs of that scenario; a run the account's usage limit refuses is recorded as {@value RunView#NOT_RUN}
 * with the reset time, and the invocation starts no further run ({@link SetupStops}). {@link QuestionReaggregation} writes the result again from the
 * existing records, without a run.
 */
@DisplayName("Question loop measurement: one item with -Dpm.experiment.question=<item>")
@EnabledIfSystemProperty(named = QuestionLoopMeasurement.ITEM_PROPERTY, matches = ".+")
@SuppressWarnings("java:S3577") // not named *Test on purpose: the default surefire run must not select a measurement
class QuestionLoopMeasurement {

    /** The property that names the item and enables the measurement. */
    static final String ITEM_PROPERTY = "pm.experiment.question";
    /** The property of the runs at once. */
    static final String PARALLEL_PROPERTY = "pm.experiment.parallel";
    /** The runs at once unless {@value #PARALLEL_PROPERTY} names another count. */
    static final int DEFAULT_PARALLEL = 4;
    /** The property of the question bound of a Q5 invocation. */
    static final String Q5_BOUND_PROPERTY = "pm.experiment.q5.bound";
    /** The property of the R2 ages. */
    static final String R2_AGES = "pm.experiment.r2.ages";
    /** The property of the kinds of Q6 an invocation runs ({@link #q6Kinds(String)}). */
    static final String Q6_KINDS_PROPERTY = "pm.experiment.q6.kinds";
    /** The event the measurement logs for each part of an operator answer it composed. */
    static final String ANSWER_COMPOSED = "answer_composed";
    /** The model of Claude Code unless {@code pm.experiment.model.claude} names another. */
    static final String CLAUDE_MODEL = "claude-haiku-4-5";
    /** The model of OpenCode on the global data home unless {@code pm.experiment.model.opencode} names another. */
    static final String OPENCODE_GLOBAL_MODEL = "opencode/gpt-5.4-nano";
    /** The worker note every launch prompt ends with. */
    static final String WORKTREE_NOTE = "Your working directory is your worktree; files you write stay there.";
    /** The task id of the asking task of every run. */
    static final String TASK_ID = "T-1";
    /** The field of a run record that says whether its R3 fault was injected (or, for the call cut, happened). */
    static final String FAULT_INJECTED_FIELD = "fault_injected";
    /** The run record's number of processes a stop reached. */
    static final String FAULT_SIGNALLED_PIDS_FIELD = "fault_signalled_pids";
    /** The longest duration of the revised scale, the default of R1 and R2. */
    static final String LONGEST = Duration.ofSeconds(Scale.R1_R2_DURATIONS.getFirst()).toString();

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();
    /** One run at a time that starts OpenCode on the global data home: its workers share one SQLite database. */
    private static final ReentrantLock OPENCODE_SERIAL = new ReentrantLock();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ROLE = "implement";
    private static final String EVENT = "event";
    private static final String DECISION = "decision";
    private static final String ENTITY = "entity";
    private static final String VOTE = "vote";
    private static final String CONSULT = "consult";
    private static final String QUORUM_A = "quorum-a";
    private static final Duration POLL = Duration.ofSeconds(1);
    private static final Duration ANSWER_WAIT = Duration.ofMinutes(15);
    private static final Map<String, Quorum> QUORUMS = Map.of(
            "interpretation", new Quorum(List.of(QUORUM_A, "quorum-b"), 90),
            "approach", new Quorum(List.of(QUORUM_A, "quorum-b"), 90),
            "granularity", new Quorum(List.of(QUORUM_A, "quorum-b"), 90),
            "placement", new Quorum(List.of(QUORUM_A, "quorum-b"), 90),
            "test_strategy", new Quorum(List.of(QUORUM_A, "quorum-b"), 90));

    /**
     * The measurement's configuration, from the system properties.
     *
     * @param hosts            the hosts
     * @param models           the model per host, absent for the harness default
     * @param runs             runs per fixture, host and form
     * @param fixtures         the most fixtures per item
     * @param parallel         runs at once
     * @param cache            the corpus cache
     * @param telemetry        the telemetry checkout, read only
     * @param telemetryRef     the ref of the branch of the scenario recipes in it
     * @param clones           the directory of the local clones of the scenarios' repositories, read only
     * @param largeRuns        runs per large scenario, host, form and continuation (R4)
     * @param workspace        the root of the worker directories of the invocation, outside the repository
     * @param evidence         the root of the stub logs of the invocation, below the persistent root
     * @param results          the results directory ({@code runs.jsonl} and the per-run logs)
     * @param exec             the {@code pm-exec} command line
     * @param runTimeout       the bound of one run beside its holds
     * @param warmth           the warm/fresh axis of the run matrix
     * @param openCodeDataHome where OpenCode workers keep their data
     * @param questionBound    the question bound of a Q5 invocation
     */
    record Config(List<Host> hosts, Map<Host, String> models, int runs, int fixtures, int parallel, Path cache,
    Path telemetry, String telemetryRef, Path clones, int largeRuns, Path workspace, Path evidence, Path results,
    List<String> exec, Duration runTimeout, Warmth warmth, OpenCodeDataHome openCodeDataHome, int questionBound) {

        Config {
            if (runs < 0 || largeRuns < 0) {
                throw new IllegalArgumentException("pm.experiment.runs (" + runs + ") and pm.experiment.r4.large-runs ("
                        + largeRuns + ") must be 0 or more");
            }
            if (parallel < 1) {
                throw new IllegalArgumentException(PARALLEL_PROPERTY + " (" + parallel + ") must be 1 or more");
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
            var dataHome = OpenCodeDataHome.of(System.getProperty(OpenCodeDataHome.PROPERTY));
            var stamp = Instant.now().toString().replace(":", "").replace(".", "");
            var results = Path.of("target", "question-loop").toAbsolutePath();
            return new Config(List.copyOf(hosts), models(dataHome),
                    Integer.getInteger("pm.experiment.runs", Scale.RUNS_PER_GROUP),
                    Integer.getInteger("pm.experiment.fixtures", Integer.MAX_VALUE),
                    Integer.getInteger(PARALLEL_PROPERTY, DEFAULT_PARALLEL),
                    Path.of(System.getProperty("pm.experiment.corpus-cache",
                            ROOT.resolve("pm-mcp-server/target/corpus-cache").toString())),
                    telemetry == null ? siblings.resolve("plan-marshall-telemetry") : Path.of(telemetry),
                    System.getProperty("pm.experiment.telemetry.ref", QuestionFixtures.TELEMETRY_REF),
                    clones == null ? siblings : Path.of(clones),
                    Integer.getInteger("pm.experiment.r4.large-runs", 3),
                    Path.of(System.getProperty("pm.experiment.workspace",
                            Path.of(System.getProperty("java.io.tmpdir"), "pm-qlv-" + stamp).toString())),
                    QuestionEvidence.workRoot(results).resolve("pm-qlv-" + stamp), results,
                    QuestionDriver.resolveExec(),
                    Duration.parse(System.getProperty("pm.experiment.run-timeout", "PT20M")),
                    Warmth.of(System.getProperty(Warmth.PROPERTY)), dataHome,
                    Integer.getInteger(Q5_BOUND_PROPERTY, Scale.Q5_FIRST_BOUND));
        }

        /**
         * @param dataHome where OpenCode keeps its data
         * @return the model per host: {@code pm.experiment.model.<host>}, else {@link #defaultModel}; a host whose
         *         model is the harness default is absent
         */
        static Map<Host, String> models(OpenCodeDataHome dataHome) {
            var models = new EnumMap<Host, String>(Host.class);
            for (var host : Host.values()) {
                var model = System.getProperty("pm.experiment.model." + host.executable(), defaultModel(host, dataHome));
                if (model != null) {
                    models.put(host, model);
                }
            }
            return models;
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
        var item = System.getProperty(ITEM_PROPERTY).strip().toUpperCase(Locale.ROOT);
        var config = Config.load();
        var plans = plans(item, config);
        assertFalse(plans.isEmpty(), "no run planned for " + item);

        var file = measure(item, config, plans, QuestionResults.ResultTarget.fromProperties());

        assertTrue(Files.isRegularFile(file), "no result written for " + item);
    }

    /**
     * Runs the planned runs of one invocation, writes its store directory and the item's result.
     *
     * @param item   the item
     * @param config the configuration
     * @param plans  the planned runs
     * @param target the target of the result
     * @return the result file
     * @throws IOException          if a record or the result cannot be written
     * @throws InterruptedException if interrupted
     * @throws ExecutionException   if a run failed outside its own recording
     */
    static Path measure(String item, Config config, List<RunPlan> plans, QuestionResults.ResultTarget target)
            throws IOException, InterruptedException, ExecutionException {
        var invocation = invocation(item, config, plans);
        var id = invocation.path(QuestionResults.INVOCATION).asText();
        QuestionResults.UnusedScenarios unused = () -> QuestionFixtures.unusedScenarios(telemetry(config)).size();
        QuestionResults.recordInvocation(config.results(), invocation);
        var stops = new SetupStops();
        try {
            execute(plans, config, id, stops);
            if (stops.any()) {
                QuestionResults.recordInvocation(config.results(), stops.applyTo(invocation));
            }
        } finally {
            if (QuestionEvidence.archiveEnabled()) {
                QuestionEvidence.store(config.results(), item, id, unused);
            }
        }
        var result = QuestionResults.result(item, target.select(QuestionResults.runs(config.results(), item)),
                target.select(QuestionResults.invocations(config.results(), item)), unused);
        return QuestionResults.write(target, item, result);
    }

    /**
     * The record of one invocation of an item: its id, which every run record of it carries, and the settings it ran
     * with: runs, fixtures and their limit, hosts, warmth, large runs, the parallel level
     * ({@value QuestionResults#PARALLEL}), the OpenCode data home and models, the R2 ages, the run limit
     * ({@value QuestionResults#RUN_TIMEOUT}), the holds of its runs, its planned runs
     * ({@value QuestionResults#PLANNED_RUNS}), for Q5 its question bound and for R3 the faults its runs inject
     * ({@value QuestionResults#R3_FAULTS}).
     *
     * @param item   the item
     * @param config the configuration
     * @param plans  the runs it plans
     * @return the invocation record
     */
    static ObjectNode invocation(String item, Config config, List<RunPlan> plans) {
        var record = QuestionStub.JSON.createObjectNode();
        record.put(QuestionResults.INVOCATION, item + "-" + Instant.now().toString().replace(":", "")
                .replace(".", "") + "-" + randomHex());
        record.put("item", item);
        record.put("started_at", Instant.now().toString());
        record.put("runs", config.runs());
        record.put("large_runs", config.largeRuns());
        if (config.fixtures() == Integer.MAX_VALUE) {
            record.putNull("fixtures_limit");
        } else {
            record.put("fixtures_limit", config.fixtures());
        }
        record.put("fixtures", plans.stream().map(plan -> plan.fixture().id()).distinct().count());
        var hosts = record.putArray("hosts");
        config.hosts().forEach(host -> hosts.add(host.executable()));
        record.put("warmth", config.warmth().name().toLowerCase(Locale.ROOT));
        record.put(QuestionResults.PARALLEL, config.parallel());
        record.put("opencode_data_home", config.openCodeDataHome().name().toLowerCase(Locale.ROOT));
        record.put("opencode_serial", serialOpenCode(config)
                && plans.stream().anyMatch(QuestionLoopMeasurement::usesOpenCode));
        var models = record.putArray("opencode_models");
        openCodeModels(plans, config).stream().sorted(Comparator.nullsFirst(Comparator.naturalOrder()))
                .forEach(models::add);
        var ages = record.putArray("r2_ages_s");
        durations(R2_AGES, LONGEST).forEach(age -> ages.add(age.toSeconds()));
        record.put(QuestionResults.RUN_TIMEOUT, config.runTimeout().toString());
        var holds = record.putArray("holds_s");
        plans.stream().map(plan -> plan.hold().toSeconds()).distinct().sorted().forEach(holds::add);
        record.put(QuestionResults.PLANNED_RUNS, plans.size());
        if ("Q5".equals(item)) {
            record.put(QuestionResults.QUESTION_BOUND, config.questionBound());
        }
        if ("R3".equals(item)) {
            var faults = record.putArray(QuestionResults.R3_FAULTS);
            plans.stream().map(RunPlan::fault).filter(Objects::nonNull).distinct().sorted()
                    .forEach(fault -> faults.add(fault.name()));
        }
        return record;
    }

    /**
     * @param host     a host
     * @param dataHome where OpenCode keeps its data
     * @return the model a host runs unless {@code pm.experiment.model.<host>} names another: {@value #CLAUDE_MODEL}
     *         for Claude Code, {@value #OPENCODE_GLOBAL_MODEL} for OpenCode on the global data home, else {@code null}
     *         (the harness default; on a per-worker data home only the free models are routable)
     */
    static String defaultModel(Host host, OpenCodeDataHome dataHome) {
        return switch (host) {
            case CLAUDE -> CLAUDE_MODEL;
            case OPENCODE -> dataHome == OpenCodeDataHome.GLOBAL ? OPENCODE_GLOBAL_MODEL : null;
            case ANTIGRAVITY -> null;
        };
    }

    /**
     * @param role a quorum role of Q6 ({@code quorum-a}, {@code quorum-b})
     * @return its {@code <host>:<model>}: {@code pm.experiment.q6.<role>}, else Claude Code with
     *         {@value #CLAUDE_MODEL} for {@code quorum-a} and OpenCode with {@code opencode-go/muse-spark-1.3-contributor}
     *         for any other
     */
    static String q6Assignment(String role) {
        return System.getProperty("pm.experiment.q6." + role, QUORUM_A.equals(role) ? "claude:" + CLAUDE_MODEL
                : "opencode:opencode-go/muse-spark-1.3-contributor");
    }

    /**
     * @param plan a planned run
     * @return whether it starts an OpenCode worker: as the asker, as the continuing host, or as a voter of Q6
     */
    static boolean usesOpenCode(RunPlan plan) {
        if (plan.host() == Host.OPENCODE || plan.continueHost() == Host.OPENCODE) {
            return true;
        }
        return VOTE.equals(plan.kind()) && QUORUMS.values().stream().flatMap(quorum -> quorum.roles().stream())
                .distinct().anyMatch(role -> Host.of(q6Assignment(role).split(":", 2)[0]) == Host.OPENCODE);
    }

    /**
     * @param plans  the planned runs
     * @param config the configuration
     * @return the OpenCode models the runs start, the harness default as {@code null}; empty when none starts OpenCode
     */
    static Set<String> openCodeModels(List<RunPlan> plans, Config config) {
        var models = new HashSet<String>();
        for (var plan : plans) {
            if (plan.host() == Host.OPENCODE) {
                models.add(plan.model());
            }
            if (plan.continueHost() == Host.OPENCODE) {
                models.add(config.model(Host.OPENCODE));
            }
            if (VOTE.equals(plan.kind())) {
                QUORUMS.values().stream().flatMap(quorum -> quorum.roles().stream()).distinct()
                        .map(role -> q6Assignment(role).split(":", 2))
                        .filter(assignment -> Host.of(assignment[0]) == Host.OPENCODE)
                        .forEach(assignment -> models.add(assignment.length > 1 ? assignment[1] : null));
            }
        }
        return models;
    }

    /**
     * @param config the configuration
     * @return whether the runs that start OpenCode run one at a time: on the global data home every OpenCode worker
     *         writes the one SQLite database of the operator's data home
     */
    static boolean serialOpenCode(Config config) {
        return config.openCodeDataHome() == OpenCodeDataHome.GLOBAL;
    }

    /**
     * The launch of a worker of the measurement: the contract of its role with {@link #WORKTREE_NOTE}, and the work
     * tools of its host.
     *
     * @param worker      the worker id
     * @param generation  its generation
     * @param role        its role
     * @param host        its host
     * @param resumable   whether its session is kept
     * @param toolTimeout the host's MCP tool timeout, {@code null} for the host default
     * @return the launch
     */
    static Launch workerLaunch(String worker, int generation, String role, Host host, boolean resumable,
            Duration toolTimeout) {
        return new Launch(worker, generation, role, QuestionDriver.contract(role, WORKTREE_NOTE), resumable,
                toolTimeout, workTools(host), null);
    }

    /**
     * The launch of a voter of Q6: a fresh worker of the vote task's role, its worker id the vote task's id, with the
     * contract of its role and the vote instruction, no work tools and no stored session.
     *
     * @param voteTask the vote task ({@value QuestionStub#VOTE_PREFIX}{@code <question_set>-<role>})
     * @param role     its role
     * @return the launch
     */
    static Launch voterLaunch(String voteTask, String role) {
        return new Launch(voteTask, 1, role, QuestionDriver.contract(role, "You vote blind on the questions in "
                + "facts.questions: follow submit:vote with answers, one item per question: {question_id, choice (a "
                + "branch_key), confidence (0..100), rationale}."), false, null, List.of(), null);
    }

    /**
     * @param host a host
     * @return the host's file tools a worker of real work gets beside the stub's tools; none on Antigravity, which
     *         gets all its tools
     */
    static List<String> workTools(Host host) {
        return switch (host) {
            case CLAUDE -> List.of("Read", "Write", "Edit", "Glob", "Grep");
            case OPENCODE -> List.of("read", "write", "edit", "glob", "grep");
            case ANTIGRAVITY -> List.of();
        };
    }

    // --- plans ---

    /**
     * The runs of one invocation of an item at the revised scale.
     *
     * @param item   the item
     * @param config the configuration
     * @return the planned runs
     * @throws IOException              if the fixtures cannot be read
     * @throws IllegalArgumentException for an unknown item
     */
    static List<RunPlan> plans(String item, Config config) throws IOException {
        var plans = new ArrayList<RunPlan>();
        switch (item) {
            case "Q1", "Q2", "Q3", "Q4" -> {
                var fixtures = limit(QuestionFixtures.applyAnswer(config.cache()), config);
                if ("Q2".equals(item)) {
                    fixtures.addAll(limit(QuestionFixtures.controls(config.cache()), config));
                }
                plans.addAll(applyAnswer(item, config, fixtures,
                        Optional.ofNullable(System.getProperty("pm.experiment.q4.other-host")).map(Host::of)));
            }
            case "Q5" -> plans.addAll(q5(config, limit(QuestionFixtures.twoFlaws(config.cache()), config)));
            case "Q6" -> plans.addAll(q6(config, limit(QuestionFixtures.applyAnswer(config.cache()), config),
                    q6Kinds(System.getProperty(Q6_KINDS_PROPERTY))));
            case "Q7" -> plans.addAll(q7(config, limit(QuestionFixtures.applyAnswer(config.cache()), config),
                    Duration.parse(System.getProperty("pm.experiment.q7.open", "PT1H"))));
            case "R1" -> plans.addAll(r1(config, durations("pm.experiment.r1.durations", LONGEST)));
            case "R2" -> plans.addAll(r2(config, durations(R2_AGES, LONGEST),
                    Optional.ofNullable(System.getProperty("pm.experiment.r2.expired-session"))));
            case "R3" -> plans.addAll(r3(config, limit(QuestionFixtures.applyAnswer(config.cache()), config),
                    Fault.select(System.getProperty(Fault.PROPERTY))));
            case "R4" -> plans.addAll(r4(config,
                    () -> limit(QuestionFixtures.largeScenarios(telemetry(config), config.clones()), config)));
            default -> throw new IllegalArgumentException("unknown item " + item + ": Q1 to Q7, R1 to R4");
        }
        return plans;
    }

    /**
     * Plans Q1 to Q4: per host, warmth, fixture and run index the entity form and, beside a control, the envelope
     * form, in the fallback continuation. Q4 rotates the reply over option, text and default, and with another host
     * continues every second run there.
     *
     * @param item      the item
     * @param config    the configuration
     * @param fixtures  the fixtures, limited
     * @param otherHost the continuing host of Q4, empty for the asker's
     * @return the planned runs
     */
    static List<RunPlan> applyAnswer(String item, Config config, List<Fixture> fixtures, Optional<Host> otherHost) {
        var plans = new ArrayList<RunPlan>();
        each(config, config.hosts(), fixtures, (host, warm, fixture, index) -> {
            for (var kind : fixture.control() ? List.of(ENTITY) : List.of(ENTITY, "envelope")) {
                var plan = RunPlan.of(item, index, host, config.model(host), warm, Form.FALLBACK, fixture, kind);
                if ("Q4".equals(item)) {
                    plan = plan.with(Reply.values()[index % 3]);
                    if (otherHost.isPresent() && index % 2 == 1) {
                        plan = plan.continuingOn(otherHost.get());
                    }
                }
                plans.add(plan);
            }
        });
        return plans;
    }

    /**
     * Plans Q5: per host, warmth, fixture and run index a run answered on the first flaw only and one that restates
     * the options and measures the reached bound ({@link Config#questionBound()}).
     *
     * @param config   the configuration
     * @param fixtures the two-flaw fixtures, limited
     * @return the planned runs
     */
    static List<RunPlan> q5(Config config, List<Fixture> fixtures) {
        var plans = new ArrayList<RunPlan>();
        each(config, config.hosts(), fixtures, (host, warm, fixture, index) -> {
            var plan = RunPlan.of("Q5", index, host, config.model(host), warm, Form.FALLBACK, fixture, ENTITY);
            plans.add(plan.with(Reply.FIRST_ONLY));
            plans.add(plan.with(Reply.RESTATE_OPTIONS).bounded());
        });
        return plans;
    }

    /**
     * Plans Q6: per host, warmth, fixture and run index a run of each selected kind, a vote run and a consult run by
     * default ({@value #Q6_KINDS_PROPERTY}).
     *
     * @param config   the configuration
     * @param fixtures the fixtures, limited
     * @param kinds    the kinds to run ({@link #q6Kinds(String)})
     * @return the planned runs
     */
    static List<RunPlan> q6(Config config, List<Fixture> fixtures, List<String> kinds) {
        var plans = new ArrayList<RunPlan>();
        each(config, config.hosts(), fixtures, (host, warm, fixture, index) -> {
            for (var kind : kinds) {
                plans.add(RunPlan.of("Q6", index, host, config.model(host), warm, Form.FALLBACK, fixture, kind));
            }
        });
        return plans;
    }

    /**
     * The kinds of Q6 to run, {@value #Q6_KINDS_PROPERTY}: {@code vote} and {@code consult}, comma-separated. The
     * Claude Code part runs the consultation part only ({@code consult}); the vote quorum belongs to the second part.
     *
     * @param value the property's value, {@code null} or blank for both
     * @return the kinds, in the order vote, consult
     * @throws IllegalArgumentException for any other kind
     */
    static List<String> q6Kinds(String value) {
        if (value == null || value.isBlank()) {
            return List.of(VOTE, CONSULT);
        }
        var named = new HashSet<String>();
        for (var part : value.split(",")) {
            var kind = part.strip().toLowerCase(Locale.ROOT);
            if (!VOTE.equals(kind) && !CONSULT.equals(kind)) {
                throw new IllegalArgumentException(Q6_KINDS_PROPERTY + ": unknown kind '" + part.strip()
                        + "', expected vote or consult");
            }
            named.add(kind);
        }
        return Stream.of(VOTE, CONSULT).filter(named::contains).toList();
    }

    /**
     * Plans Q7: fresh runs on Claude Code only, with the record open for the open time and the stub restarted in it.
     *
     * @param config   the configuration
     * @param fixtures the fixtures, limited
     * @param open     the open time
     * @return the planned runs
     */
    static List<RunPlan> q7(Config config, List<Fixture> fixtures, Duration open) {
        var plans = new ArrayList<RunPlan>();
        for (var fixture : fixtures) {
            for (var index = 0; index < config.runs(); index++) {
                plans.add(RunPlan.of("Q7", index, Host.CLAUDE, config.model(Host.CLAUDE), false, Form.FALLBACK,
                        fixture, ENTITY).holding(open).restarting());
            }
        }
        return plans;
    }

    /**
     * Plans R1 on the probe: per host, warmth and run index a hold run of each duration.
     *
     * @param config    the configuration
     * @param durations the durations, longest first
     * @return the planned runs
     */
    static List<RunPlan> r1(Config config, List<Duration> durations) {
        var plans = new ArrayList<RunPlan>();
        each(config, config.hosts(), probe(), (host, warm, fixture, index) -> {
            for (var duration : durations) {
                plans.add(RunPlan.of("R1", index, host, config.model(host), warm, Form.HOLD, fixture, ENTITY)
                        .holding(duration));
            }
        });
        return plans;
    }

    /**
     * Plans R2 on the probe: per host, warmth and run index a resume of each age, one with the stub restarted while
     * the record is open, a resume of a missing session ({@link QuestionDriver#missingSessionId(Host)}), and one of an
     * expired session where one is named.
     *
     * @param config  the configuration
     * @param ages    the ages, longest first
     * @param expired the id of an expired session, empty for none
     * @return the planned runs
     */
    static List<RunPlan> r2(Config config, List<Duration> ages, Optional<String> expired) {
        var plans = new ArrayList<RunPlan>();
        each(config, config.hosts(), probe(), (host, warm, fixture, index) -> {
            for (var age : ages) {
                plans.add(RunPlan.of("R2", index, host, config.model(host), warm, Form.RESUME, fixture, ENTITY)
                        .holding(age));
            }
            var base = RunPlan.of("R2", index, host, config.model(host), warm, Form.RESUME, fixture, ENTITY);
            plans.add(base.holding(Duration.ofMinutes(5)).restarting());
            plans.add(base.resumingSession(QuestionDriver.missingSessionId(host)));
            expired.ifPresent(session -> plans.add(base.resumingSession(session)));
        });
        return plans;
    }

    /**
     * Plans R3: per host, warmth, fixture and run index each selected fault, in the order of {@link Fault}, in its
     * continuation form ({@link Fault#form()}) with its hold ({@link Fault#hold(Host)}).
     *
     * @param config   the configuration
     * @param fixtures the fixtures, limited
     * @param faults   the selected faults ({@link Fault#select(String)})
     * @return the planned runs
     */
    static List<RunPlan> r3(Config config, List<Fixture> fixtures, List<Fault> faults) {
        var plans = new ArrayList<RunPlan>();
        each(config, config.hosts(), fixtures, (host, warm, fixture, index) -> {
            for (var fault : Fault.values()) {
                if (faults.contains(fault)) {
                    plans.add(RunPlan.of("R3", index, host, config.model(host), warm, fault.form(), fixture, ENTITY)
                            .holding(fault.hold(host)).faulting(fault));
                }
            }
        });
        return plans;
    }

    /** The large work of R4, loaded only when it has runs. */
    @FunctionalInterface
    interface LargeWork {
        /**
         * @return the large scenarios
         * @throws IOException if they cannot be loaded
         */
        List<Fixture> load() throws IOException;
    }

    /**
     * Plans R4: the probe as small work and the large work, each with every continuation form (hold, resume and
     * fallback, decision of 2026-10-31), the small work with {@link Config#runs()} runs and the large work with
     * {@link Config#largeRuns()} runs. A part with zero runs plans no run, and the large work is not loaded then.
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
                plans.add(RunPlan.of("R4", index, host, config.model(host), warm, form, fixture, ENTITY)
                        .holding(Duration.ofMinutes(1)));
            }
        });
        if (config.largeRuns() > 0) {
            each(config.largeRuns(), config.warmth(), config.hosts(), large.load(), (host, warm, fixture, index) -> {
                for (var form : Form.values()) {
                    plans.add(RunPlan.of("R4", index, host, config.model(host), warm, form, fixture, ENTITY)
                            .holding(Duration.ofMinutes(1)));
                }
            });
        }
        return plans;
    }

    /**
     * The stub's settings of a run: the host's bounded wait as the window, the quorums of a vote run, the question
     * bound of a Q5 run ({@link Config#questionBound()}), and progress every 5 s. A {@link Fault#CALL_CUT} run gets the
     * window of its cut setting and no progress frame on a held call; every run of R3 gets a stub that does not lose a
     * worker at its process exit, so the missing acknowledgement is the only detection R3 measures.
     *
     * @param plan    the planned run
     * @param config  the configuration
     * @param stubDir the stub directory
     * @param socket  the stub's socket
     * @return the settings
     */
    static QuestionStub.Settings stubSettings(RunPlan plan, Config config, Path stubDir, Path socket) {
        var settings = QuestionStub.Settings.of(stubDir, socket).withWindow(plan.host().boundedWait())
                .withQuorums(VOTE.equals(plan.kind()) ? QUORUMS : Map.of());
        if ("Q5".equals(plan.item())) {
            settings = settings.withQuestionBound(config.questionBound());
        }
        if (plan.fault() == Fault.CALL_CUT) {
            var window = plan.host().cutSetting().window(plan.host());
            settings = new QuestionStub.Settings(settings.directory(), settings.socket(), window,
                    Host.CutSetting.noProgress(window), settings.questionBound(), settings.quorums(),
                    settings.loseOnExit());
        }
        return "R3".equals(plan.item()) ? settings.withLoseOnExit(false) : settings;
    }

    /**
     * @param plan a planned run
     * @return the tool timeout its first generation is launched with: the cut setting's of a {@link Fault#CALL_CUT}
     *         run ({@code null} on Antigravity, which has none), else {@code null}, the host default
     */
    static Duration toolTimeout(RunPlan plan) {
        return plan.fault() == Fault.CALL_CUT ? plan.host().cutSetting().toolTimeout() : null;
    }

    /**
     * @param plan a planned run
     * @return whether its fresh asker is ended by the driver at its silence limit after the ask, before the open time
     *         starts: the fresh runs of Q7 ({@link QuestionDriver#endAsker})
     */
    static boolean endsAskerBeforeOpenTime(RunPlan plan) {
        return "Q7".equals(plan.item()) && !plan.warm() && plan.form() == Form.FALLBACK;
    }

    /**
     * The warm/fresh axis of the run matrix, set by {@value #PROPERTY}: {@code both} (the default), {@code fresh} or
     * {@code warm}.
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

    /**
     * @return the telemetry checkout of {@code pm.experiment.telemetry} and {@code pm.experiment.telemetry.ref}, with
     *         the defaults of {@link Config#load()}, for a re-aggregation of R4 without a configuration
     * @throws IOException if the default checkout cannot be found
     */
    static QuestionFixtures.Source telemetryFromProperties() throws IOException {
        var telemetry = System.getProperty("pm.experiment.telemetry");
        var checkout = telemetry == null
                ? QuestionFixtures.siblingRepositories(ROOT).resolve("plan-marshall-telemetry")
                : Path.of(telemetry);
        return QuestionFixtures.telemetry(checkout,
                System.getProperty("pm.experiment.telemetry.ref", QuestionFixtures.TELEMETRY_REF));
    }

    private static List<Fixture> limit(List<Fixture> fixtures, Config config) {
        return new ArrayList<>(fixtures.subList(0, Math.min(fixtures.size(), config.fixtures())));
    }

    /**
     * @param property a property of ISO durations, comma-separated
     * @param defaults its value when it is not set
     * @return the durations, in the order named
     */
    static List<Duration> durations(String property, String defaults) {
        var durations = new ArrayList<Duration>();
        for (var value : System.getProperty(property, defaults).split(",")) {
            durations.add(Duration.parse(value.strip()));
        }
        return durations;
    }

    private static String randomHex() {
        var bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    // --- execution ---

    /**
     * The stops of one invocation: the setup failures ({@value RunView#SETUP_FAILED}) — the scenarios whose setup failed
     * in a run, how many runs failed so, and how many runs of those scenarios were not started afterwards — and the
     * refusals by the account's usage limit ({@value RunView#NOT_RUN}), after the first of which no further run of the
     * invocation starts. The invocation's record is appended again after its runs with the counts
     * ({@link #applyTo(ObjectNode)}).
     */
    static final class SetupStops {

        private final Set<String> failed = ConcurrentHashMap.newKeySet();
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicInteger stopped = new AtomicInteger();
        private final AtomicInteger refusals = new AtomicInteger();
        private final AtomicInteger limitStopped = new AtomicInteger();
        private final Set<String> resets = ConcurrentHashMap.newKeySet();

        /**
         * @param fixture a fixture
         * @return whether the run is not started: the usage limit refused an earlier run of the invocation, or a run of
         *         the fixture failed its setup; counts the run as stopped for that reason then
         */
        boolean stop(String fixture) {
            if (refusals.get() > 0) {
                limitStopped.incrementAndGet();
                return true;
            }
            if (!failed.contains(fixture)) {
                return false;
            }
            stopped.incrementAndGet();
            return true;
        }

        /**
         * Records a run the account's usage limit refused.
         *
         * @param resetsAt the reset time the refusal named, empty when none
         */
        void refused(String resetsAt) {
            refusals.incrementAndGet();
            if (!resetsAt.isEmpty()) {
                resets.add(resetsAt);
            }
        }

        /** @return the runs the usage limit refused */
        int refusals() {
            return refusals.get();
        }

        /** @return the runs not started because the usage limit refused an earlier run of the invocation */
        int limitStopped() {
            return limitStopped.get();
        }

        /** @return whether the invocation's record needs the counts appended again */
        boolean any() {
            return failures() > 0 || refusals() > 0;
        }

        /**
         * Records a run whose setup of the fixture failed.
         *
         * @param fixture the fixture
         */
        void failed(String fixture) {
            failed.add(fixture);
            failures.incrementAndGet();
        }

        /** @return the runs whose setup failed */
        int failures() {
            return failures.get();
        }

        /** @return the runs not started because an earlier run of their scenario failed its setup */
        int stopped() {
            return stopped.get();
        }

        /**
         * @param invocation the invocation record written before the runs
         * @return a copy with, after a setup failure, {@value RunView#SETUP_FAILED} (the count),
         *         {@code setup_failed_fixtures}, {@value QuestionResults#SETUP_STOPPED_RUNS} and a message that names the
         *         stop, and after a refusal {@code usage_limit_refused} (the count),
         *         {@value QuestionResults#USAGE_LIMIT_STOPPED_RUNS}, {@code usage_limit_resets_at} and a message
         */
        ObjectNode applyTo(ObjectNode invocation) {
            var copy = invocation.deepCopy();
            if (refusals() > 0) {
                copy.put("usage_limit_refused", refusals());
                copy.put(QuestionResults.USAGE_LIMIT_STOPPED_RUNS, limitStopped());
                var times = copy.putArray("usage_limit_resets_at");
                resets.stream().sorted().forEach(times::add);
                copy.put("usage_limit_stop_message", "the account's usage limit refused " + refusals() + " run(s); "
                        + "the invocation's remaining " + limitStopped() + " run(s) were not started");
            }
            if (failures() == 0) {
                return copy;
            }
            copy.put(RunView.SETUP_FAILED, failures());
            var fixtures = copy.putArray("setup_failed_fixtures");
            failed.stream().sorted().forEach(fixtures::add);
            copy.put(QuestionResults.SETUP_STOPPED_RUNS, stopped());
            copy.put("setup_stop_message", "the setup of " + String.join(", ", failed.stream().sorted().toList())
                    + " failed in " + failures() + " run(s); the invocation's remaining " + stopped() + " run(s) of "
                    + "that scenario were not started");
            return copy;
        }
    }

    /**
     * Runs the planned runs on {@link Config#parallel()} threads, those that start OpenCode on the global data home one
     * at a time.
     *
     * @param plans      the planned runs
     * @param config     the configuration
     * @param invocation the invocation id
     * @param stops      the setup failures of the invocation
     * @throws InterruptedException if interrupted
     * @throws ExecutionException   if a run failed outside its own recording
     */
    static void execute(List<RunPlan> plans, Config config, String invocation, SetupStops stops)
            throws InterruptedException, ExecutionException {
        try (var executor = Executors.newFixedThreadPool(config.parallel())) {
            var futures = new ArrayList<Future<?>>();
            for (var plan : plans) {
                var serial = serialOpenCode(config) && usesOpenCode(plan);
                futures.add(executor.submit(() -> {
                    if (stops.stop(plan.fixture().id())) {
                        return null;
                    }
                    if (serial) {
                        OPENCODE_SERIAL.lock();
                    }
                    try {
                        runOne(plan, config, invocation, stops);
                    } finally {
                        if (serial) {
                            OPENCODE_SERIAL.unlock();
                        }
                    }
                    return null;
                }));
            }
            for (var future : futures) {
                future.get();
            }
        }
    }

    /**
     * Runs one planned run and appends its record.
     *
     * @param plan       the planned run
     * @param config     the configuration
     * @param invocation the invocation id
     * @param stops      the setup failures of the invocation
     * @throws IOException if the record cannot be appended
     */
    static void runOne(RunPlan plan, Config config, String invocation, SetupStops stops) throws IOException {
        var suffix = randomHex();
        var id = "%s-%s-%s-%s-%03d-%s".formatted(plan.item(), plan.host().executable(), plan.warm() ? "warm" : "fresh",
                plan.fixture().id().replace('/', '_'), plan.index(), suffix);
        var base = config.workspace().resolve(id);
        var stubDir = stubDirectory(config, id);
        var stubSettings = stubSettings(plan, config, stubDir, Path.of("/tmp", "pmq-" + suffix + ".sock"));
        var record = runRecord(plan, config, stubDir);
        record.put(QuestionResults.INVOCATION, invocation);
        if (!stubSettings.loseOnExit()) {
            record.put(QuestionResults.STUB_LOSE_ON_EXIT, false);
        }
        try (var driver = QuestionDriver.start(stubSettings, new QuestionDriver.Settings(plan.host(), plan.model(),
                     base.resolve("workers"), config.results().resolve("runs").resolve(id), config.exec(),
                     config.openCodeDataHome(), config.parallel()))) {
            new Session(driver, plan, config, record, stubSettings.questionBound()).run();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            record.put("error", "interrupted");
        } catch (QuestionFixtures.SetupFailure e) {
            recordSetupFailure(record, e);
            stops.failed(plan.fixture().id());
        } catch (IOException | IllegalStateException e) {
            record.put("error", String.valueOf(e.getMessage()));
        }
        if (RunView.notRun(record)) {
            stops.refused(record.path(QuestionDriver.RESETS_AT).asText(""));
        }
        QuestionResults.recordRun(config.results(), record);
    }

    /**
     * Records a run ended by a quota error of a worker's harness: {@value QuestionDriver#QUOTA_ERROR}, its rule, worker,
     * generation and detail. A refusal by the account's usage limit ({@value QuestionDriver#RATE_LIMIT_EVENT}) also
     * records the event's status, {@code rateLimitType} and {@code resetsAt} and marks the run as not run
     * ({@value RunView#NOT_RUN}, reason {@value RunView#USAGE_LIMIT}): it is never a failed run.
     *
     * @param record the run record
     * @param quota  the quota error
     */
    static void recordQuotaError(ObjectNode record, QuestionDriver.QuotaError quota) {
        record.put(QuestionDriver.QUOTA_ERROR, true);
        record.put(QuestionDriver.QUOTA_RULE, quota.rule());
        record.put("quota_error_worker", quota.worker());
        record.put("quota_error_generation", quota.generation());
        record.put("quota_error_detail", quota.detail());
        if (quota.usageLimit()) {
            record.put(QuestionDriver.RATE_LIMIT_STATUS, quota.refusal().status());
            record.put(QuestionDriver.RATE_LIMIT_TYPE, quota.refusal().rateLimitType());
            record.put(QuestionDriver.RESETS_AT, quota.refusal().resetsAt());
            record.put(RunView.NOT_RUN, true);
            record.put(RunView.NOT_RUN_REASON, RunView.USAGE_LIMIT);
        }
    }

    /**
     * @param config the configuration
     * @param run    a run id
     * @return the run's stub directory, {@code <run id>/stub} below the invocation's evidence root
     */
    static Path stubDirectory(Config config, String run) {
        return config.evidence().resolve(run).resolve("stub");
    }

    /**
     * Records a run whose fixture or workspace setup failed before any worker launched: {@value RunView#SETUP_FAILED},
     * its reason and the full message.
     *
     * @param record  the run record
     * @param failure the setup failure
     */
    static void recordSetupFailure(ObjectNode record, QuestionFixtures.SetupFailure failure) {
        record.put(RunView.SETUP_FAILED, true);
        record.put(RunView.SETUP_FAILED_REASON, failure.reason());
        record.put("error", String.valueOf(failure.getMessage()));
    }

    /**
     * @param plan    a planned run
     * @param config  the configuration
     * @param stubDir its stub directory
     * @return its run record before the run: its plan, the parallel level ({@value QuestionResults#PARALLEL}) and, for
     *         Q5, the question bound ({@value QuestionResults#QUESTION_BOUND})
     */
    static ObjectNode runRecord(RunPlan plan, Config config, Path stubDir) {
        var record = QuestionStub.JSON.createObjectNode();
        record.put("item", plan.item());
        record.put("run", plan.index());
        record.put("host", plan.host().executable());
        record.put("model", plan.model() == null ? RunView.HARNESS_DEFAULT : plan.model());
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
        record.put(QuestionResults.PARALLEL, config.parallel());
        if ("Q5".equals(plan.item())) {
            record.put(QuestionResults.QUESTION_BOUND, config.questionBound());
        }
        return record;
    }

    /** One run: the asking task, its questions, their answers, and its continuation until the entry is done. */
    private static final class Session {

        private final QuestionDriver driver;
        private final RunPlan plan;
        private final Config config;
        private final ObjectNode record;
        private final int questionBound;
        private final List<QuestionDriver> siblings = new ArrayList<>();
        private final Set<String> seenDecisions = new HashSet<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private int generation = 1;
        private boolean ended;

        Session(QuestionDriver driver, RunPlan plan, Config config, ObjectNode record, int questionBound) {
            this.driver = driver;
            this.plan = plan;
            this.config = config;
            this.record = record;
            this.questionBound = questionBound;
        }

        void run() throws IOException, InterruptedException {
            var fixture = plan.fixture();
            var canary = QuestionFixtures.prepare(fixture, driver.settings().workspace().resolve("w1"));
            record.put("canary", canary);
            var spec = spec(fixture);
            var helpers = startHelpers();
            try {
                var current = start(spec);
                if (plan.negative() != null) {
                    negativeResume(current);
                    return;
                }
                var deadline = System.currentTimeMillis() + config.runTimeout().toMillis()
                        + plan.hold().toMillis() * 4;
                var rounds = 0;
                while (!ended && !entryDone() && System.currentTimeMillis() < deadline && rounds <= questionBound) {
                    var asked = nextAsked(deadline);
                    if (asked.isEmpty()) {
                        break;
                    }
                    rounds++;
                    current = round(asked.get(), current, rounds);
                    if (current == null) {
                        break;
                    }
                }
                awaitEntry(deadline);
                record.put(QuestionResults.TIMED_OUT, !ended && !entryDone());
            } finally {
                running.set(false);
                for (var helper : helpers) {
                    helper.join(POLL.toMillis() * 5);
                }
                recordVoterEarlyExits();
                recordClientVersion();
                endWarm();
                record.set("workers", workers());
                for (var sibling : siblings) {
                    sibling.close();
                }
            }
        }

        /** @return the generation that goes on after the round, {@code null} when the answer did not come */
        private Worker round(JsonNode asked, Worker current, int rounds) throws IOException, InterruptedException {
            if (rounds == 1) {
                driver.worktreeDigest(current);
                if (plan.form() == Form.FALLBACK) {
                    awaitStop(current, asked);
                    driver.worktreeDigest(current);
                }
            }
            var hold = plan.hold();
            if (rounds == 1 && plan.fault() == Fault.STOP_BEFORE_ANSWER) {
                stopWhileHolding(current, asked);
            }
            if (rounds == 1 && plan.fault() == Fault.CALL_CUT) {
                hold = awaitCut(current, asked);
            }
            if (!answer(asked, hold)) {
                return null;
            }
            return continueAfter(asked, current);
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
            var launch = launch("w1", 1, plan.host(), toolTimeout(plan));
            if (plan.warm()) {
                driver.stub().queue(spec);
                return driver.startWarm(launch);
            }
            return driver.startFresh(spec, launch);
        }

        private Launch launch(String worker, int launchGeneration, Host host, Duration toolTimeout) {
            return workerLaunch(worker, launchGeneration, ROLE, host, plan.form() == Form.RESUME, toolTimeout);
        }

        /**
         * {@link Fault#STOP_BEFORE_ANSWER}: stops the asker once it holds {@code pm_wait} for the asked task, before
         * the answer, and records whether the stop was injected, when, and the start of the hold it was injected in.
         */
        private void stopWhileHolding(Worker asker, JsonNode asked) throws IOException, InterruptedException {
            var askedAt = asked.path("at").asLong();
            var held = driver.stopWhileHolding(asker, askedAt, plan.host().silenceLimit());
            record.put(FAULT_INJECTED_FIELD, held.isPresent());
            if (held.isPresent()) {
                record.put(FAULT_INJECTED_FIELD + "_at", System.currentTimeMillis());
                record.put("fault_held", true);
                record.put("fault_hold_started_at", held.get().start());
                record.put("fault_after_ask_ms", System.currentTimeMillis() - askedAt);
                recordSignalled(asker);
            } else {
                record.put("fault_held", false);
                record.put("fault_not_injected_reason", "no_hold");
            }
        }

        /**
         * {@link Fault#CALL_CUT}: waits, up to the time the operator's answer is due, for the host to cut the asker's
         * held {@code pm_wait}, and records how the run made the host cut and whether the logs show the cut.
         *
         * @return the rest of the operator's hold
         */
        private Duration awaitCut(Worker asker, JsonNode asked) throws IOException, InterruptedException {
            var setting = plan.host().cutSetting();
            var askedAt = asked.path("at").asLong();
            var due = askedAt + plan.hold().toMillis();
            record.put("call_cut_means", setting.means());
            record.put("call_cut_tool_timeout_ms", setting.toolTimeout() == null ? -1
                    : setting.toolTimeout().toMillis());
            record.put("call_cut_host_cut_ms", setting.hostCut().toMillis());
            record.put("call_cut_window_s", setting.window(plan.host()).toSeconds());
            record.put("call_cut_progress", false);
            var cut = driver.awaitCallCut(asker, askedAt, due, setting);
            record.put(QuestionResults.CALL_CUT_FIELD, cut.isPresent());
            record.put(FAULT_INJECTED_FIELD, cut.isPresent());
            cut.ifPresent(value -> {
                record.put(FAULT_INJECTED_FIELD + "_at", value.cutAt());
                record.put("call_cut_evidence", value.evidence());
                record.put("call_cut_at", value.cutAt());
                record.put("call_cut_after_ms", value.afterMs());
            });
            return Duration.ofMillis(Math.max(0, due - System.currentTimeMillis()));
        }

        /**
         * Waits for the answer of the asked decision ({@link QuestionDriver#awaitAnswer}): the operator's, after the
         * given hold, or, in a vote run, its quorum's decision. Each operator answer is composed so that its branch
         * never contradicts its reply text ({@link QuestionFixtures#composeAnswer}), recorded in the run record
         * ({@link AnswerAudit#recordComposition}) and logged per part as {@value #ANSWER_COMPOSED}.
         */
        private boolean answer(JsonNode asked, Duration operatorHold) throws InterruptedException, IOException {
            var hold = operatorHold;
            if (plan.restart()) {
                Thread.sleep(hold.dividedBy(2)); // NOSONAR java:S2925 - the sleep is the simulated operator's hold
                driver.restartStub();
                hold = hold.dividedBy(2);
            }
            var answered = driver.awaitAnswer(asked.path(DECISION).asText(), ANSWER_WAIT, hold, decision -> {
                var composition = QuestionFixtures.composeAnswer(plan.fixture(), decision, plan.reply());
                var decisionId = decision.path("id").asText();
                AnswerAudit.recordComposition(record, decisionId, plan.reply(), composition);
                for (var note : composition.notes()) {
                    driver.stub().event(ANSWER_COMPOSED, AnswerAudit.compositionFields(decisionId, plan.reply(),
                            note));
                }
                return composition.parts();
            });
            return answered.isPresent();
        }

        private Worker continueAfter(JsonNode asked, Worker current) throws IOException, InterruptedException {
            var decision = asked.path(DECISION).asText();
            var task = asked.path("task").asText();
            return switch (plan.form()) {
                case HOLD -> hold(current, decision, task);
                case RESUME -> resume(current, task, decision);
                case FALLBACK -> fallback(current, decision);
            };
        }

        private Worker hold(Worker current, String decision, String task) throws IOException, InterruptedException {
            if (plan.fault() == Fault.MODEL_HANG || plan.fault() == Fault.KILL) {
                driver.awaitEvent(event -> "answer_delivered".equals(event.path(EVENT).asText())
                                && current.id().equals(event.path("worker").asText()),
                        System.currentTimeMillis() + plan.host().silenceLimit().toMillis());
                injected(current, plan.fault());
            }
            if (driver.superviseAnswer(current, plan.host().silenceLimit().plus(Duration.ofMinutes(1)))) {
                return current;
            }
            if (recordLoss(current)) {
                var step = recover(current, decision, task).step();
                if (step == RecoveryStep.TAKEN || step == RecoveryStep.GAP) {
                    return current;
                }
            }
            return continuation(decision, current);
        }

        /**
         * Resumes the asker's session after the answer; a resumed generation whose answer acknowledgement is missing
         * is followed by the step the stub's state gives ({@link QuestionDriver#recovery}).
         */
        private Worker resume(Worker current, String task, String decision) throws IOException, InterruptedException {
            driver.awaitExit(current, plan.host().silenceLimit());
            var session = driver.sessionId(current);
            if (session.isEmpty()) {
                record.put("no_session", true);
                return current;
            }
            var resumed = driver.resume(current, task, session.get(), ++generation);
            if (plan.fault() == Fault.RESUMED_EXIT) {
                injected(resumed, Fault.RESUMED_EXIT);
            }
            if (driver.superviseAnswer(resumed, Duration.ofMinutes(2))) {
                return resumed;
            }
            recordLoss(resumed);
            var recovery = recover(resumed, decision, task);
            return switch (recovery.step()) {
                case TAKEN, GAP -> resumed;
                case CONTINUE -> driver.startLeased(recovery.task(), recovery.entity(),
                        launch(current.id(), ++generation, plan.host(), null));
                case RESUME, WAIT -> {
                    var again = driver.resume(current, task, session.get(), ++generation);
                    driver.superviseAnswer(again, Duration.ofMinutes(2));
                    yield again;
                }
            };
        }

        /** Injects a fault and records it and its time. */
        private void injected(Worker worker, Fault fault) {
            driver.inject(worker, fault);
            record.put(FAULT_INJECTED_FIELD, true);
            record.put(FAULT_INJECTED_FIELD + "_at", System.currentTimeMillis());
            recordSignalled(worker);
        }

        private void recordSignalled(Worker worker) {
            driver.signalledPids(worker).ifPresent(count -> record.put(FAULT_SIGNALLED_PIDS_FIELD, count));
        }

        /**
         * Records the first loss the driver detected in the run ({@value QuestionDriver#LOST_DETECTED}): by what,
         * after how long, and who saw it first; a run with a fault also records the exit observation
         * ({@link RunReadings#exitObservation}).
         *
         * @return whether the driver detected a loss of the generation
         */
        private boolean recordLoss(Worker worker) throws IOException {
            var events = QuestionDriver.jsonLinesSoFar(driver.stub().events());
            var detected = events.stream()
                    .filter(event -> QuestionDriver.LOST_DETECTED.equals(event.path(EVENT).asText())
                            && worker.id().equals(event.path("worker").asText())
                            && worker.generation() == event.path("generation").asInt())
                    .findFirst();
            if (detected.isEmpty()) {
                return false;
            }
            if (!record.has("loss_by")) {
                var event = detected.get();
                record.put("loss_by", event.path("by").asText());
                record.put("loss_after_ms", event.path("after_ms").asLong());
                record.put("loss_first_by", event.path("first_by").asText());
                record.put("loss_stub_reason", event.path("stub_reason").asText(""));
                record.put("loss_stub_after_ms", event.path("stub_after_ms").asLong(-1));
                if (plan.fault() != null) {
                    var observation = RunReadings.exitObservation(events, worker.id(), worker.generation());
                    record.put("loss_detected_at", event.path("at").asLong());
                    record.set("loss_exit_observed_at", QuestionStub.JSON.valueToTree(observation.exitAt()));
                    record.set("loss_exit_observed_after_ms",
                            QuestionStub.JSON.valueToTree(observation.exitAfterDeliveryMs()));
                }
            }
            return true;
        }

        /** Reads what the stub made of the lost task and records the step. */
        private QuestionDriver.Recovery recover(Worker lost, String decision, String task) throws IOException {
            var recovery = driver.recovery(lost, decision, task);
            if (!record.has("recovery_step")) {
                record.put("recovery_step", recovery.step().name().toLowerCase(Locale.ROOT));
                record.put("recovery_task", recovery.task());
                record.put("recovery_holder", recovery.holder());
                if (recovery.holderEnded()) {
                    record.put("recovery_holder_ended", true);
                }
            }
            if (recovery.step() == RecoveryStep.GAP && !record.has(QuestionDriver.CONTINUATION_GAP)) {
                record.put(QuestionDriver.CONTINUATION_GAP, QuestionDriver.HELD_BY_ENDED_GENERATION);
            }
            return recovery;
        }

        /**
         * Continues a decision in the fallback form: a warm asker on its own host is expected to take the stub's
         * continuation over its {@code pm_wait} ({@link QuestionDriver#awaitWarmContinuation}); otherwise a fresh
         * generation is started on the continuation.
         */
        private Worker fallback(Worker current, String decision) throws IOException, InterruptedException {
            if (plan.warm() && plan.continueHost() == null) {
                var warm = driver.awaitWarmContinuation(current, decision, plan.host().silenceLimit());
                var step = warm.step().name().toLowerCase(Locale.ROOT);
                return switch (warm.step()) {
                    case TAKEN, PENDING -> current;
                    case ASKER_EXITED -> {
                        var next = continuation(decision, current);
                        record.put(QuestionDriver.WARM_ASKER_EXITED, true);
                        record.put(QuestionDriver.WARM_CONTINUATION, step);
                        record.put(QuestionDriver.WARM_CONTINUATION + "_generation", next.generation());
                        yield next;
                    }
                    case NOT_TAKEN, NOT_CONTINUED -> {
                        record.put(QuestionDriver.WARM_CONTINUATION, step);
                        record.put(QuestionDriver.CONTINUATION_GAP, step);
                        yield current;
                    }
                };
            }
            if (plan.warm()) {
                driver.stub().end(current.id(), current.generation());
            }
            return continuation(decision, current);
        }

        /**
         * Starts a fresh generation of the asking worker on the task that continues the decision, unless the asking
         * worker already took that task over its held {@code pm_wait} and closed it
         * ({@link QuestionDriver#continuedByHold(String)}).
         */
        private Worker continuation(String decision, Worker current) throws IOException, InterruptedException {
            var continued = driver.awaitEvent(event -> "continued".equals(event.path(EVENT).asText())
                    && decision.equals(event.path(DECISION).asText()), System.currentTimeMillis()
                    + ANSWER_WAIT.toMillis());
            if (continued.isEmpty()) {
                record.put(QuestionDriver.CONTINUATION_GAP, "not_continued");
                return current;
            }
            var held = driver.continuedByHold(decision);
            if (held.isPresent()) {
                recordContinuedByHold(held.get());
                return current;
            }
            var host = plan.continueHost() == null ? plan.host() : plan.continueHost();
            var target = host == plan.host() ? driver : sibling(host, config.model(host));
            return target.startLeased(continued.get().path("task").asText(),
                    continued.get().path(ENTITY).asText(), launch(current.id(), ++generation, host, null));
        }

        private void recordContinuedByHold(QuestionDriver.HeldContinuation held) {
            var field = QuestionDriver.CONTINUED_BY_HOLD;
            if (record.has(field)) {
                return;
            }
            record.put(field, true);
            record.put(field + "_decision", held.decision());
            record.put(field + "_task", held.task());
            record.put(field + "_worker", held.worker());
            record.put(field + "_generation", held.generation());
            record.put(field + "_outcome", held.outcome());
            record.put(field + "_closed_at", held.closedAt());
            record.put(field + "_submitted_at", held.submittedAt());
            record.put(field + "_channel", held.channel());
        }

        /**
         * Resumes a missing or over-age session and waits for its end up to the run timeout, and records the exit, the
         * session id the generation reports, and whether it started a new session
         * ({@link RunReadings#startedNewSession}).
         */
        private void negativeResume(Worker current) throws IOException, InterruptedException {
            nextAsked(System.currentTimeMillis() + config.runTimeout().toMillis());
            if (ended) {
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
            var warned = RunReadings.warnsMissingSession(driver.stderr(resumed), plan.negative());
            record.put("negative_not_found_warning", warned);
            record.put(RunReadings.NEGATIVE_NEW_SESSION, RunReadings.startedNewSession(plan.host(), plan.negative(),
                    reported.orElse(""), warned));
        }

        private Optional<JsonNode> nextAsked(long deadline) throws IOException, InterruptedException {
            var asked = driver.awaitEventWhileLaunched(event -> QuestionDriver.ASKED.equals(event.path(EVENT).asText())
                    && !seenDecisions.contains(event.path(DECISION).asText()) || entryDone(), deadline);
            if (asked.isEmpty() && ended()) {
                return Optional.empty();
            }
            var event = asked.filter(found -> QuestionDriver.ASKED.equals(found.path(EVENT).asText()));
            event.ifPresent(found -> seenDecisions.add(found.path(DECISION).asText()));
            return event;
        }

        /**
         * Records the run as ended at once: a quota error of a worker's harness first
         * ({@value QuestionDriver#QUOTA_ERROR}), else a launch failure ({@value QuestionDriver#LAUNCH_FAILED}), else an
         * early exit ({@value QuestionDriver#EARLY_EXIT}, or {@value QuestionDriver#CONTINUATION_EARLY_EXIT} for a
         * continuation generation), each with its worker and detail.
         *
         * @return whether the run ended so
         */
        private boolean ended() throws IOException, InterruptedException {
            if (!ended) {
                driver.quotaError().ifPresent(value -> {
                    ended = true;
                    recordQuotaError(record, value);
                });
            }
            if (!ended) {
                driver.launchFailure().ifPresent(value -> {
                    ended = true;
                    record.put(QuestionDriver.LAUNCH_FAILED, true);
                    record.put("launch_worker", value.worker());
                    record.put("launch_generation", value.generation());
                    record.put("launch_exit", value.exit());
                    record.put("launch_stderr", value.stderr());
                    var tail = record.putArray("launch_stderr_tail");
                    value.tail().forEach(tail::add);
                    record.put("error", "launch_failed: exit " + value.exit() + ": " + value.stderr());
                });
            }
            if (!ended) {
                driver.earlyExit().ifPresent(value -> {
                    ended = true;
                    var field = value.continuation() ? QuestionDriver.CONTINUATION_EARLY_EXIT
                            : QuestionDriver.EARLY_EXIT;
                    record.put(field, true);
                    record.put(field + "_worker", value.worker());
                    record.put(field + "_generation", value.generation());
                    record.put(field + "_code", value.exit());
                    record.put(field + "_detail", value.detail());
                });
            }
            return ended;
        }

        /**
         * Waits until the asker stopped after its ask: a fresh worker exits, a warm worker calls {@code pm_wait} or
         * exits ({@link QuestionDriver#awaitWarmStop}); in Q7 the fresh asker is ended by the driver at its silence
         * limit if it still runs ({@link QuestionDriver#endAsker}).
         */
        private void awaitStop(Worker asker, JsonNode asked) throws IOException, InterruptedException {
            var at = asked.path("at").asLong();
            if (plan.warm()) {
                if (driver.awaitWarmStop(asker, at, plan.host().silenceLimit()) == QuestionDriver.WarmStop.EXITED) {
                    record.put(QuestionDriver.WARM_ASKER_EXITED, true);
                    record.put(QuestionDriver.WARM_ASKER_EXITED + "_after_ask_ms", System.currentTimeMillis() - at);
                }
            } else if (endsAskerBeforeOpenTime(plan)) {
                var end = driver.endAsker(asker, at, plan.host().silenceLimit());
                var field = QuestionDriver.ASKER_ENDED_BY_DRIVER;
                record.put(field, end.endedByDriver());
                record.put(field + "_after_ask_ms", end.afterAskMs());
                record.put(field + "_holding_wait", end.holdingWait());
            } else {
                driver.awaitExit(asker, plan.host().silenceLimit());
            }
        }

        private void awaitEntry(long deadline) throws IOException, InterruptedException {
            while (!entryDone() && System.currentTimeMillis() < deadline && !ended()) {
                Thread.sleep(POLL); // NOSONAR java:S2925 - polls the stub state, no event to await
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
            if (VOTE.equals(plan.kind())) {
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

        /** Starts a fresh worker on every vote task, on the harness and model of its role, once. */
        private void serveVotes() {
            var started = new HashSet<String>();
            try {
                while (running.get()) {
                    var stub = driver.stub();
                    for (var id : stub.taskIds()) {
                        if (QuestionDriver.voter(id) && "task.queued".equals(stub.state(id).orElse(""))
                                && started.add(id)) {
                            var role = id.substring(id.lastIndexOf("quorum-"));
                            var assignment = q6Assignment(role).split(":", 2);
                            var voter = sibling(Host.of(assignment[0]), assignment.length > 1 ? assignment[1] : null);
                            voter.startLeased(id, id, voterLaunch(id, role));
                        }
                    }
                    Thread.sleep(POLL); // NOSONAR java:S2925 - polls the stub state, no event to await
                }
            } catch (IOException e) {
                record.put("vote_error", String.valueOf(e.getMessage()));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Records the voters that connected and exited without a call ({@value QuestionDriver#VOTER_EARLY_EXIT}); the
         * run is not ended by them.
         */
        private void recordVoterEarlyExits() throws IOException {
            var exits = driver.voterEarlyExits();
            if (exits.isEmpty()) {
                return;
            }
            var first = exits.getFirst();
            var field = QuestionDriver.VOTER_EARLY_EXIT;
            record.put(field, true);
            record.put(field + "_worker", first.worker());
            record.put(field + "_generation", first.generation());
            record.put(field + "_code", first.exit());
            record.put(field + "_detail", first.detail());
            var all = record.putArray(field + "s");
            for (var exit : exits) {
                var node = all.addObject();
                node.put("worker", exit.worker());
                node.put("generation", exit.generation());
                node.put("code", exit.exit());
                node.put("detail", exit.detail());
            }
        }

        /**
         * Records the client version of the asker's harness ({@value QuestionResults#CLIENT_VERSION},
         * {@link QuestionDriver#clientVersion}); a run that started no worker records none.
         */
        private void recordClientVersion() throws IOException {
            var workers = driver.workers();
            if (!workers.isEmpty()) {
                record.put(QuestionResults.CLIENT_VERSION, driver.clientVersion(workers.getFirst()));
            }
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
                    for (var event : QuestionDriver.jsonLinesSoFar(driver.stub().events())) {
                        var consultation = event.path("consultation").asText();
                        if ("consulted".equals(event.path(EVENT).asText()) && answered.add(consultation)) {
                            driver.stub().answerConsultation(consultation, "Where the description and the examples "
                                    + "disagree, the consultation cannot settle which is meant: that is the "
                                    + "operator's decision.");
                        }
                    }
                    Thread.sleep(POLL); // NOSONAR java:S2925 - polls the stub log, no event to await
                }
            } catch (IOException e) {
                record.put("consult_error", String.valueOf(e.getMessage()));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        /** Q7: a warm worker of the role waits beside the open record and is recycled after its idle wake-ups. */
        private void idleWarmWorker() {
            try {
                var warm = driver.startWarm(launch("w-warm", 1, plan.host(), null));
                record.put("warm_worker", warm.id());
                while (running.get() && warm.process().isAlive()) {
                    var idle = QuestionDriver.jsonLinesSoFar(driver.stub().events()).stream()
                            .filter(event -> "wait_again".equals(event.path(EVENT).asText())
                                    && warm.id().equals(event.path("worker").asText()))
                            .count();
                    if (idle >= 3) {
                        driver.stub().end(warm.id(), warm.generation());
                        return;
                    }
                    Thread.sleep(POLL); // NOSONAR java:S2925 - polls the stub log, no event to await
                }
            } catch (IOException e) {
                record.put("warm_error", String.valueOf(e.getMessage()));
            } catch (InterruptedException _) {
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
                node.put("stderr", QuestionDriver.stderrFile(worker.output().getParent(), worker.id(),
                        worker.generation()).toString());
                node.put("relay_log", worker.relayLog().toString());
            }
            return array;
        }
    }
}
