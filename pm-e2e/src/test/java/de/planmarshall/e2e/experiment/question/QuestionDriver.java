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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.experiment.question.QuestionStub.PartAnswer;
import de.planmarshall.e2e.experiment.question.QuestionStub.TaskSpec;

/**
 * The driver of the measurement (experiment only): starts workers headless through {@code pm-exec} in worker
 * directories outside the repository, warm and fresh, on Claude Code, OpenCode and Antigravity; answers each
 * decision on the operator's behalf after the hold the item measures; resumes a stored session by the harness's resume
 * command on the session id read from the worker's structured result; restarts the stub; injects the faults of R3;
 * and supervises the answer acknowledgement as the server's supervisor will (PM-WORK-2, PM-WORK-3).
 * <p>
 * Every action it takes is appended to the stub's event log, beside the stub's own events; the relay writes its
 * host-side log and the driver the worker's arrival-stamped output into the run directory.
 */
final class QuestionDriver implements AutoCloseable {

    /** Environment variables of the driver's own host session, never passed to a worker. */
    static final List<String> HOST_SESSION_PREFIXES = List.of("CLAUDECODE", "CLAUDE_PID", "CLAUDE_EFFORT",
            "CLAUDE_CODE_SSE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_MESSAGING", "CLAUDE_CODE_BRIDGE",
            "CLAUDE_CODE_EXECPATH", "CLAUDE_CODE_SESSION", "CLAUDE_CODE_CHILD", "OPENCODE_SESSION", "CODEX_SANDBOX");

    /**
     * The fields of a harness's structured result that carry its session id: {@code session_id} (Claude Code),
     * {@code sessionID} (OpenCode), {@code conversation_id} (Antigravity, verified 2026-10-21).
     */
    static final List<String> SESSION_FIELDS = List.of("session_id", "sessionID", "conversation_id");

    /**
     * The verified headless launch of Antigravity ({@code agy}, verified 2026-10-21 against the stub): the
     * placeholders {@code {prompt}}, {@code {model}} and {@code {session_id}} are replaced, and a placeholder without
     * a value is dropped together with the flag before it. {@code -Dpm.experiment.agy.argv} (a JSON array) overrides
     * it.
     */
    static final List<String> AGY_ARGV = List.of("agy", "--print", "{prompt}", "--output-format", "json",
            "--dangerously-skip-permissions", "--model", "{model}", "--conversation", "{session_id}");

    /**
     * Where Antigravity reads the stub's MCP server, below the worker directory: a workspace plugin
     * ({@code .agents/plugins/<name>/} with {@code plugin.json} and {@code mcp_config.json}), verified 2026-10-21; its
     * name differs from the operator's globally installed {@code plan-marshall} plugin. {@code -Dpm.experiment.agy.config}
     * overrides it.
     */
    static final String AGY_CONFIG = ".agents/plugins/pm-question-stub/mcp_config.json";

    /** The agent of {@code opencode.json} that the worker runs as: the stub's tools and the work tools only. */
    static final String OPENCODE_AGENT = "pull-worker";

    /**
     * The tool through which OpenCode 2.1.2 offers MCP tools to the model: it runs code that calls
     * {@code tools['plan-marshall'].pm_wait()} (verified 2026-10-21). The agent permits it together with
     * {@code plan-marshall*}; permitting {@code plan-marshall.pm_wait} and its siblings by name did not expose the
     * stub's tools to the code.
     */
    static final String OPENCODE_CODE_TOOL = "execute";

    /**
     * The headless launch of OpenCode 2.1.2, verified 2026-10-21 against the stub: the placeholders {@code {prompt}},
     * {@code {model}} and {@code {session_id}} are replaced, and a placeholder without a value is dropped together with
     * the flag before it. {@code --standalone} runs a private server ({@code opencode serve --stdio}) that honours the
     * worker's {@code XDG_DATA_HOME} ({@link OpenCodeDataHome}); without it the run goes to the operator's background
     * service, which keeps the session in its own data directory. The worker directory is the process working directory and {@code PWD}: OpenCode
     * takes the project directory from {@code PWD}. No {@code --auto}: the agent's permissions allow what the worker
     * needs. {@code -Dpm.experiment.opencode.argv} (a JSON array) overrides it.
     */
    static final List<String> OPENCODE_ARGV = List.of("opencode", "run", "--standalone", "--format", "json",
            "--agent", OPENCODE_AGENT, "--model", "{model}", "--session", "{session_id}", "{prompt}");

    /**
     * The prefix of the property that names a host's executable by its path ({@code pm.experiment.exe.claude},
     * {@code pm.experiment.exe.opencode}); {@code pm-exec} runs a program by its absolute path only.
     */
    static final String EXECUTABLE_PROPERTY = "pm.experiment.exe.";

    /** The event the stub logs when a generation's relay connects: the first event the generation causes itself. */
    static final String CONNECTED = "connected";
    /** The event the stub logs for every tool call of a generation. */
    static final String CALL = "call";
    /** The event the stub logs for every ask it records. */
    static final String ASKED = "asked";
    /** The event the driver logs when it injects a fault into a generation. */
    static final String FAULT_INJECTED = "fault_injected";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(10);
    private static final Duration POLL = Duration.ofMillis(100);
    private static final boolean LINUX = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
    private static final String WORKER = "worker";
    private static final String GENERATION = "generation";
    private static final String EVENT = "event";

    /**
     * The hosts of the measurement with the per-host values of their client profiles (PM-CLIENT-2).
     */
    enum Host {
        /** Claude Code. */
        CLAUDE("claude", "AskUserQuestion", 15, false, 240, 30,
            List.of(".claude", ".claude.json", ".cache/claude", ".local/state/claude", ".local/share/claude",
                    ".config/claude")),
        /**
         * OpenCode, on the data home {@link OpenCodeDataHome} selects. A {@code pm_wait} held by the stub for 150 s
         * returned uncut, and
         * one held for 330 s was still uncut after about 260 s, when the probe itself was ended (verified 2026-10-21);
         * what OpenCode does with a call held beyond that is not verified.
         */
        OPENCODE("opencode", "question", 270, true, 240, 30,
                List.of(".local/state/opencode", ".cache/opencode", ".config/opencode")),
        /**
         * Antigravity, a smoke run: it is not worker-eligible, and it runs with all host tools. A {@code pm_wait}
         * held by the stub for the bounded wait of 150 s returned uncut (verified 2026-10-21).
         */
        ANTIGRAVITY("agy", "ask_question", 0, false, 150, 30, List.of(".gemini", ".antigravity"));

        private final String executable;
        private final String questionTool;
        private final int ackDeadlineSeconds;
        private final boolean ackInformational;
        private final int boundedWaitSeconds;
        private final int silenceGraceSeconds;
        private final List<String> stateDirs;

        Host(String executable, String questionTool, int ackDeadlineSeconds, boolean ackInformational,
                int boundedWaitSeconds, int silenceGraceSeconds, List<String> stateDirs) {
            this.executable = executable;
            this.questionTool = questionTool;
            this.ackDeadlineSeconds = ackDeadlineSeconds;
            this.ackInformational = ackInformational;
            this.boundedWaitSeconds = boundedWaitSeconds;
            this.silenceGraceSeconds = silenceGraceSeconds;
            this.stateDirs = stateDirs;
        }

        /** @return the executable name */
        String executable() {
            return executable;
        }

        /** @return the name of the host's own question tool */
        String questionTool() {
            return questionTool;
        }

        /**
         * @return the deadline of the answer acknowledgement: the profile's {@code ack_deadline_seconds}, for a host
         *         without a measured value its silence limit
         */
        Duration ackDeadline() {
            return ackDeadlineSeconds > 0 ? Duration.ofSeconds(ackDeadlineSeconds) : silenceLimit();
        }

        /** @return whether the acknowledgement is informational, silence alone deciding */
        boolean ackInformational() {
            return ackInformational;
        }

        /** @return the bounded wait */
        Duration boundedWait() {
            return Duration.ofSeconds(boundedWaitSeconds);
        }

        /** @return the silence limit: bounded wait plus silence grace */
        Duration silenceLimit() {
            return Duration.ofSeconds((long) boundedWaitSeconds + silenceGraceSeconds);
        }

        /**
         * @param name a host name ({@code claude}, {@code opencode}, {@code antigravity} or {@code agy})
         * @return the host
         */
        static Host of(String name) {
            var key = name.strip().toLowerCase(Locale.ROOT);
            for (var host : values()) {
                if (host.executable.equals(key) || host.name().toLowerCase(Locale.ROOT).equals(key)) {
                    return host;
                }
            }
            throw new IllegalArgumentException("unknown host " + name);
        }
    }

    /** A fault of R3. */
    enum Fault {
        /** The worker's process stopped ({@code SIGSTOP}) before the answer. */
        STOP_BEFORE_ANSWER,
        /** The held call cut by the host (a host tool timeout below the hold). */
        CALL_CUT,
        /** The model request left hanging after the answer (the process stopped once the answer is delivered). */
        MODEL_HANG,
        /** The process killed while it holds. */
        KILL,
        /** A resumed process that exits before its first call. */
        RESUMED_EXIT
    }

    /**
     * Where OpenCode keeps its data ({@code XDG_DATA_HOME}), set by {@value #PROPERTY}: {@code global} (the default)
     * or {@code worker}.
     */
    enum OpenCodeDataHome {
        /**
         * The operator's own data home, which the worker inherits: it has the operator's login, so the models of the
         * login are routable ({@code opencode/gpt-5.4-nano} ran there, verified 2026-10-21), and every OpenCode
         * worker writes the one SQLite database of it; the sessions of the runs stay in the operator's history. A
         * departure from the per-worker data home of the work package, accepted by the operator.
         */
        GLOBAL,
        /**
         * A data home per worker below its directory ({@link #opencodeDataHome(Path)}), as the work package
         * specifies: the login does not carry, and only the free models of the provider {@code opencode} are routable.
         */
        WORKER;

        /** The system property that selects it. */
        static final String PROPERTY = "pm.experiment.opencode.data-home";

        /**
         * @param value {@code global} or {@code worker}, in any case; {@code null} for the default
         * @return the data home
         * @throws IllegalArgumentException for any other value
         */
        static OpenCodeDataHome of(String value) {
            if (value == null) {
                return GLOBAL;
            }
            for (var home : values()) {
                if (home.name().equalsIgnoreCase(value.strip())) {
                    return home;
                }
            }
            throw new IllegalArgumentException("unknown -D" + PROPERTY + "=" + value + ": global or worker");
        }
    }

    /**
     * The driver's settings.
     *
     * @param host             the host
     * @param model            the model, {@code null} for the harness default
     * @param workspace        the parent of the worker directories, outside every repository
     * @param runDir           where the worker output and the relay logs go
     * @param exec             the {@code pm-exec} command line
     * @param openCodeDataHome where an OpenCode worker keeps its data
     */
    record Settings(Host host, String model, Path workspace, Path runDir, List<String> exec,
    OpenCodeDataHome openCodeDataHome) {

        /**
         * Settings with OpenCode on its default data home ({@link OpenCodeDataHome#GLOBAL}).
         *
         * @param host      the host
         * @param model     the model, {@code null} for the harness default
         * @param workspace the parent of the worker directories
         * @param runDir    where the worker output and the relay logs go
         * @param exec      the {@code pm-exec} command line
         */
        Settings(Host host, String model, Path workspace, Path runDir, List<String> exec) {
            this(host, model, workspace, runDir, exec, OpenCodeDataHome.GLOBAL);
        }
    }

    /**
     * One worker launch.
     *
     * @param worker      the worker id
     * @param generation  its generation
     * @param role        its role
     * @param prompt      the launch prompt
     * @param resumable   whether its session is kept (the launch omits the no-persistence flag)
     * @param toolTimeout the host's MCP tool timeout to configure, {@code null} for the host default
     * @param workTools   the host tools beside the stub's (file tools for real work), by the host's names
     * @param sessionId   the session to resume, {@code null} for a new session
     */
    record Launch(String worker, int generation, String role, String prompt, boolean resumable, Duration toolTimeout,
    List<String> workTools, String sessionId) {
    }

    /**
     * A started worker generation.
     *
     * @param launch    its launch
     * @param host      its host
     * @param process   the process ({@code pm-exec}, which runs the harness in its place)
     * @param directory its worker directory, shared by the generations of the worker
     * @param output    its arrival-stamped output
     * @param relayLog  its relay's host-side log
     */
    record Worker(Launch launch, Host host, Process process, Path directory, Path output, Path relayLog) {

        String id() {
            return launch.worker();
        }

        int generation() {
            return launch.generation();
        }
    }

    /**
     * A worker generation that failed to launch: its process exited, or {@code pm-exec} reported an error, before the
     * stub saw its relay connect.
     *
     * @param worker     the worker id
     * @param generation its generation
     * @param exit       the exit code of its process
     * @param stderr     the first line of {@code stderr} that is not a line of {@code pm-exec}'s own
     *                   ({@code {"pm_exec":...}}), the harness's cause; without one {@code pm-exec}'s error line, else
     *                   its first line, empty when none
     * @param tail       the last {@value #STDERR_TAIL} lines of {@code stderr} that are not lines of {@code pm-exec}'s
     *                   own
     */
    record LaunchFailure(String worker, int generation, int exit, String stderr, List<String> tail) {
    }

    /** How many of the harness's last {@code stderr} lines a launch failure records. */
    static final int STDERR_TAIL = 5;

    /**
     * A worker generation that ended early: its relay connected, but its process exited without a single call to the
     * stub (OpenCode with {@code opencode/gpt-5.4-nano} said the plan-marshall tools were unavailable and exited within
     * seconds in 15 of 24 runs of the Q1 smoke pass).
     *
     * @param worker     the worker id
     * @param generation its generation
     * @param exit       the exit code of its process
     * @param detail     the first line of {@code stderr} that is not a line of {@code pm-exec}'s own, else the last
     *                   line of its output ({@link #earlyExitDetail(String, List)}), empty when none
     */
    record EarlyExit(String worker, int generation, int exit, String detail) {
    }

    /**
     * A worker generation whose harness answered with a quota or limit error ({@link QuestionResults#QUOTA_PATTERN}
     * in its own output or {@code stderr}): Antigravity said {@code Individual quota reached} in the last three
     * Antigravity runs of the Q3 smoke pass.
     *
     * @param worker     the worker id
     * @param generation its generation
     * @param detail     the matching line ({@link QuestionResults#quotaDetail(String, List)})
     */
    record QuotaError(String worker, int generation, String detail) {
    }

    private final QuestionStub.Settings stubSettings;
    private final Settings settings;
    private final boolean ownsStub;
    private final List<Worker> workers = new CopyOnWriteArrayList<>();
    private final List<QuestionDriver> siblings = new CopyOnWriteArrayList<>();
    private final Map<String, LaunchFailure> launchFailures = new ConcurrentHashMap<>();
    private final Map<String, EarlyExit> earlyExits = new ConcurrentHashMap<>();
    private final Map<String, QuotaError> quotaErrors = new ConcurrentHashMap<>();
    private volatile QuestionStub stub;

    private QuestionDriver(QuestionStub.Settings stubSettings, Settings settings, QuestionStub stub,
            boolean ownsStub) {
        this.stubSettings = stubSettings;
        this.settings = settings;
        this.stub = stub;
        this.ownsStub = ownsStub;
    }

    /**
     * A driver of another host on the same stub, for a continuation or a vote on another harness; closing it ends
     * its workers and leaves the stub running. Its workers count in {@link #launchFailure()} of this driver.
     *
     * @param host  the host
     * @param model its model, {@code null} for the harness default
     * @return the sibling driver
     */
    QuestionDriver sibling(Host host, String model) {
        var sibling = new QuestionDriver(stubSettings, new Settings(host, model, settings.workspace(),
                settings.runDir(), settings.exec(), settings.openCodeDataHome()), stub, false);
        siblings.add(sibling);
        return sibling;
    }

    /**
     * Leases a queued task (a continuation, a vote) to a new generation and starts it on that task.
     *
     * @param taskId the task
     * @param entity its entity
     * @param launch the launch (its prompt is the contract; the start instruction is appended)
     * @return the worker
     * @throws IOException if it cannot be started
     */
    Worker startLeased(String taskId, String entity, Launch launch) throws IOException {
        stub.leaseAtSpawn(taskId, launch.worker(), launch.generation());
        return launch(withPrompt(launch, launch.prompt() + "\n\nStart: call pm_state with entity \"" + entity
                + "\" and follow its link ack."));
    }

    /** @return every worker this driver started */
    List<Worker> workers() {
        return List.copyOf(workers);
    }

    /**
     * Starts the stub and returns its driver.
     *
     * @param stubSettings the stub's settings
     * @param settings     the driver's settings
     * @return the driver
     * @throws IOException if the stub cannot start or a directory not be created
     */
    static QuestionDriver start(QuestionStub.Settings stubSettings, Settings settings) throws IOException {
        Files.createDirectories(settings.workspace());
        Files.createDirectories(settings.runDir());
        return new QuestionDriver(stubSettings, settings, QuestionStub.start(stubSettings), true);
    }

    /** @return the running stub */
    QuestionStub stub() {
        return stub;
    }

    /** @return the driver's settings */
    Settings settings() {
        return settings;
    }

    /**
     * Stops the stub and starts it again on the same directory: open records are replayed.
     *
     * @return the new stub
     * @throws IOException if it cannot be restarted
     */
    QuestionStub restartStub() throws IOException {
        stub.event("stub_restart", Map.of());
        stub.close();
        stub = QuestionStub.start(stubSettings);
        return stub;
    }

    /**
     * Queues a task, leases it to a fresh generation, and starts that generation on it.
     *
     * @param spec   the task
     * @param launch the launch (its prompt is the contract; the start instruction is appended)
     * @return the worker
     * @throws IOException if it cannot be started
     */
    Worker startFresh(TaskSpec spec, Launch launch) throws IOException {
        stub.queue(spec);
        stub.leaseAtSpawn(spec.taskId(), launch.worker(), launch.generation());
        return launch(withPrompt(launch, launch.prompt() + "\n\nStart: call pm_state with entity \"" + spec.entity()
                + "\" and follow its link ack."));
    }

    /**
     * Starts a warm generation that takes task after task from {@code pm_wait}.
     *
     * @param launch the launch (its prompt is the contract; the start instruction is appended)
     * @return the worker
     * @throws IOException if it cannot be started
     */
    Worker startWarm(Launch launch) throws IOException {
        return launch(withPrompt(launch, launch.prompt() + "\n\nStart: call pm_wait. Take task after task; end "
                + "your turn only when pm_wait returns 'wait: end'."));
    }

    /**
     * Resumes a stored session as a new generation, leased the task that waits for it.
     *
     * @param previous   the generation that asked and ended
     * @param taskId     the suspended task
     * @param sessionId  the session id read from its structured result
     * @param generation the new generation
     * @return the resumed worker
     * @throws IOException if it cannot be started
     */
    Worker resume(Worker previous, String taskId, String sessionId, int generation) throws IOException {
        stub.leaseAtSpawn(taskId, previous.id(), generation);
        var entity = previous.launch();
        var launch = new Launch(entity.worker(), generation, entity.role(), "The answer to your question is there. "
                + "Call pm_state, follow its link ack, and continue the task with the answer.", true,
                entity.toolTimeout(), entity.workTools(), sessionId);
        return launch(launch);
    }

    /**
     * Resumes a session that does not exist or is older than its limit. The specification expects the resume to fail
     * at once; Antigravity instead warns on {@code stderr} ({@link #stderr(Worker)}), starts a new conversation with a
     * new id and runs it to its end, so the caller reads the session id the generation reports
     * ({@link #sessionId(Worker)}) as well as its exit.
     *
     * @param previous   the generation whose session is named
     * @param sessionId  the session id to try
     * @param generation the new generation
     * @return the started worker
     * @throws IOException if it cannot be started
     */
    Worker resumeWithoutTask(Worker previous, String sessionId, int generation) throws IOException {
        var entity = previous.launch();
        return launch(new Launch(entity.worker(), generation, entity.role(), "Continue.", true, entity.toolTimeout(),
                entity.workTools(), sessionId));
    }

    /**
     * @param worker a worker
     * @return the last session id its structured result names ({@code session_id} on Claude Code, {@code sessionID}
     *         on OpenCode, {@code conversation_id} on Antigravity)
     * @throws IOException if the output cannot be read
     */
    Optional<String> sessionId(Worker worker) throws IOException {
        String found = null;
        for (var line : outputLines(worker.output())) {
            for (var field : SESSION_FIELDS) {
                var value = line.findValue(field);
                if (value != null && value.isTextual()) {
                    found = value.asText();
                    break;
                }
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * @param worker a worker
     * @return what it wrote to {@code stderr} so far, empty when nothing
     * @throws IOException if the file cannot be read
     */
    String stderr(Worker worker) throws IOException {
        var file = stderrFile(settings.runDir(), worker.id(), worker.generation());
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    /**
     * @param runDir     the run directory of a worker's files
     * @param worker     the worker id
     * @param generation its generation
     * @return the file its {@code stderr} goes to
     */
    static Path stderrFile(Path runDir, String worker, int generation) {
        return runDir.resolve("stderr-" + worker + "-g" + generation + ".log");
    }

    /**
     * Waits for a worker to exit.
     *
     * @param worker  the worker
     * @param timeout the maximum wait
     * @return the exit code, or empty if it still runs
     * @throws InterruptedException if interrupted
     */
    Optional<Integer> awaitExit(Worker worker, Duration timeout) throws InterruptedException {
        if (worker.process().waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return Optional.of(worker.process().exitValue());
        }
        return Optional.empty();
    }

    /**
     * Ends a worker as the server ends one: {@code SIGTERM}, then {@code SIGKILL} after the termination grace.
     *
     * @param worker the worker
     * @throws InterruptedException if interrupted
     */
    void terminate(Worker worker) throws InterruptedException {
        if (!worker.process().isAlive()) {
            return;
        }
        signal(worker, "CONT");
        worker.process().destroy();
        if (!worker.process().waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
            worker.process().descendants().forEach(ProcessHandle::destroyForcibly);
            worker.process().destroyForcibly();
        }
    }

    /**
     * Injects a fault into a worker; {@link Fault#CALL_CUT} is a launch setting (a host tool timeout) and is only
     * recorded here.
     *
     * @param worker the worker
     * @param fault  the fault
     */
    void inject(Worker worker, Fault fault) {
        var fields = identity(worker);
        fields.put("fault", fault.name());
        stub.event(FAULT_INJECTED, fields);
        switch (fault) {
            case STOP_BEFORE_ANSWER, MODEL_HANG -> signal(worker, "STOP");
            case KILL, RESUMED_EXIT -> {
                worker.process().descendants().forEach(ProcessHandle::destroyForcibly);
                worker.process().destroyForcibly();
            }
            case CALL_CUT -> {
                // set at launch through the tool timeout
            }
        }
    }

    /**
     * Takes the next decision delivered to the operator, holds it, and answers it.
     *
     * @param timeout how long to wait for a decision
     * @param hold    how long the operator takes to answer
     * @param reply   the answer per part, from the decision's snapshot
     * @return the decision answered, or empty when none arrived in time
     * @throws InterruptedException if interrupted
     */
    Optional<String> answerNext(Duration timeout, Duration hold, Function<ObjectNode, List<PartAnswer>> reply)
            throws InterruptedException {
        var id = stub.operatorDecisions().poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (id == null) {
            return Optional.empty();
        }
        stub.event("decision_seen", Map.of("decision", id, "hold_ms", hold.toMillis()));
        Thread.sleep(hold);
        var decision = stub.decision(id).orElseThrow();
        var result = stub.answer(id, decision.path("revision").asInt(), reply.apply(decision));
        stub.event("answer_sent", Map.of("decision", id, "result", result.name()));
        return Optional.of(id);
    }

    /**
     * Supervises the acknowledgement of an answer delivered to a worker, as the supervisor does: a missing
     * acknowledgement within the host's deadline (by silence where the acknowledgement is informational) ends the
     * worker as lost, and the stub releases its task.
     *
     * @param worker         the worker
     * @param deliveryWithin how long to wait for the delivery of the answer
     * @return {@code true} if the worker acknowledged in time
     * @throws IOException          if the event log cannot be read
     * @throws InterruptedException if interrupted
     */
    boolean superviseAnswer(Worker worker, Duration deliveryWithin) throws IOException, InterruptedException {
        var delivered = awaitEvent(event -> "answer_delivered".equals(event.path(EVENT).asText())
                && matches(event, worker), System.currentTimeMillis() + deliveryWithin.toMillis());
        if (delivered.isEmpty()) {
            return false;
        }
        var deliveredAt = delivered.get().path("at").asLong();
        var host = worker.host();
        var acknowledged = awaitEvent(event -> "acknowledged".equals(event.path(EVENT).asText())
                        && "answer".equals(event.path("kind").asText()) && matches(event, worker),
                deliveredAt + host.ackDeadline().toMillis());
        if (acknowledged.isPresent()) {
            return true;
        }
        var by = "answer_ack_missing";
        if (host.ackInformational()) {
            by = "silence";
            if (!silentFor(worker, host.silenceLimit())) {
                return true;
            }
        }
        stub.lost(worker.id(), worker.generation(), by);
        var fields = identity(worker);
        fields.put("by", by);
        fields.put("after_ms", System.currentTimeMillis() - deliveredAt);
        stub.event("lost_detected", fields);
        terminate(worker);
        return false;
    }

    /**
     * @param worker a worker
     * @return the SHA-256 over the paths and contents of its worker directory, without the harness configuration and
     *         data directory: a change after the ask is a change of the worktree
     * @throws IOException if a file cannot be read
     */
    String worktreeDigest(Worker worker) throws IOException {
        try (Stream<Path> walk = Files.walk(worker.directory())) {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var path : walk.filter(Files::isRegularFile).sorted().toList()) {
                var relative = worker.directory().relativize(path).toString();
                if (relative.startsWith("data") || relative.startsWith(".") || relative.endsWith("mcp-config.json")
                        || "opencode.json".equals(relative)) {
                    continue;
                }
                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(path));
            }
            var value = HexFormat.of().formatHex(digest.digest());
            var fields = identity(worker);
            fields.put("digest", value);
            stub.event("worktree_digest", fields);
            return value;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    /**
     * Waits for an event of the stub's log.
     *
     * @param match      the event
     * @param untilMillis the deadline (epoch milliseconds)
     * @return the first matching event
     * @throws IOException          if the log cannot be read
     * @throws InterruptedException if interrupted
     */
    Optional<JsonNode> awaitEvent(Predicate<JsonNode> match, long untilMillis)
            throws IOException, InterruptedException {
        while (true) {
            for (var event : QuestionResults.readJsonLines(stub.events())) {
                if (match.test(event)) {
                    return Optional.of(event);
                }
            }
            if (System.currentTimeMillis() >= untilMillis) {
                return Optional.empty();
            }
            Thread.sleep(POLL);
        }
    }

    /**
     * Waits for an event of the stub's log as {@link #awaitEvent(Predicate, long)} does, but ends the wait at once
     * when a worker of this driver or of a sibling failed to launch ({@link #launchFailure()}), ended early
     * ({@link #earlyExit()}) or met a quota error of its harness ({@link #quotaError()}), instead of at the deadline.
     *
     * @param match       the event
     * @param untilMillis the deadline (epoch milliseconds)
     * @return the first matching event, empty at the deadline, on a launch failure, an early exit or a quota error
     * @throws IOException          if the log cannot be read
     * @throws InterruptedException if interrupted
     */
    Optional<JsonNode> awaitEventWhileLaunched(Predicate<JsonNode> match, long untilMillis)
            throws IOException, InterruptedException {
        while (true) {
            var found = awaitEvent(match, 0);
            if (found.isPresent() || quotaError().isPresent() || launchFailure().isPresent()
                    || earlyExit().isPresent() || System.currentTimeMillis() >= untilMillis) {
                return found;
            }
            Thread.sleep(POLL);
        }
    }

    /**
     * @return the first worker of this driver or of its siblings whose harness met a quota error
     *         ({@link #quotaError(Worker)}), empty when none did
     * @throws IOException if a worker's output or {@code stderr} cannot be read
     */
    Optional<QuotaError> quotaError() throws IOException {
        for (var worker : workers) {
            var quota = quotaError(worker);
            if (quota.isPresent()) {
                return quota;
            }
        }
        for (var sibling : siblings) {
            var quota = sibling.quotaError();
            if (quota.isPresent()) {
                return quota;
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a worker generation's harness answered with a quota or limit error: a line of its own {@code stderr}
     * (not one of {@code pm-exec}'s) or of its output matches {@link QuestionResults#QUOTA_PATTERN}, whether or not it
     * still runs and whether or not it called the stub. The first detection is logged as the event
     * {@code quota_error}.
     *
     * @param worker a worker
     * @return its quota error, empty when none
     * @throws IOException if its output or {@code stderr} cannot be read
     */
    Optional<QuotaError> quotaError(Worker worker) throws IOException {
        var key = worker.id() + "#" + worker.generation();
        var known = quotaErrors.get(key);
        if (known != null) {
            return Optional.of(known);
        }
        var detail = QuestionResults.quotaDetail(stderr(worker), QuestionResults.readJsonLines(worker.output()));
        if (detail.isEmpty()) {
            return Optional.empty();
        }
        var quota = new QuotaError(worker.id(), worker.generation(), detail.get());
        if (quotaErrors.putIfAbsent(key, quota) == null) {
            var fields = identity(worker);
            fields.put("detail", quota.detail());
            stub.event(QuestionResults.QUOTA_ERROR, fields);
        }
        return Optional.of(quotaErrors.get(key));
    }

    /**
     * @return the first worker of this driver or of its siblings that ended early ({@link #earlyExit(Worker)}), empty
     *         when none did
     * @throws IOException if the event log or a worker's output cannot be read
     */
    Optional<EarlyExit> earlyExit() throws IOException {
        for (var worker : workers) {
            var early = earlyExit(worker);
            if (early.isPresent()) {
                return early;
            }
        }
        for (var sibling : siblings) {
            var early = sibling.earlyExit();
            if (early.isPresent()) {
                return early;
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a worker generation ended early: its process exited after its relay connected
     * ({@value #CONNECTED}) without a single call to the stub ({@value #CALL}), no fault was injected into it, no
     * newer generation of the same worker was started, and the worker did not ask in an earlier generation (its run
     * keeps the wait as before). A generation whose relay never connected failed to launch instead
     * ({@link #launchFailure(Worker)}); one that called the stub, an asker among them, did not end early. The first
     * detection is logged as the event {@code early_exit}.
     *
     * @param worker a worker
     * @return its early exit, empty when it still runs or did not end early
     * @throws IOException if the event log or the worker's output cannot be read
     */
    Optional<EarlyExit> earlyExit(Worker worker) throws IOException {
        var key = worker.id() + "#" + worker.generation();
        var known = earlyExits.get(key);
        if (known != null) {
            return Optional.of(known);
        }
        var superseded = workers.stream().anyMatch(other -> other.id().equals(worker.id())
                && other.generation() > worker.generation());
        if (worker.process().isAlive() || superseded) {
            return Optional.empty();
        }
        var own = new HashSet<String>();
        var askedBefore = false;
        for (var event : QuestionResults.readJsonLines(stub.events())) {
            if (matches(event, worker)) {
                own.add(event.path(EVENT).asText());
            }
            askedBefore |= ASKED.equals(event.path(EVENT).asText())
                    && worker.id().equals(event.path(WORKER).asText());
        }
        if (!own.contains(CONNECTED) || own.contains(CALL) || own.contains(FAULT_INJECTED) || askedBefore) {
            return Optional.empty();
        }
        var early = new EarlyExit(worker.id(), worker.generation(), worker.process().exitValue(),
                earlyExitDetail(stderr(worker), QuestionResults.readJsonLines(worker.output())));
        if (earlyExits.putIfAbsent(key, early) == null) {
            var fields = identity(worker);
            fields.put("exit", early.exit());
            fields.put("detail", early.detail());
            stub.event("early_exit", fields);
        }
        return Optional.of(earlyExits.get(key));
    }

    /**
     * @param stderr  what a worker wrote to {@code stderr}
     * @param outputs its arrival-stamped output lines
     * @return the first line of {@code stderr} that is not a line of {@code pm-exec}'s own, else the last line of its
     *         output, empty when it wrote neither
     */
    static String earlyExitDetail(String stderr, List<JsonNode> outputs) {
        return stderr.lines().filter(line -> !line.isBlank() && !pmExecLine(line)).findFirst()
                .orElseGet(() -> outputs.isEmpty() ? "" : outputs.getLast().path("line").asText());
    }

    /**
     * @return the first worker of this driver or of its siblings that failed to launch, empty when none did
     * @throws IOException          if the event log or a worker's output cannot be read
     * @throws InterruptedException if interrupted while a worker is ended
     */
    Optional<LaunchFailure> launchFailure() throws IOException, InterruptedException {
        for (var worker : workers) {
            var failure = launchFailure(worker);
            if (failure.isPresent()) {
                return failure;
            }
        }
        for (var sibling : siblings) {
            var failure = sibling.launchFailure();
            if (failure.isPresent()) {
                return failure;
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a worker failed to launch: its process exited, or {@code pm-exec} reported an error line, before the
     * stub saw its relay connect ({@value #CONNECTED}). A worker into which a fault was injected has not failed to
     * launch. A worker that still runs after {@code pm-exec} reported an error is ended. The first detection is logged
     * as the event {@code launch_failed}.
     *
     * @param worker a worker
     * @return its launch failure, empty when it launched or still may
     * @throws IOException          if the event log or the worker's output cannot be read
     * @throws InterruptedException if interrupted while the worker is ended
     */
    Optional<LaunchFailure> launchFailure(Worker worker) throws IOException, InterruptedException {
        var key = worker.id() + "#" + worker.generation();
        var known = launchFailures.get(key);
        if (known != null) {
            return Optional.of(known);
        }
        var alive = worker.process().isAlive();
        var stderr = stderr(worker);
        var reported = stderr.lines().anyMatch(QuestionDriver::pmExecError) || outputLines(worker.output()).stream()
                .anyMatch(line -> "error".equals(line.path("pm_exec").asText()));
        if (alive && !reported || sawEvent(worker, CONNECTED) || sawEvent(worker, FAULT_INJECTED)) {
            return Optional.empty();
        }
        if (alive) {
            terminate(worker);
        }
        var failure = launchFailure(worker.id(), worker.generation(), worker.process().waitFor(), stderr(worker));
        if (launchFailures.putIfAbsent(key, failure) == null) {
            var fields = identity(worker);
            fields.put("exit", failure.exit());
            fields.put("stderr", failure.stderr());
            fields.put("stderr_tail", failure.tail());
            stub.event("launch_failed", fields);
        }
        return Optional.of(launchFailures.get(key));
    }

    /**
     * Writes the worker's contract and the decision-envelope example every launch prompt starts with.
     *
     * @param role  the role
     * @param extra what the fixture adds
     * @return the prompt
     */
    static String contract(String role, String extra) {
        return """
                You are a headless worker of the role %s of plan-marshall. Your work reaches the server only through \
                the tools pm_wait, pm_state and pm_do of the MCP server plan-marshall. Never ask anyone in prose, \
                never use a question tool of your host, and never end your turn with a question.

                Every tool result is a representation of a task entity in TOON. Follow only the links it offers, with \
                pm_do(plan_id, entity, link, args), naming args as the link's free parameters say. An offered task \
                has the single link ack: follow it first. The facts of the task say what to do.

                If the task cannot be decided without the operator's answer, follow the link ask (or ask:<task_id>) \
                with args {"decision": <envelope>}: two to five options, exactly one recommended with your \
                confidence (0 to 100) and a rationale naming the evidence, and the decision kind. %s Example:
                %s
                If no ask link is offered, decide on your own judgment and state it in your submission, or follow \
                infeasible with your evidence. When the facts carry answers, the question is settled: apply the \
                answer and never ask it again. When the representation says to call pm_wait, call it, and call it \
                again on 'wait: again'. When it has no links and says to end your turn, end your turn.
                %s""".formatted(role, EVIDENCE_RULE, ENVELOPE_EXAMPLE, extra);
    }

    /**
     * An item of the envelope's evidence as the stub accepts it ({@link QuestionStub#validateDecision}: text that
     * starts with {@code pm://}, at most 512 characters): the URI of the task entity of the plan.
     */
    static final String EVIDENCE_EXAMPLE = "pm://" + QuestionStub.PLAN_ID + "/T-1";

    /**
     * The evidence rule of the contract: the stub refuses an ask whose evidence holds anything but {@code pm://} URIs
     * with {@code invalid_link_args} ({@code every item must be a pm:// URI}).
     */
    static final String EVIDENCE_RULE = "The envelope's evidence is a list of at most 20 pm:// URIs and nothing else "
            + "(it may be empty): name the entity you work on as pm://<plan_id>/<entity>; a file name, a quote or a "
            + "line of code is no evidence item and belongs into the description.";

    /** The example of a decision envelope in the contract, with one evidence item ({@link #EVIDENCE_EXAMPLE}). */
    static final String ENVELOPE_EXAMPLE = """
            {"title":"Which reading of the contract","header":"The interface contract contradicts itself",\
            "description":"The description and the examples of the function disagree; the implementation \
            depends on which is meant.","evidence":["%s"],"blocks":"task","reversibility":"reversible","parts":[\
            {"question_text":"Which behaviour should the function implement?","decision_kind":"interpretation",\
            "answer_type":"one","options":[{"branch_key":"description","label":"Follow the description",\
            "description":"Implement what the text says.","consequences":"The examples are wrong."},\
            {"branch_key":"examples","label":"Follow the examples","description":"Implement what the examples \
            show.","consequences":"The text is wrong."}],"recommendation":{"branch_key":"description",\
            "confidence":60,"rationale":"The text states the intent; the examples look edited."},\
            "own_words":true,"note":true,"delegation":false}]}""".formatted(EVIDENCE_EXAMPLE);

    @Override
    public void close() throws IOException {
        for (var worker : workers) {
            try {
                terminate(worker);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (ownsStub) {
            stub.close();
        }
    }

    // --- launch ---

    private static Launch withPrompt(Launch launch, String prompt) {
        return new Launch(launch.worker(), launch.generation(), launch.role(), prompt, launch.resumable(),
                launch.toolTimeout(), launch.workTools(), launch.sessionId());
    }

    private Worker launch(Launch launch) throws IOException {
        var host = settings.host();
        var directory = Files.createDirectories(settings.workspace().resolve(launch.worker()));
        var label = launch.worker() + "-g" + launch.generation();
        var relayLog = settings.runDir().resolve("relay-" + label + ".jsonl");
        var output = settings.runDir().resolve("output-" + label + ".jsonl");
        var environment = workerEnvironment(launch, relayLog);
        var command = new ArrayList<>(settings.exec());
        if (LINUX) {
            for (var path : writeSet(directory)) {
                command.add("--write");
                command.add(path.toString());
            }
        }
        command.add("--");
        command.addAll(harnessCommand(host, launch, directory, environment));
        var builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectError(stderrFile(settings.runDir(), launch.worker(), launch.generation()).toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        var process = builder.start();
        var worker = new Worker(launch, host, process, directory, output, relayLog);
        workers.add(worker);
        Thread.ofPlatform().daemon().start(() -> stamp(process.getInputStream(), output));
        process.onExit().thenRun(() -> exited(worker));
        var fields = identity(worker);
        fields.put("host", host.executable());
        fields.put("model", String.valueOf(settings.model()));
        fields.put("pid", process.pid());
        fields.put("resumed", launch.sessionId() != null);
        fields.put("resumable", launch.resumable());
        fields.put("tool_timeout_ms", launch.toolTimeout() == null ? -1 : launch.toolTimeout().toMillis());
        stub.event("worker_started", fields);
        return worker;
    }

    private void exited(Worker worker) {
        var fields = identity(worker);
        fields.put("exit", worker.process().exitValue());
        stub.event("worker_exited", fields);
        stub.workerExited(worker.id(), worker.generation(), worker.launch().resumable());
    }

    private Map<String, String> workerEnvironment(Launch launch, Path relayLog) throws IOException {
        var environment = new LinkedHashMap<String, String>();
        System.getenv().forEach((name, value) -> {
            if (HOST_SESSION_PREFIXES.stream().noneMatch(name::startsWith)) {
                environment.put(name, value);
            }
        });
        environment.put("PWD", settings.workspace().resolve(launch.worker()).toString());
        environment.putAll(QuestionStub.relayEnvironment(stub.settings().socket(), launch.worker(),
                launch.generation(), launch.role(), settings.host().executable(), relayLog));
        if (settings.host() == Host.CLAUDE && launch.toolTimeout() != null) {
            environment.put("MCP_TOOL_TIMEOUT", Long.toString(launch.toolTimeout().toMillis()));
        }
        if (settings.host() == Host.OPENCODE) {
            applyOpenCodeDataHome(environment, settings.openCodeDataHome(),
                    settings.workspace().resolve(launch.worker()));
        }
        return environment;
    }

    /**
     * Sets OpenCode's data home in a worker's environment: on {@link OpenCodeDataHome#GLOBAL} the inherited
     * {@code XDG_DATA_HOME} (or its absence) stays as it is; on {@link OpenCodeDataHome#WORKER} it is the worker's own
     * ({@link #opencodeDataHome(Path)}).
     *
     * @param environment the worker's environment, changed in place
     * @param home        the data home
     * @param directory   the worker directory
     * @throws IOException if the worker's data home cannot be created
     */
    static void applyOpenCodeDataHome(Map<String, String> environment, OpenCodeDataHome home, Path directory)
            throws IOException {
        if (home == OpenCodeDataHome.WORKER) {
            environment.put("XDG_DATA_HOME", opencodeDataHome(directory).toString());
        }
    }

    /**
     * @param environment the environment the worker inherits
     * @return OpenCode's directory in the operator's global data home: {@code $XDG_DATA_HOME/opencode}, else
     *         {@code ~/.local/share/opencode}
     */
    static Path globalOpencodeData(Map<String, String> environment) {
        var dataHome = environment.get("XDG_DATA_HOME");
        return dataHome == null || dataHome.isBlank()
                ? Path.of(System.getProperty("user.home"), ".local", "share", "opencode")
                : Path.of(dataHome, "opencode");
    }

    /**
     * The per-worker data home of OpenCode ({@code XDG_DATA_HOME}, {@link OpenCodeDataHome#WORKER}) with the operator's
     * {@code auth.json} copied in; it
     * is kept while the worker's session may be resumed and removed with the workspace. With {@code --standalone}
     * OpenCode 2.1.2 keeps the worker's sessions in it ({@code opencode/opencode.db}) and resumes from it, but the copied
     * login does not carry: {@code opencode models --standalone} lists only the free models of the provider
     * {@code opencode} there, and a model of the login ({@code opencode/gpt-5.4-nano}, {@code opencode-go/...}) fails
     * with {@code Model unavailable} (verified 2026-10-21).
     *
     * @param directory the worker directory
     * @return the data home
     * @throws IOException if it cannot be created
     */
    static Path opencodeDataHome(Path directory) throws IOException {
        var dataHome = Files.createDirectories(directory.resolve("data"));
        var login = Path.of(System.getProperty("user.home"), ".local/share/opencode/auth.json");
        var target = Files.createDirectories(dataHome.resolve("opencode")).resolve("auth.json");
        if (Files.isRegularFile(login) && !Files.exists(target)) {
            Files.copy(login, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return dataHome;
    }

    private List<Path> writeSet(Path directory) {
        var home = Path.of(System.getProperty("user.home"));
        var paths = new ArrayList<Path>();
        paths.add(directory);
        settings.host().stateDirs.stream().map(home::resolve).filter(Files::exists).forEach(paths::add);
        if (settings.host() == Host.OPENCODE && settings.openCodeDataHome() == OpenCodeDataHome.GLOBAL) {
            var global = globalOpencodeData(System.getenv());
            if (Files.exists(global)) {
                paths.add(global);
            }
        }
        paths.add(Path.of("/tmp"));
        paths.add(Path.of("/dev"));
        return paths;
    }

    private List<String> harnessCommand(Host host, Launch launch, Path directory, Map<String, String> environment)
            throws IOException {
        var relay = QuestionStub.relayCommand();
        var stubTools = QuestionStub.TOOLS;
        var command = new ArrayList<String>();
        switch (host) {
            case CLAUDE -> {
                var config = directory.resolve("mcp-config.json");
                Files.writeString(config, mcpServers(relay, Map.of()));
                var allowed = new ArrayList<String>();
                stubTools.forEach(tool -> allowed.add("mcp__" + QuestionStub.SERVER + "__" + tool));
                allowed.addAll(launch.workTools());
                command.addAll(List.of(executable(host, environment), "-p", launch.prompt(), "--mcp-config",
                        config.toString(),
                        "--strict-mcp-config", "--allowedTools", String.join(",", allowed), "--disallowedTools",
                        host.questionTool(), "--output-format", "stream-json", "--verbose"));
                if (!launch.resumable()) {
                    command.add("--no-session-persistence");
                }
                if (launch.sessionId() != null) {
                    command.addAll(List.of("--resume", launch.sessionId()));
                }
                if (settings.model() != null) {
                    command.addAll(List.of("--model", settings.model()));
                }
            }
            case OPENCODE -> {
                Files.writeString(directory.resolve("opencode.json"), opencodeConfig(relay, launch, environment));
                command.addAll(opencodeCommand(launch.prompt(), settings.model(), launch.sessionId(),
                        executable(host, environment)));
            }
            case ANTIGRAVITY -> command.addAll(antigravityCommand(launch, directory, relay, environment));
        }
        return command;
    }

    /**
     * The launch of Antigravity: {@link #AGY_ARGV} (or {@code -Dpm.experiment.agy.argv}, a JSON array with the
     * placeholders {@code {prompt}}, {@code {model}}, {@code {session_id}} and {@code {mcp_config}}), and the stub's
     * MCP server written to {@link #AGY_CONFIG} (or {@code -Dpm.experiment.agy.config}) below the worker directory in
     * the {@code mcpServers} format, with the relay's identity in its {@code env}; a configuration below
     * {@code plugins/<name>/} gets the {@code plugin.json} that makes it a workspace plugin.
     */
    private List<String> antigravityCommand(Launch launch, Path directory, List<String> relay,
            Map<String, String> environment) throws IOException {
        var config = directory.resolve(System.getProperty("pm.experiment.agy.config", AGY_CONFIG));
        Files.createDirectories(config.getParent());
        var relayEnvironment = new LinkedHashMap<String, String>();
        environment.forEach((name, value) -> {
            if (name.startsWith("PM_Q_")) {
                relayEnvironment.put(name, value);
            }
        });
        Files.writeString(config, mcpServers(relay, relayEnvironment));
        var pluginDirectory = config.getParent();
        if (pluginDirectory.getParent() != null && "plugins".equals(pluginDirectory.getParent().getFileName()
                .toString())) {
            Files.writeString(pluginDirectory.resolve("plugin.json"), "{\"name\":\""
                    + pluginDirectory.getFileName() + "\"}\n");
        }
        var argv = new ArrayList<String>();
        var configured = System.getProperty("pm.experiment.agy.argv");
        if (configured == null) {
            argv.addAll(AGY_ARGV);
        } else {
            QuestionStub.JSON.readTree(configured).forEach(item -> argv.add(item.asText()));
        }
        var values = new LinkedHashMap<String, String>();
        values.put("{prompt}", launch.prompt());
        values.put("{model}", settings.model());
        values.put("{session_id}", launch.sessionId());
        values.put("{mcp_config}", config.toString());
        var command = expand(argv, values);
        if (!command.isEmpty() && Host.ANTIGRAVITY.executable().equals(command.getFirst())) {
            command.set(0, agyExecutable(environment));
        }
        environment.put("PM_Q_HOST_TOOLS", "all");
        return command;
    }

    /**
     * A session id that names no stored session, for the negative resume of R2: a UUID for Claude Code and Antigravity;
     * for OpenCode an id of its own form ({@code ses_} and 26 characters), because OpenCode refuses any other at once
     * ({@code Expected a string starting with "ses"}, exit 1), which is the unreadable id and not the missing one.
     *
     * @param host the host
     * @return the id
     */
    static String missingSessionId(Host host) {
        if (host == Host.OPENCODE) {
            var bytes = new byte[13];
            RANDOM.nextBytes(bytes);
            return "ses_" + HexFormat.of().formatHex(bytes);
        }
        return UUID.randomUUID().toString();
    }

    /**
     * The launch of OpenCode: {@link #OPENCODE_ARGV}, or {@code -Dpm.experiment.opencode.argv} (a JSON array with the
     * placeholders {@code {prompt}}, {@code {model}} and {@code {session_id}}), with the executable by its absolute
     * path.
     *
     * @param prompt     the prompt
     * @param model      the model, {@code null} for the harness default
     * @param sessionId  the session to resume, {@code null} for a new one
     * @param executable the absolute path of {@code opencode}
     * @return the command
     * @throws JsonProcessingException if the property is no JSON array
     */
    static List<String> opencodeCommand(String prompt, String model, String sessionId, String executable)
            throws JsonProcessingException {
        var argv = new ArrayList<String>();
        var configured = System.getProperty("pm.experiment.opencode.argv");
        if (configured == null) {
            argv.addAll(OPENCODE_ARGV);
        } else {
            QuestionStub.JSON.readTree(configured).forEach(item -> argv.add(item.asText()));
        }
        var values = new LinkedHashMap<String, String>();
        values.put("{prompt}", prompt);
        values.put("{model}", model);
        values.put("{session_id}", sessionId);
        var command = expand(argv, values);
        if (!command.isEmpty() && Host.OPENCODE.executable().equals(command.getFirst())) {
            command.set(0, executable);
        }
        return command;
    }

    /**
     * Replaces the placeholders of a launch template; a placeholder without a value is dropped together with the
     * flag before it.
     *
     * @param argv   the template
     * @param values the value per placeholder, {@code null} for none
     * @return the command
     */
    static List<String> expand(List<String> argv, Map<String, String> values) {
        var command = new ArrayList<String>();
        for (var item : argv) {
            var value = item;
            var missing = false;
            for (var entry : values.entrySet()) {
                if (value.contains(entry.getKey())) {
                    if (entry.getValue() == null) {
                        missing = true;
                    } else {
                        value = value.replace(entry.getKey(), entry.getValue());
                    }
                }
            }
            if (missing) {
                if (!command.isEmpty() && command.getLast().startsWith("-")) {
                    command.removeLast();
                }
            } else if (!value.isEmpty()) {
                command.add(value);
            }
        }
        return command;
    }

    /**
     * The executable of Claude Code or OpenCode by its absolute path, as {@code pm-exec} requires it:
     * {@code -Dpm.experiment.exe.<host>}, else the first match on the worker's {@code PATH}, else the host's known
     * location ({@link #knownLocations(Host)}).
     *
     * @param host        the host
     * @param environment the worker's environment, whose {@code PATH} is searched
     * @return the absolute path
     * @throws IllegalStateException if the property names no executable file, or none is found
     */
    static String executable(Host host, Map<String, String> environment) {
        return executable(host, System.getProperty(EXECUTABLE_PROPERTY + host.executable()),
                environment.getOrDefault("PATH", ""), knownLocations(host));
    }

    /**
     * @param host           the host
     * @param override       the path the property names, {@code null} for none
     * @param searchPath     the {@code PATH} to search
     * @param knownLocations the directories searched after it
     * @return the absolute path of the host's executable
     * @throws IllegalStateException if the override names no executable file, or none is found
     */
    static String executable(Host host, String override, String searchPath, List<Path> knownLocations) {
        var property = "-D" + EXECUTABLE_PROPERTY + host.executable();
        if (override != null) {
            var path = Path.of(override).toAbsolutePath();
            if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
                throw new IllegalStateException(property + " names no executable file: " + path);
            }
            return path.toString();
        }
        var directories = new ArrayList<Path>();
        for (var entry : searchPath.split(File.pathSeparator)) {
            if (!entry.isEmpty()) {
                directories.add(Path.of(entry));
            }
        }
        directories.addAll(knownLocations);
        for (var directory : directories) {
            var candidate = directory.resolve(host.executable()).toAbsolutePath();
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        throw new IllegalStateException(host.executable() + " not found on the PATH or in " + knownLocations
                + ": set " + property + " to its absolute path");
    }

    /**
     * @param host a host
     * @return where the host installs itself: {@code ~/.local/bin} for Claude Code and Antigravity,
     *         {@code /opt/homebrew/bin} for OpenCode
     */
    static List<Path> knownLocations(Host host) {
        return switch (host) {
            case CLAUDE, ANTIGRAVITY -> List.of(Path.of(System.getProperty("user.home"), ".local", "bin"));
            case OPENCODE -> List.of(Path.of("/opt/homebrew/bin"));
        };
    }

    /** @return {@code agy} from the worker's {@code PATH}, else {@code ~/.local/bin/agy}, where it installs itself */
    private static String agyExecutable(Map<String, String> environment) {
        for (var entry : environment.getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!entry.isEmpty() && Files.isExecutable(Path.of(entry, Host.ANTIGRAVITY.executable()))) {
                return Path.of(entry, Host.ANTIGRAVITY.executable()).toString();
            }
        }
        return Path.of(System.getProperty("user.home"), ".local", "bin", Host.ANTIGRAVITY.executable()).toString();
    }

    private static String mcpServers(List<String> relay, Map<String, String> environment)
            throws JsonProcessingException {
        var root = QuestionStub.JSON.createObjectNode();
        var server = root.putObject("mcpServers").putObject(QuestionStub.SERVER);
        server.put("type", "stdio");
        server.put("command", relay.getFirst());
        var args = server.putArray("args");
        relay.subList(1, relay.size()).forEach(args::add);
        if (!environment.isEmpty()) {
            var env = server.putObject("env");
            environment.forEach(env::put);
        }
        return QuestionStub.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    /**
     * The {@code opencode.json} of a worker directory: the stub's MCP server as a local server with the relay's
     * identity in its {@code environment} (it reached the relay both with {@code --standalone} and through the
     * background service, which does not start the server in the worker's environment), and the agent
     * {@value #OPENCODE_AGENT} with {@value #OPENCODE_CODE_TOOL}, the stub's tools ({@code plan-marshall*}) and the
     * work tools only; every other tool, the question tool among them, is denied.
     *
     * @param relay       the relay command line
     * @param launch      the launch
     * @param environment the worker's environment; its {@code PM_Q_} variables go into the server's environment
     * @return the configuration
     * @throws JsonProcessingException if it cannot be written
     */
    static String opencodeConfig(List<String> relay, Launch launch, Map<String, String> environment)
            throws JsonProcessingException {
        var root = QuestionStub.JSON.createObjectNode();
        root.put("$schema", "https://opencode.ai/config.json");
        var server = root.putObject("mcp").putObject(QuestionStub.SERVER);
        server.put("type", "local");
        var command = server.putArray("command");
        relay.forEach(command::add);
        server.put("enabled", true);
        var serverEnvironment = server.putObject("environment");
        environment.forEach((name, value) -> {
            if (name.startsWith("PM_Q_")) {
                serverEnvironment.put(name, value);
            }
        });
        if (launch.toolTimeout() != null) {
            server.put("timeout", launch.toolTimeout().toMillis());
        }
        var agent = root.putObject("agent").putObject(OPENCODE_AGENT);
        agent.put("description", "question-loop worker");
        agent.put("mode", "primary");
        var tools = agent.putObject("tools");
        tools.put("*", false);
        tools.put(OPENCODE_CODE_TOOL, true);
        tools.put(QuestionStub.SERVER + "*", true);
        launch.workTools().forEach(tool -> tools.put(tool, true));
        return QuestionStub.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    /**
     * @return the {@code pm-exec} command line: {@code -Dpm.experiment.exec}, else the native binary of a checkout of
     *         {@code pm-mcp-clients} beside this repository, else its JAR staged by {@code -Pintegration-tests}
     */
    static List<String> resolveExec() {
        var configured = System.getProperty("pm.experiment.exec");
        if (configured != null) {
            return List.of(configured);
        }
        var root = Path.of("").toAbsolutePath().getParent();
        var nativeBinary = root.resolveSibling("pm-mcp-clients").resolve("pm-exec/target/pm-exec");
        if (Files.isExecutable(nativeBinary)) {
            return List.of(nativeBinary.toString());
        }
        var jar = Path.of("target", "e2e-jvm-lib", "pm-exec.jar").toAbsolutePath();
        if (Files.isRegularFile(jar)) {
            var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            return List.of(java, "--enable-native-access=ALL-UNNAMED", "-cp", jar.toString(),
                    "de.planmarshall.exec.PmExec");
        }
        throw new IllegalStateException("pm-exec not found: set -Dpm.experiment.exec, build ../pm-mcp-clients "
                + "natively, or stage the JVM layout with -Pintegration-tests");
    }

    // --- helpers ---

    private void signal(Worker worker, String signal) {
        try {
            var process = new ProcessBuilder("kill", "-" + signal, Long.toString(worker.process().pid()))
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (IOException e) {
            throw new IllegalStateException("kill -" + signal + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @return {@code true} when the worker made no call for the silence limit (or exited), {@code false} when it
     *         acknowledged the answer late, which on an informational host shows it alive
     */
    private boolean silentFor(Worker worker, Duration limit) throws IOException, InterruptedException {
        var start = System.currentTimeMillis();
        while (worker.process().isAlive()) {
            var lastCall = 0L;
            for (var event : QuestionResults.readJsonLines(stub.events())) {
                var name = event.path(EVENT).asText();
                if (matches(event, worker) && "acknowledged".equals(name)
                        && "answer".equals(event.path("kind").asText())) {
                    return false;
                }
                if (CALL.equals(name) && matches(event, worker)) {
                    lastCall = event.path("at").asLong();
                }
            }
            var now = System.currentTimeMillis();
            if (now - lastCall >= limit.toMillis()) {
                return true;
            }
            if (now - start >= limit.toMillis() && lastCall > start) {
                return false;
            }
            Thread.sleep(POLL);
        }
        return true;
    }

    private boolean sawEvent(Worker worker, String name) throws IOException {
        for (var event : QuestionResults.readJsonLines(stub.events())) {
            if (name.equals(event.path(EVENT).asText()) && matches(event, worker)) {
                return true;
            }
        }
        return false;
    }

    /** @return whether a line is {@code pm-exec}'s report of an error ({@code {"pm_exec":"error",...}}) */
    static boolean pmExecError(String line) {
        return "error".equals(pmExecStage(line));
    }

    /** @return whether a line is one of {@code pm-exec}'s own ({@code {"pm_exec":...}}), not the harness's */
    static boolean pmExecLine(String line) {
        return !pmExecStage(line).isEmpty();
    }

    private static String pmExecStage(String line) {
        try {
            var node = QuestionStub.JSON.readTree(line);
            return node != null && node.path("pm_exec").isTextual() ? node.path("pm_exec").asText() : "";
        } catch (JsonProcessingException _) {
            return "";
        }
    }

    /**
     * The launch failure of a worker from what it wrote to {@code stderr}: the harness's own first line and last
     * {@value #STDERR_TAIL} lines, without the lines {@code pm-exec} writes itself, so that the cause reaches the run
     * record and not {@code pm-exec}'s {@code launched} line.
     *
     * @param worker     the worker id
     * @param generation its generation
     * @param exit       its exit code
     * @param stderr     what it wrote to {@code stderr}
     * @return the launch failure
     */
    static LaunchFailure launchFailure(String worker, int generation, int exit, String stderr) {
        var lines = stderr.lines().filter(line -> !line.isBlank()).toList();
        var harness = lines.stream().filter(line -> !pmExecLine(line)).toList();
        var first = harness.stream().findFirst()
                .or(() -> lines.stream().filter(QuestionDriver::pmExecError).findFirst())
                .or(() -> lines.stream().findFirst()).orElse("");
        return new LaunchFailure(worker, generation, exit, first,
                List.copyOf(harness.subList(Math.max(0, harness.size() - STDERR_TAIL), harness.size())));
    }

    private static boolean matches(JsonNode event, Worker worker) {
        return worker.id().equals(event.path(WORKER).asText()) && worker.generation() == event.path(GENERATION).asInt();
    }

    private static Map<String, Object> identity(Worker worker) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put(WORKER, worker.id());
        fields.put(GENERATION, worker.generation());
        return fields;
    }

    /** Copies the worker's output line by line, each with its arrival time. */
    private static void stamp(InputStream stream, Path output) {
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
             var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                     StandardOpenOption.APPEND)) {
            for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                var node = QuestionStub.JSON.createObjectNode();
                node.put("at", System.currentTimeMillis());
                node.put("line", line);
                writer.write(QuestionStub.JSON.writeValueAsString(node));
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            throw new IllegalStateException("worker output not recorded: " + output, e);
        }
    }

    /**
     * @param output an arrival-stamped output file
     * @return its lines that are JSON, parsed
     * @throws IOException if it cannot be read
     */
    static List<JsonNode> outputLines(Path output) throws IOException {
        var lines = new ArrayList<JsonNode>();
        for (var stamped : QuestionResults.readJsonLines(output)) {
            try {
                lines.add(QuestionStub.JSON.readTree(stamped.path("line").asText()));
            } catch (JsonProcessingException _) {
                // a line of the harness that is no JSON carries no figure
            }
        }
        return lines;
    }
}
