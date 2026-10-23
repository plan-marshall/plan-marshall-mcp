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
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.URISyntaxException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The stub MCP server of the question loop (experiment only): the task protocol of PM-WORK-2 with the question links of
 * PM-WF-12 and PM-WF-18 and the consultation of PM-WORK-4, as far as the measurement needs them.
 * <ul>
 * <li>Tools {@code pm_wait}, {@code pm_state} and {@code pm_do}; every result is a TOON representation of a task
 * entity, or {@code wait: again} / {@code wait: end}.</li>
 * <li>Leases per generation: a worker's identity, role and generation arrive in the first line of its connection,
 * written by its {@link Relay}, never by the model; a newer generation of a worker fences the older one, whose calls
 * are refused {@code generation_fenced} and whose leases are released.</li>
 * <li>The links {@code ask}, {@code ask:TASK-NNN} (the question envelope checked: two to five options, exactly one
 * recommended, confidence, rationale, decision kind; {@code invalid_link_args} on a violation),
 * {@code consult:<role>}, and the vote link {@code submit:vote} of a quorum; the question bound withdraws the ask
 * links once reached.</li>
 * <li>Continuation per task ({@link Form}): {@code HOLD} keeps the task leased in {@code awaiting_answer} and answers
 * the worker's {@code pm_wait} with the answer and the single link {@code ack}; {@code RESUME} suspends the task for a
 * resumed session; {@code FALLBACK} ends the task {@code asked} (the {@code asked} representation has
 * {@code links: []}), and the answer reaches a new task in {@code facts.answers} exactly once.</li>
 * <li>Question records are appended to {@code questions.jsonl} of the stub directory and replayed by
 * {@link #start(Settings)}, so a restarted stub keeps an open record and delivers its answer exactly once.</li>
 * <li>Every transition is appended to {@code events.jsonl} with its arrival time; every figure of the measurement is
 * computed from it.</li>
 * </ul>
 * Nothing is decided by time: a decision is answered only by {@link #answer(String, int, List)} or by its quorum.
 */
final class QuestionStub implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();
    /** The plan id every representation carries. */
    static final String PLAN_ID = "qlv";
    /** The compiled question bound per entry of a state and per plan task. */
    static final int QUESTION_BOUND = 3;
    /** The server name every harness sees. */
    static final String SERVER = "plan-marshall";
    /** The tools of the stub. */
    static final List<String> TOOLS = List.of("pm_wait", "pm_state", "pm_do");
    /** The text of a wait answered without work. */
    static final String WAIT_AGAIN = "wait: again\n";
    /** The text of a wait that ends the worker. */
    static final String WAIT_END = "wait: end\n";
    /**
     * The prefix of a vote task, its entity and the worker that serves it: {@code vote-<question_set>-<role>}
     * (Model Work § The Adjudication).
     */
    static final String VOTE_PREFIX = "vote-";
    /** The reason a held {@code pm_wait} is refused with when the stub stops (also the stop of a restart). */
    static final String STUB_STOPPED = "stub_stopped";
    /**
     * The event a stub that does not lose a worker at its process exit ({@link Settings#loseOnExit()} false) logs at
     * that exit: the observation, with the tasks still leased to the generation and their states.
     */
    static final String EXIT_OBSERVED = "exit_observed";

    private static final Set<String> DECISION_KINDS = Set.of("interpretation", "approach", "granularity",
            "placement", "test_strategy", "scope", "product_behaviour", "assurance", "external_fact");
    private static final Set<String> DECISION_FIELDS = Set.of("title", "header", "description", "evidence", "blocks",
            "reversibility", "revisits", "parts");
    private static final Set<String> PART_FIELDS = Set.of("part_key", "question_text", "decision_kind",
            "answer_type", "value_schema", "options", "depends_on", "recommendation", "own_words", "note",
            "delegation");
    private static final Set<String> OPTION_FIELDS = Set.of("branch_key", "label", "description", "consequences",
            "preview");
    private static final Set<String> SERVER_OWNED = Set.of("question_id", "class", "origin", "source_entity",
            "revision", "plan_task_id");
    private static final Pattern BRANCH_KEY = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");
    private static final Pattern FREE = Pattern.compile("^([a-z_]+):(text|int|enum|list|schema)(.*)$");
    private static final Pattern RANGE = Pattern.compile("^\\[(\\d+)\\.\\.(\\d+)]$");
    private static final Pattern ENVELOPE_ENTITY = Pattern.compile("^envelope-(\\d+)$");
    private static final String ENVELOPE_LINK = "decision:schema[decision-envelope]";
    private static final String STATE_OFFERED = "task.offered";
    private static final String STATE_ANSWER = "task.answer";
    private static final String STATE_QUEUED = "task.queued";
    private static final String STATE_SUSPENDED = "task.suspended";
    private static final String STATE_RESUMABLE = "task.resumable";
    private static final String STATE_CONSULTING = "consulting";
    private static final String OUTCOME_SUBMITTED = "submitted";
    private static final String OUTCOME_ASKED = "asked";
    private static final String OUTCOME_INFEASIBLE = "infeasible";
    private static final String OUTCOME_LOST = "lost";
    private static final String INVALID_ARGS = "invalid_link_args";
    private static final String TASK = "task";
    private static final String WORKER = "worker";
    private static final String GENERATION = "generation";
    private static final String DECISION = "decision";
    private static final String REASON = "reason";
    private static final String OPTIONS = "options";
    private static final String BRANCH = "branch_key";
    private static final String TEXT = "text";
    private static final String ANSWERS = "answers";
    private static final String QUESTION_ID = "question_id";
    private static final String NONE_FITS = "none_fits";

    /** How a task continues after its worker asked. */
    enum Form {
        /** The worker holds its task in {@code pm_wait} until the answer. */
        HOLD,
        /** The worker ends with its session stored; a resumed generation continues. */
        RESUME,
        /** The task ends {@code asked}; a new task carries the answer. */
        FALLBACK
    }

    /** The kind of a task entity. */
    enum Kind {
        /** A task of a state that may declare {@code ask} and {@code consult:<role>}. */
        ENTITY,
        /** An envelope task over plan tasks, with {@code ask:TASK-NNN}. */
        ENVELOPE,
        /** A vote task of a quorum, blind to the recommendation. */
        VOTE,
        /** A consultation task of the consulted role. */
        CONSULTATION
    }

    /**
     * The quorum declaration of one decision kind.
     *
     * @param roles the listed roles
     * @param score the declared score
     */
    record Quorum(List<String> roles, int score) {
    }

    /**
     * One plan task of an envelope.
     *
     * @param id         the plan task id ({@code TASK-001})
     * @param text       what the plan task asks for
     * @param submitFree the free parameters of its submit link
     */
    record PlanTask(String id, String text, String submitFree) {
    }

    /**
     * What a task is; persisted with every question record, so a restarted stub can continue it.
     *
     * @param taskId       the queue task id
     * @param entryKey     the entry of the state it belongs to; continuations share it, the question bound counts it
     * @param role         the role whose worker serves it
     * @param kind         its kind
     * @param entity       the entity it works on
     * @param summary      the one-line summary of its active state
     * @param facts        its facts ({@code plan_tasks} are rendered per plan task, never whole)
     * @param planTasks    the plan tasks of an envelope, empty otherwise
     * @param cursor       the first plan task to work on
     * @param ask          whether its state declares {@code ask}
     * @param consultRoles the roles its state declares {@code consult role} for
     * @param form         how it continues after a question
     * @param submitFree   the free parameters of its submit link (not of an envelope)
     */
    record TaskSpec(String taskId, String entryKey, String role, Kind kind, String entity, String summary,
    ObjectNode facts, List<PlanTask> planTasks, int cursor, boolean ask, List<String> consultRoles, Form form,
    String submitFree) {

        /**
         * @param taskId     the queue task id, also the entity and the entry
         * @param role       the role
         * @param summary    the summary
         * @param facts      the facts
         * @param submitFree the free parameters of {@code submit}
         * @param form       the continuation form
         * @param consult    the consultable roles
         * @return a task whose state declares {@code ask}
         */
        static TaskSpec entity(String taskId, String role, String summary, ObjectNode facts, String submitFree,
                Form form, List<String> consult) {
            return new TaskSpec(taskId, taskId, role, Kind.ENTITY, taskId, summary, facts, List.of(), 0, true,
                    List.copyOf(consult), form, submitFree);
        }

        /**
         * @param taskId    the queue task id, also the entry
         * @param role      the role
         * @param summary   the summary
         * @param facts     the facts
         * @param planTasks the plan tasks
         * @param form      the continuation form
         * @return the first envelope task ({@code envelope-1}) over the plan tasks
         */
        static TaskSpec envelope(String taskId, String role, String summary, ObjectNode facts,
                List<PlanTask> planTasks, Form form) {
            return new TaskSpec(taskId, taskId, role, Kind.ENVELOPE, "envelope-1", summary, facts,
                    List.copyOf(planTasks), 0, true, List.of(), form, "");
        }

        TaskSpec continuing(String newTaskId, String newEntity, int newCursor) {
            return new TaskSpec(newTaskId, entryKey, role, kind, newEntity, summary, facts, planTasks, newCursor, ask,
                    consultRoles, form, submitFree);
        }
    }

    /**
     * The operator's answer to one part of a decision.
     *
     * @param questionId the part's {@code question_id}
     * @param branchKey  the chosen option ({@code +}-joined for several), {@code value}, {@code own} or
     *                   {@code none_fits}
     * @param text       the operator's own words or note, may be {@code null}
     * @param kind       how it was taken: {@code chosen}, {@code interpreted}, {@code own_option},
     *                   {@code delegated} or {@code none_fits}
     */
    record PartAnswer(String questionId, String branchKey, String text, String kind) {
    }

    /** The outcome of an answer. */
    enum AnswerResult {
        /** Recorded. */
        ANSWERED,
        /** An unknown decision or option, or a part left out. */
        INVALID_ANSWER,
        /** An answer to an older revision. */
        CONFLICT,
        /** A second answer. */
        ALREADY_ANSWERED
    }

    /**
     * The stub's settings.
     *
     * @param directory     the stub directory ({@code events.jsonl}, {@code questions.jsonl})
     * @param socket        the Unix socket the relays connect to (must fit {@code sun_path})
     * @param window        the bounded wait of {@code pm_wait}
     * @param progressEvery the interval of progress frames on a wait that carries a progress token
     * @param questionBound the question bound
     * @param quorums       the quorum declaration per decision kind; a kind without one is {@code ask}
     * @param loseOnExit    whether a worker's process exit ends the generation as lost
     *                      ({@link QuestionStub#workerExited}): {@code true} for every item but R3; in R3
     *                      ({@code false}) the exit is only observed ({@value QuestionStub#EXIT_OBSERVED}) and the
     *                      task stays leased until the driver's supervision detects
     *                      the missing acknowledgement, so the acknowledgement is the only detection R3 measures
     */
    record Settings(Path directory, Path socket, Duration window, Duration progressEvery, int questionBound,
    Map<String, Quorum> quorums, boolean loseOnExit) {

        /**
         * Settings that lose a worker at its process exit, as every item but R3 runs.
         *
         * @param directory     the stub directory
         * @param socket        the socket
         * @param window        the bounded wait of {@code pm_wait}
         * @param progressEvery the interval of progress frames
         * @param questionBound the question bound
         * @param quorums       the quorum declaration per decision kind
         */
        Settings(Path directory, Path socket, Duration window, Duration progressEvery, int questionBound,
                Map<String, Quorum> quorums) {
            this(directory, socket, window, progressEvery, questionBound, quorums, true);
        }

        /**
         * @param directory the stub directory
         * @param socket    the socket
         * @return the settings with the bounded wait of Claude Code and OpenCode ({@code 240 s}), progress every
         *         {@code 5 s}, the compiled question bound, no quorum, and the loss of a worker at its process exit
         */
        static Settings of(Path directory, Path socket) {
            return new Settings(directory, socket, Duration.ofSeconds(240), Duration.ofSeconds(5), QUESTION_BOUND,
                    Map.of());
        }

        Settings withWindow(Duration value) {
            return new Settings(directory, socket, value, progressEvery, questionBound, quorums, loseOnExit);
        }

        Settings withQuestionBound(int value) {
            return new Settings(directory, socket, window, progressEvery, value, quorums, loseOnExit);
        }

        Settings withQuorums(Map<String, Quorum> value) {
            return new Settings(directory, socket, window, progressEvery, questionBound, Map.copyOf(value),
                    loseOnExit);
        }

        Settings withLoseOnExit(boolean value) {
            return new Settings(directory, socket, window, progressEvery, questionBound, quorums, value);
        }
    }

    private record Outcome(String text, boolean error) {

        static Outcome of(String text) {
            return new Outcome(text, false);
        }

        static Outcome refused(String reason) {
            return new Outcome("error: " + reason + "\n", true);
        }
    }

    private static final class Task {

        final TaskSpec spec;
        String state = STATE_QUEUED;
        String worker;
        int generation;
        String lastHolder;
        int cursor;
        boolean closed;
        String outcome;
        ArrayNode answers = JSON.createArrayNode();
        final ArrayNode consultations = JSON.createArrayNode();
        final Set<String> deliveredConsultations = new LinkedHashSet<>();
        boolean answerReady;
        boolean consultationReady;
        boolean answerAckPending;
        boolean sessionStored;
        long offeredAt;
        String openDecision;

        Task(TaskSpec spec) {
            this.spec = spec;
            this.cursor = spec.cursor();
        }

        boolean leasedTo(String key) {
            return !closed && worker != null && key.equals(worker + "#" + generation);
        }

        PlanTask planTask() {
            return spec.planTasks().get(cursor);
        }

        String lastWorker() {
            return lastHolder == null ? "" : lastHolder.substring(0, lastHolder.lastIndexOf('#'));
        }
    }

    private static final class Decision {

        final String id;
        final String taskId;
        final String planTaskId;
        final String entryKey;
        final ObjectNode envelope;
        final TaskSpec spec;
        final Form form;
        final List<String> questionIds;
        final ObjectNode votes = JSON.createObjectNode();
        final Set<String> pendingVotes = new HashSet<>();
        int revision = 1;
        boolean delivered;
        boolean answered;
        boolean continued;
        ArrayNode items = JSON.createArrayNode();

        Decision(String id, String taskId, String planTaskId, String entryKey, ObjectNode envelope, TaskSpec spec,
                List<String> questionIds) {
            this.id = id;
            this.taskId = taskId;
            this.planTaskId = planTaskId;
            this.entryKey = entryKey;
            this.envelope = envelope;
            this.spec = spec;
            this.form = spec.form();
            this.questionIds = List.copyOf(questionIds);
        }

        ArrayNode parts() {
            return (ArrayNode) envelope.get("parts");
        }
    }

    private final class Connection {

        final SocketChannel channel;
        final OutputStream out;
        final Object writeLock = new Object();
        String worker = "";
        int generation;
        String role = "";

        Connection(SocketChannel channel) {
            this.channel = channel;
            this.out = Channels.newOutputStream(channel);
        }

        String key() {
            return worker + "#" + generation;
        }

        void send(ObjectNode message) throws IOException {
            var bytes = (JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (writeLock) {
                out.write(bytes);
                out.flush();
            }
        }

        Map<String, Object> identity() {
            var identity = new LinkedHashMap<String, Object>();
            identity.put(WORKER, worker);
            identity.put(GENERATION, generation);
            return identity;
        }
    }

    private final Settings settings;
    private final ServerSocketChannel server;
    private final Path events;
    private final Path records;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final Object eventLock = new Object();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final Map<String, Decision> decisions = new LinkedHashMap<>();
    private final Map<String, Integer> generations = new HashMap<>();
    private final Map<String, Integer> waits = new HashMap<>();
    private final Map<String, String> consultationAsker = new HashMap<>();
    private final Map<String, String> voteDecision = new HashMap<>();
    private final Set<String> ended = new HashSet<>();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<String> operatorDecisions = new LinkedBlockingQueue<>();
    private int nextQuestion;
    private int nextConsultation;
    private int nextYield;
    private volatile boolean closed;

    private QuestionStub(Settings settings, ServerSocketChannel server) {
        this.settings = settings;
        this.server = server;
        this.events = settings.directory().resolve("events.jsonl");
        this.records = settings.directory().resolve("questions.jsonl");
    }

    /**
     * Starts the stub: replays the question records of the stub directory, binds the socket, and accepts relays.
     *
     * @param settings the settings
     * @return the running stub
     * @throws IOException if the directory, the records, or the socket cannot be used
     */
    static QuestionStub start(Settings settings) throws IOException {
        Files.createDirectories(settings.directory());
        Files.deleteIfExists(settings.socket());
        var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(settings.socket()));
        var stub = new QuestionStub(settings, server);
        stub.replay();
        var started = new LinkedHashMap<String, Object>();
        started.put("records", stub.decisions.size());
        if (!settings.loseOnExit()) {
            started.put("lose_on_exit", false);
        }
        stub.event("stub_started", started);
        Thread.ofPlatform().daemon().name("question-stub-accept").start(stub::acceptLoop);
        return stub;
    }

    /** @return the settings */
    Settings settings() {
        return settings;
    }

    /** @return the event log */
    Path events() {
        return events;
    }

    /** @return the question records */
    Path records() {
        return records;
    }

    /** @return the ids of the decisions delivered to the operator, in the order of delivery */
    BlockingQueue<String> operatorDecisions() {
        return operatorDecisions;
    }

    /**
     * Queues a task for the workers of its role.
     *
     * @param spec the task
     */
    void queue(TaskSpec spec) {
        lock.lock();
        try {
            tasks.put(spec.taskId(), new Task(spec));
            event("queued", Map.of(TASK, spec.taskId(), "role", spec.role(), "entity", spec.entity()));
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Leases a task to a generation at its spawn (a fresh worker, or a resumed session); a resumable task is offered
     * with its answer, so the offer's acknowledgement is the answer acknowledgement.
     *
     * @param taskId     the task
     * @param worker     the worker
     * @param generation its generation
     */
    void leaseAtSpawn(String taskId, String worker, int generation) {
        lock.lock();
        try {
            advanceGeneration(worker, generation);
            var task = Objects.requireNonNull(tasks.get(taskId), taskId);
            var resumed = STATE_RESUMABLE.equals(task.state);
            lease(task, worker, generation);
            task.answerAckPending = resumed;
            var fields = identity(worker, generation);
            fields.put(TASK, taskId);
            fields.put("at_spawn", true);
            fields.put("resumed", resumed);
            event(resumed ? "answer_delivered" : "offered", fields);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Answers a decision on the operator's behalf (channel {@code cli}).
     *
     * @param decisionId the decision
     * @param revision   the revision it answers
     * @param parts      one answer per part
     * @return the outcome
     */
    AnswerResult answer(String decisionId, int revision, List<PartAnswer> parts) {
        lock.lock();
        try {
            var decision = decisions.get(decisionId);
            if (decision == null) {
                return AnswerResult.INVALID_ANSWER;
            }
            if (decision.answered) {
                event("answer_refused", Map.of(DECISION, decisionId, REASON, "already_answered"));
                return AnswerResult.ALREADY_ANSWERED;
            }
            if (revision != decision.revision) {
                event("answer_refused", Map.of(DECISION, decisionId, REASON, "conflict"));
                return AnswerResult.CONFLICT;
            }
            if (!validAnswer(decision, parts)) {
                event("answer_refused", Map.of(DECISION, decisionId, REASON, "invalid_answer"));
                return AnswerResult.INVALID_ANSWER;
            }
            recordAnswer(decision, parts, "cli");
            return AnswerResult.ANSWERED;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Answers a consultation on the consulted role's behalf, as a scripted consulted role does.
     *
     * @param consultationId the consultation
     * @param text           the answer
     */
    void answerConsultation(String consultationId, String text) {
        lock.lock();
        try {
            var task = tasks.get(consultationId);
            if (task != null && !task.closed) {
                close(task, OUTCOME_SUBMITTED);
                deliverConsultation(consultationId, text, "answered");
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reports a worker process that exited: a suspended task keeps its stored session; every other lease of the
     * generation is handled as a lost worker's. A stub that does not lose a worker at its exit
     * ({@link Settings#loseOnExit()} false, the R3 runs) only observes it ({@link #observeExit}).
     *
     * @param worker        the worker
     * @param generation    its generation
     * @param sessionStored whether the harness stored its session
     */
    void workerExited(String worker, int generation, boolean sessionStored) {
        lock.lock();
        try {
            var key = worker + "#" + generation;
            if (!settings.loseOnExit()) {
                observeExit(key, worker, generation, sessionStored);
                return;
            }
            for (var task : List.copyOf(tasks.values())) {
                if (task.leasedTo(key) && STATE_SUSPENDED.equals(task.state)) {
                    task.worker = null;
                    task.sessionStored = sessionStored;
                    event("session_stored", Map.of(TASK, task.spec.taskId(), "stored", sessionStored));
                }
            }
            lost(worker, generation, "exited");
        } finally {
            lock.unlock();
        }
    }

    /**
     * The exit of a worker's process as the stub of R3 sees it: logged as {@value #EXIT_OBSERVED} with the tasks still
     * leased to the generation and their states, and nothing else. No task is closed, released or continued, so a task
     * whose answer was delivered stays leased until the driver's supervision detects the missing acknowledgement (or
     * silence where the acknowledgement is informational) and ends the generation ({@link #lost}); the
     * acknowledgement is then the only detection, as R3 measures it. A suspended task records whether its session was
     * stored ({@code session_stored}), as at every exit, keeping its lease: the next generation's fence releases it.
     * Before 2026-10-23 the stub of R3 lost the generation at the exit, about 15 s before the driver's detection, so the
     * detection was not made by the acknowledgement (R3 smoke attempt, runs {@code 8a799961}, {@code f8d38a77},
     * {@code 153d88f2}, fault {@code KILL}, and {@code cc787dd3}, fault {@code RESUMED_EXIT}).
     */
    private void observeExit(String key, String worker, int generation, boolean sessionStored) {
        var leased = new LinkedHashMap<String, String>();
        var suspended = new ArrayList<Task>();
        for (var task : tasks.values()) {
            if (task.leasedTo(key)) {
                leased.put(task.spec.taskId(), task.state);
                if (STATE_SUSPENDED.equals(task.state)) {
                    suspended.add(task);
                }
            }
        }
        var fields = identity(worker, generation);
        fields.put("leased", leased);
        event(EXIT_OBSERVED, fields);
        for (var task : suspended) {
            task.sessionStored = sessionStored;
            event("session_stored", Map.of(TASK, task.spec.taskId(), "stored", sessionStored));
        }
    }

    /**
     * Ends a worker as lost (its acknowledgement missing, its silence, its fence): every task leased to the
     * generation is released and offered again; a task whose answer was delivered and not acknowledged continues in
     * a new task with {@code facts.answers}, or, where its session was stored, is resumed again. A vote task is never
     * offered again (the stub has no re-offer budget, and the driver starts one voter per vote task): it closes
     * {@code lost} at once, its role has no vote, and the decision rule of the specification settles the set
     * (Model Work § Decision Rule): a listed role without a vote escalates it to the operator with the reason
     * {@code role_unavailable}, never as agreement.
     *
     * @param worker     the worker
     * @param generation its generation
     * @param reason     why
     */
    void lost(String worker, int generation, String reason) {
        lock.lock();
        try {
            var key = worker + "#" + generation;
            for (var task : List.copyOf(tasks.values())) {
                if (task.leasedTo(key)) {
                    release(task, reason);
                }
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Answers the generation's next {@code pm_wait} with {@code end}.
     *
     * @param worker     the worker
     * @param generation its generation
     */
    void end(String worker, int generation) {
        lock.lock();
        try {
            ended.add(worker + "#" + generation);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param taskId a task
     * @return its state ({@code task.queued}, {@code task.offered}, the active state, {@code task.<outcome>}), or
     *         empty for an unknown task
     */
    Optional<String> state(String taskId) {
        lock.lock();
        try {
            return Optional.ofNullable(tasks.get(taskId)).map(task -> task.state);
        } finally {
            lock.unlock();
        }
    }

    /** @return the ids of every task, in the order they were queued */
    List<String> taskIds() {
        lock.lock();
        try {
            return List.copyOf(tasks.keySet());
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param decisionId a decision
     * @return a snapshot: {@code id}, {@code revision}, {@code task_id}, {@code form}, {@code answered},
     *         {@code envelope}, {@code votes}, {@code items}; empty for an unknown decision
     */
    Optional<ObjectNode> decision(String decisionId) {
        lock.lock();
        try {
            var decision = decisions.get(decisionId);
            if (decision == null) {
                return Optional.empty();
            }
            var node = JSON.createObjectNode();
            node.put("id", decision.id);
            node.put("revision", decision.revision);
            node.put("task_id", decision.taskId);
            node.put("form", decision.form.name());
            node.put("answered", decision.answered);
            node.set("envelope", decision.envelope.deepCopy());
            node.set("votes", decision.votes.deepCopy());
            node.set("items", decision.items.deepCopy());
            return Optional.of(node);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Appends an event to the log, with its arrival time.
     *
     * @param name   the event
     * @param fields its fields (strings, numbers, booleans, JSON nodes)
     */
    void event(String name, Map<String, ?> fields) {
        var node = JSON.createObjectNode();
        node.put("at", System.currentTimeMillis());
        node.put("nanos", System.nanoTime());
        node.put("event", name);
        fields.forEach((key, value) -> node.set(key, JSON.valueToTree(value)));
        append(events, node);
    }

    @Override
    public void close() throws IOException {
        closed = true;
        lock.lock();
        try {
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        event("stub_stopped", Map.of());
        server.close();
        for (var connection : connections) {
            connection.channel.close();
        }
        Files.deleteIfExists(settings.socket());
    }

    // --- connections and MCP ---

    private void acceptLoop() {
        while (!closed) {
            try {
                var channel = server.accept();
                Thread.ofVirtual().start(() -> serve(channel));
            } catch (IOException e) {
                if (!closed) {
                    event("accept_failed", Map.of("detail", String.valueOf(e.getMessage())));
                }
                return;
            }
        }
    }

    private void serve(SocketChannel channel) {
        var connection = new Connection(channel);
        connections.add(connection);
        try {
            var reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel),
                    StandardCharsets.UTF_8));
            var hello = reader.readLine();
            if (hello == null) {
                return;
            }
            identify(connection, JSON.readTree(hello));
            for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                handle(connection, JSON.readTree(line));
            }
        } catch (IOException e) {
            if (!closed) {
                var fields = connection.identity();
                fields.put("detail", String.valueOf(e.getMessage()));
                event("connection_failed", fields);
            }
        } finally {
            connections.remove(connection);
            event("disconnected", connection.identity());
            try {
                channel.close();
            } catch (IOException e) {
                event("close_failed", Map.of("detail", String.valueOf(e.getMessage())));
            }
        }
    }

    private void identify(Connection connection, JsonNode hello) {
        connection.worker = hello.path(WORKER).asText();
        connection.generation = hello.path(GENERATION).asInt();
        connection.role = hello.path("role").asText();
        lock.lock();
        try {
            advanceGeneration(connection.worker, connection.generation);
            var fields = connection.identity();
            fields.put("role", connection.role);
            fields.put("harness", hello.path("harness").asText());
            fields.put("fenced", fenced(connection));
            event("connected", fields);
        } finally {
            lock.unlock();
        }
    }

    private void handle(Connection connection, JsonNode message) throws IOException {
        var id = message.get("id");
        if (id == null) {
            return;
        }
        var method = message.path("method").asText("");
        switch (method) {
            case "initialize" -> reply(connection, id, initializeResult(message.path("params")));
            case "ping" -> reply(connection, id, JSON.createObjectNode());
            case "tools/list" -> reply(connection, id, toolsList());
            case "tools/call" -> {
                var params = message.path("params");
                Thread.ofVirtual().start(() -> call(connection, id, params));
            }
            default -> {
                var response = JSON.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.set("id", id);
                var error = response.putObject("error");
                error.put("code", -32601);
                error.put("message", "method not found: " + method);
                connection.send(response);
            }
        }
    }

    private static void reply(Connection connection, JsonNode id, ObjectNode result) throws IOException {
        var response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        connection.send(response);
    }

    private static ObjectNode initializeResult(JsonNode params) {
        var result = JSON.createObjectNode();
        result.put("protocolVersion", params.path("protocolVersion").asText("2025-06-18"));
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        var info = result.putObject("serverInfo");
        info.put("name", SERVER);
        info.put("version", "question-loop-stub");
        return result;
    }

    private static ObjectNode toolsList() {
        var result = JSON.createObjectNode();
        var tools = result.putArray("tools");
        var wait = tools.addObject();
        wait.put("name", "pm_wait");
        wait.put("description", "Wait for your next task or for the answer your task waits for. Returns a task "
                + "representation, 'wait: again' (call pm_wait again), or 'wait: end' (end your turn).");
        var waitSchema = wait.putObject("inputSchema");
        waitSchema.put("type", "object");
        waitSchema.putObject("properties");
        waitSchema.put("additionalProperties", false);
        var state = tools.addObject();
        state.put("name", "pm_state");
        state.put("description", "Read the representation of a task entity leased to you.");
        var stateSchema = state.putObject("inputSchema");
        stateSchema.put("type", "object");
        stateSchema.putObject("properties").putObject("entity").put("type", "string");
        stateSchema.put("additionalProperties", false);
        var doTool = tools.addObject();
        doTool.put("name", "pm_do");
        doTool.put("description", "Follow one link of a task entity leased to you: plan_id and entity from the "
                + "representation, link from its links, args as the link's free parameters name them.");
        var doSchema = doTool.putObject("inputSchema");
        doSchema.put("type", "object");
        var properties = doSchema.putObject("properties");
        properties.putObject("plan_id").put("type", "string");
        properties.putObject("entity").put("type", "string");
        properties.putObject("link").put("type", "string");
        properties.putObject("args").put("type", "object");
        doSchema.putArray("required").add("plan_id").add("link");
        doSchema.put("additionalProperties", false);
        return result;
    }

    private void call(Connection connection, JsonNode id, JsonNode params) {
        var name = params.path("name").asText();
        var arguments = params.path("arguments").isObject() ? params.path("arguments") : JSON.createObjectNode();
        var token = params.path("_meta").get("progressToken");
        var fields = connection.identity();
        fields.put("tool", name);
        fields.put("link", arguments.path("link").asText(""));
        event("call", fields);
        var outcome = switch (name) {
            case "pm_wait" -> pmWait(connection, token);
            case "pm_state" -> pmState(connection, arguments);
            case "pm_do" -> pmDo(connection, arguments);
            default -> Outcome.refused("unknown_tool");
        };
        var result = JSON.createObjectNode();
        var content = result.putArray("content").addObject();
        content.put("type", TEXT);
        content.put(TEXT, outcome.text());
        result.put("isError", outcome.error());
        try {
            reply(connection, id, result);
        } catch (IOException e) {
            var failed = connection.identity();
            failed.put("tool", name);
            failed.put("detail", String.valueOf(e.getMessage()));
            event("reply_failed", failed);
        }
    }

    // --- the three tools ---

    /**
     * A {@code pm_wait}: held up to the bounded wait. A wait the stopping stub ends ({@link #close()}, also the stop of
     * a restart) is refused {@value #STUB_STOPPED}, a wait of a fenced generation {@code generation_fenced}. Before
     * 2026-10-22 both were refused {@code generation_fenced}, so the stub log of a restart named a held wait of a
     * generation that was not fenced as fenced (Q7 smoke pass, run
     * {@code Q7-claude-fresh-question_apply-answer_he-106-002-aa970d29}: {@code stub_restart}, {@code stub_stopped},
     * then {@code refused} {@code generation_fenced} for {@code w1} generation 1, which reconnected with
     * {@code fenced: false}).
     */
    private Outcome pmWait(Connection connection, JsonNode token) {
        var key = connection.key();
        lock.lock();
        try {
            if (closed) {
                return refused(connection, STUB_STOPPED);
            }
            if (fenced(connection)) {
                return refused(connection, "generation_fenced");
            }
            int sequence = waits.merge(key, 1, Integer::sum);
            changed.signalAll();
            event("wait_started", connection.identity());
            var deadline = System.nanoTime() + settings.window().toNanos();
            var nextProgress = System.nanoTime() + settings.progressEvery().toNanos();
            var progress = 0;
            while (true) {
                if (closed) {
                    return refused(connection, STUB_STOPPED);
                }
                if (fenced(connection)) {
                    return refused(connection, "generation_fenced");
                }
                if (!Objects.equals(waits.get(key), sequence)) {
                    waitAgain(connection, "superseded");
                    return Outcome.of(WAIT_AGAIN);
                }
                if (ended.contains(key)) {
                    event("wait_end", connection.identity());
                    return Outcome.of(WAIT_END);
                }
                var delivery = deliverable(connection);
                if (delivery.isPresent()) {
                    return delivery.get();
                }
                var now = System.nanoTime();
                if (now >= deadline) {
                    waitAgain(connection, "window");
                    return Outcome.of(WAIT_AGAIN);
                }
                if (token != null && now >= nextProgress) {
                    progress++;
                    progress(connection, token, progress);
                    nextProgress = now + settings.progressEvery().toNanos();
                }
                var until = token == null ? deadline : Math.min(deadline, nextProgress);
                changed.awaitNanos(until - now);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.of(WAIT_AGAIN);
        } finally {
            lock.unlock();
        }
    }

    private void waitAgain(Connection connection, String reason) {
        var fields = connection.identity();
        fields.put(REASON, reason);
        event("wait_again", fields);
    }

    private void progress(Connection connection, JsonNode token, int progress) {
        var notification = JSON.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/progress");
        var params = notification.putObject("params");
        params.set("progressToken", token);
        params.put("progress", progress);
        params.put("message", "waiting");
        try {
            connection.send(notification);
        } catch (IOException e) {
            var fields = connection.identity();
            fields.put("detail", String.valueOf(e.getMessage()));
            event("progress_failed", fields);
        }
    }

    private Optional<Outcome> deliverable(Connection connection) {
        var key = connection.key();
        var holdsLease = false;
        for (var task : tasks.values()) {
            if (!task.leasedTo(key)) {
                continue;
            }
            holdsLease = true;
            if (task.answerReady) {
                task.answerReady = false;
                task.answerAckPending = true;
                task.state = STATE_ANSWER;
                task.offeredAt = System.nanoTime();
                var fields = connection.identity();
                fields.put(TASK, task.spec.taskId());
                fields.put(DECISION, task.openDecision);
                fields.put("resumed", false);
                event("answer_delivered", fields);
                return Optional.of(Outcome.of(render(task, null)));
            }
            if (task.consultationReady) {
                task.consultationReady = false;
                task.state = activeState(task.spec.kind());
                var fields = connection.identity();
                fields.put(TASK, task.spec.taskId());
                event("consultation_delivered", fields);
                return Optional.of(Outcome.of(render(task, null)));
            }
        }
        if (holdsLease) {
            return Optional.empty();
        }
        for (var task : tasks.values()) {
            if (STATE_QUEUED.equals(task.state) && task.spec.role().equals(connection.role)) {
                lease(task, connection.worker, connection.generation);
                var fields = connection.identity();
                fields.put(TASK, task.spec.taskId());
                fields.put("at_spawn", false);
                fields.put("resumed", false);
                event("offered", fields);
                return Optional.of(Outcome.of(render(task, null)));
            }
        }
        return Optional.empty();
    }

    private Outcome pmState(Connection connection, JsonNode arguments) {
        lock.lock();
        try {
            if (fenced(connection)) {
                return refused(connection, "generation_fenced");
            }
            var task = leased(connection, arguments.path("entity").asText(""));
            return task.map(value -> Outcome.of(render(value, null)))
                    .orElseGet(() -> refused(connection, "job_scope_mismatch"));
        } finally {
            lock.unlock();
        }
    }

    private Outcome pmDo(Connection connection, JsonNode arguments) {
        lock.lock();
        try {
            if (fenced(connection)) {
                return refused(connection, "generation_fenced");
            }
            var link = arguments.path("link").asText("");
            var entity = arguments.path("entity").asText("");
            var args = arguments.path("args").isObject() ? arguments.path("args") : JSON.createObjectNode();
            var leased = leased(connection, entity);
            if (leased.isEmpty()) {
                return staleOrMismatch(connection, entity, link);
            }
            var task = leased.get();
            if (!PLAN_ID.equals(arguments.path("plan_id").asText(""))) {
                return rejected(task, link, "unknown_plan", "plan_id is " + PLAN_ID);
            }
            var offered = offeredLinks(task);
            if (!offered.contains(link)) {
                var fields = connection.identity();
                fields.put(TASK, task.spec.taskId());
                fields.put("link", link);
                fields.put("state", task.state);
                event("link_refused", fields);
                return rejected(task, link, "link_not_offered", "offered: " + String.join(", ", offered));
            }
            var outcome = follow(task, link, args);
            changed.signalAll();
            return outcome;
        } finally {
            lock.unlock();
        }
    }

    private Outcome follow(Task task, String link, JsonNode args) {
        if ("ack".equals(link)) {
            return acknowledge(task);
        }
        if ("ask".equals(link) || link.startsWith("ask:")) {
            return ask(task, link, args);
        }
        if (link.startsWith("consult:")) {
            return consult(task, link, link.substring("consult:".length()), args);
        }
        if (link.startsWith("yield:")) {
            return yieldEnvelope(task);
        }
        if ("infeasible".equals(link) || link.startsWith("infeasible:")) {
            return infeasible(task, link, args);
        }
        if ("submit:vote".equals(link)) {
            return vote(task, link, args);
        }
        if ("submit:answer".equals(link)) {
            return consultationAnswer(task, link, args);
        }
        return submit(task, link, args);
    }

    private Outcome staleOrMismatch(Connection connection, String entity, String link) {
        var key = connection.key();
        for (var task : tasks.values()) {
            if (task.closed && key.equals(task.lastHolder) && task.spec.entity().equals(entity)) {
                var fields = connection.identity();
                fields.put(TASK, task.spec.taskId());
                fields.put("link", link);
                event("stale_entity", fields);
                return new Outcome(render(task, rejection(link, "stale_entity", "the task is closed")), true);
            }
        }
        return refused(connection, "job_scope_mismatch");
    }

    // --- links ---

    private Outcome acknowledge(Task task) {
        var answer = task.answerAckPending;
        task.answerAckPending = false;
        task.state = activeState(task.spec.kind());
        var fields = identity(task.worker, task.generation);
        fields.put(TASK, task.spec.taskId());
        fields.put("kind", answer ? "answer" : "offer");
        fields.put("after_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - task.offeredAt));
        if (answer) {
            fields.put(DECISION, task.openDecision);
        }
        event("acknowledged", fields);
        return Outcome.of(render(task, null));
    }

    private Outcome ask(Task task, String link, JsonNode args) {
        var errors = new ArrayList<String>();
        validateDecision(args.get(DECISION), errors);
        if (!errors.isEmpty()) {
            var fields = identity(task.worker, task.generation);
            fields.put(TASK, task.spec.taskId());
            fields.put("link", link);
            fields.put("detail", String.join("; ", errors));
            event("ask_refused", fields);
            return rejected(task, link, INVALID_ARGS, String.join("; ", errors));
        }
        var planTaskId = task.spec.kind() == Kind.ENVELOPE ? task.planTask().id() : null;
        var decision = createDecision(task, planTaskId, (ObjectNode) args.get(DECISION));
        task.openDecision = decision.id;
        var fields = identity(task.worker, task.generation);
        fields.put(TASK, task.spec.taskId());
        fields.put(DECISION, decision.id);
        fields.put("plan_task_id", planTaskId == null ? "" : planTaskId);
        fields.put("form", task.spec.form().name());
        fields.put("question_ids", decision.questionIds);
        fields.put("question_texts", questionTexts(decision));
        event("asked", fields);
        switch (task.spec.form()) {
            case HOLD -> task.state = awaitingState(task.spec.kind());
            case RESUME -> task.state = STATE_SUSPENDED;
            case FALLBACK -> {
                if (task.spec.kind() == Kind.ENVELOPE) {
                    event("yielded", Map.of(TASK, task.spec.taskId(), "yield_reason", "question"));
                }
                close(task, OUTCOME_ASKED);
            }
        }
        var rendered = render(task, null);
        adjudicate(decision);
        return Outcome.of(rendered);
    }

    private Outcome consult(Task task, String link, String role, JsonNode args) {
        var errors = new ArrayList<String>();
        requireText(args, "question", 1, 4000, "", errors);
        if (!errors.isEmpty()) {
            return rejected(task, link, INVALID_ARGS, String.join("; ", errors));
        }
        var consultationId = "C-%03d".formatted(++nextConsultation);
        var facts = JSON.createObjectNode();
        facts.put("question", args.path("question").asText());
        facts.put("asking_role", task.spec.role());
        var spec = new TaskSpec(consultationId, consultationId, role, Kind.CONSULTATION, consultationId,
                "Answer the consultation of role " + task.spec.role(), facts, List.of(), 0, false, List.of(),
                Form.FALLBACK, "answer:text[1..4000]");
        tasks.put(consultationId, new Task(spec));
        consultationAsker.put(consultationId, task.spec.taskId());
        task.state = STATE_CONSULTING;
        var entry = task.consultations.addObject();
        entry.put("consultation_id", consultationId);
        entry.put("role", role);
        entry.put("status", "open");
        entry.putNull("answer");
        var fields = identity(task.worker, task.generation);
        fields.put(TASK, task.spec.taskId());
        fields.put("consultation", consultationId);
        fields.put("role", role);
        fields.put("question", args.path("question").asText());
        event("consulted", fields);
        return Outcome.of(render(task, null));
    }

    private Outcome consultationAnswer(Task task, String link, JsonNode args) {
        var errors = new ArrayList<String>();
        requireText(args, "answer", 1, 4000, "", errors);
        if (!errors.isEmpty()) {
            return rejected(task, link, INVALID_ARGS, String.join("; ", errors));
        }
        close(task, OUTCOME_SUBMITTED);
        deliverConsultation(task.spec.taskId(), args.path("answer").asText(), "answered");
        return Outcome.of(render(task, null));
    }

    private Outcome yieldEnvelope(Task task) {
        close(task, OUTCOME_SUBMITTED);
        event("yielded", Map.of(TASK, task.spec.taskId(), "yield_reason", "context"));
        if (task.cursor < task.spec.planTasks().size()) {
            var next = task.spec.continuing(task.spec.entryKey() + "-y" + (++nextYield),
                    nextEnvelope(task.spec.entity()), task.cursor);
            var continuing = new Task(next);
            continuing.answers = answersFor(next.entryKey());
            tasks.put(next.taskId(), continuing);
            event("queued", Map.of(TASK, next.taskId(), "role", next.role(), "entity", next.entity()));
        }
        return Outcome.of(render(task, null));
    }

    private Outcome infeasible(Task task, String link, JsonNode args) {
        var errors = new ArrayList<String>();
        requireText(args, "evidence", 1, 2000, "", errors);
        if (!errors.isEmpty()) {
            return rejected(task, link, INVALID_ARGS, String.join("; ", errors));
        }
        close(task, OUTCOME_INFEASIBLE);
        var fields = identity(task.lastWorker(), task.generation);
        fields.put(TASK, task.spec.taskId());
        fields.put("link", link);
        fields.put("evidence", args.path("evidence").asText());
        event("infeasible", fields);
        if (task.spec.kind() == Kind.VOTE) {
            settleVote(voteDecision.get(task.spec.taskId()), task.spec.taskId());
        } else if (task.spec.kind() == Kind.CONSULTATION) {
            deliverConsultation(task.spec.taskId(), null, OUTCOME_INFEASIBLE);
        }
        return Outcome.of(render(task, null));
    }

    private Outcome submit(Task task, String link, JsonNode args) {
        var free = task.spec.kind() == Kind.ENVELOPE ? task.planTask().submitFree() : task.spec.submitFree();
        var errors = new ArrayList<String>();
        validateFree(free, args, errors);
        checkConsultationReferences(task, args, errors);
        var fields = identity(task.worker, task.generation);
        fields.put(TASK, task.spec.taskId());
        fields.put("link", link);
        if (!errors.isEmpty()) {
            fields.put("detail", String.join("; ", errors));
            event("submit_refused", fields);
            return rejected(task, link, INVALID_ARGS, String.join("; ", errors));
        }
        fields.put("plan_task_id", task.spec.kind() == Kind.ENVELOPE ? task.planTask().id() : "");
        fields.put("args", args);
        event("submitted", fields);
        if (task.spec.kind() == Kind.ENVELOPE) {
            task.cursor++;
            if (task.cursor >= task.spec.planTasks().size()) {
                close(task, OUTCOME_SUBMITTED);
            }
        } else {
            close(task, OUTCOME_SUBMITTED);
        }
        return Outcome.of(render(task, null));
    }

    private Outcome vote(Task task, String link, JsonNode args) {
        var decision = decisions.get(voteDecision.get(task.spec.taskId()));
        var errors = new ArrayList<String>();
        validateVote(decision, task.spec.role(), args.get(ANSWERS), errors);
        if (!errors.isEmpty()) {
            var fields = identity(task.worker, task.generation);
            fields.put(TASK, task.spec.taskId());
            fields.put("detail", String.join("; ", errors));
            event("vote_refused", fields);
            return rejected(task, link, INVALID_ARGS, String.join("; ", errors));
        }
        decision.votes.set(task.spec.role(), args.get(ANSWERS).deepCopy());
        close(task, OUTCOME_SUBMITTED);
        var fields = identity(task.lastWorker(), task.generation);
        fields.put(TASK, task.spec.taskId());
        fields.put(DECISION, decision.id);
        fields.put("role", task.spec.role());
        fields.put(ANSWERS, args.get(ANSWERS));
        event("vote_submitted", fields);
        var rendered = render(task, null);
        settleVote(decision.id, task.spec.taskId());
        return Outcome.of(rendered);
    }

    // --- decisions ---

    private Decision createDecision(Task task, String planTaskId, ObjectNode envelope) {
        var ids = new ArrayList<String>();
        for (var ignored : envelope.get("parts")) {
            ids.add("Q-%03d".formatted(++nextQuestion));
        }
        var spec = task.spec.continuing(task.spec.taskId(), task.spec.entity(), task.cursor);
        var decision = new Decision(ids.getFirst(), task.spec.taskId(), planTaskId, task.spec.entryKey(),
                envelope.deepCopy(), spec, ids);
        decisions.put(decision.id, decision);
        var line = JSON.createObjectNode();
        line.put("line", OUTCOME_ASKED);
        line.put("decision_id", decision.id);
        line.set("question_ids", JSON.valueToTree(ids));
        line.put("task_id", decision.taskId);
        if (planTaskId == null) {
            line.putNull("plan_task_id");
        } else {
            line.put("plan_task_id", planTaskId);
        }
        line.put("entry_key", decision.entryKey);
        line.put(WORKER, task.worker);
        line.put(GENERATION, task.generation);
        line.set("envelope", decision.envelope);
        line.set("spec", JSON.valueToTree(spec));
        writeRecord(line);
        return decision;
    }

    private void adjudicate(Decision decision) {
        var roles = new LinkedHashSet<String>();
        for (var part : decision.parts()) {
            var quorum = settings.quorums().get(part.path("decision_kind").asText());
            if (quorum == null) {
                deliverToOperator(decision, "kind_ask");
                return;
            }
            roles.addAll(quorum.roles());
        }
        for (var role : roles) {
            var entity = VOTE_PREFIX + decision.id + "-" + role;
            var facts = JSON.createObjectNode();
            facts.set("questions", blindQuestions(decision, role));
            var spec = new TaskSpec(entity, entity, role, Kind.VOTE, entity, "Vote on the questions of "
                    + decision.id, facts, List.of(), 0, false, List.of(), Form.FALLBACK,
                    "answers:list<schema[vote-answer]>");
            tasks.put(entity, new Task(spec));
            voteDecision.put(entity, decision.id);
            decision.pendingVotes.add(entity);
            event("queued", Map.of(TASK, entity, "role", role, "entity", entity));
        }
        event("adjudication_started", Map.of(DECISION, decision.id, "roles", List.copyOf(roles)));
    }

    /** The questions of a vote task: no recommendation, options sorted by {@code branch_key}. */
    private ArrayNode blindQuestions(Decision decision, String role) {
        var questions = JSON.createArrayNode();
        for (var index = 0; index < decision.questionIds.size(); index++) {
            var part = decision.parts().get(index);
            var quorum = settings.quorums().get(part.path("decision_kind").asText());
            if (quorum == null || !quorum.roles().contains(role)) {
                continue;
            }
            var question = questions.addObject();
            question.put(QUESTION_ID, decision.questionIds.get(index));
            question.put("question_text", part.path("question_text").asText());
            question.put("decision_kind", part.path("decision_kind").asText());
            question.put("answer_type", part.path("answer_type").asText());
            var sorted = new ArrayList<JsonNode>();
            part.path(OPTIONS).forEach(sorted::add);
            sorted.sort(Comparator.comparing(option -> option.path(BRANCH).asText()));
            var options = question.putArray(OPTIONS);
            for (var option : sorted) {
                var copy = options.addObject();
                for (var field : List.of(BRANCH, "label", "description", "consequences")) {
                    copy.put(field, option.path(field).asText());
                }
            }
        }
        return questions;
    }

    private void settleVote(String decisionId, String voteTask) {
        var decision = decisions.get(decisionId);
        if (decision == null) {
            return;
        }
        decision.pendingVotes.remove(voteTask);
        if (!decision.pendingVotes.isEmpty()) {
            return;
        }
        var reason = decisionRule(decision);
        var line = JSON.createObjectNode();
        line.put("line", "adjudicated");
        line.put("decision_id", decision.id);
        line.put("outcome", reason == null ? "decided" : "escalated");
        if (reason == null) {
            line.put(REASON, "agreement");
        } else {
            line.put(REASON, reason);
        }
        line.set("votes", decision.votes.deepCopy());
        writeRecord(line);
        event("adjudicated", Map.of(DECISION, decision.id, "outcome", reason == null ? "decided" : "escalated",
                REASON, reason == null ? "agreement" : reason));
        if (reason == null) {
            var parts = new ArrayList<PartAnswer>();
            for (var index = 0; index < decision.questionIds.size(); index++) {
                var questionId = decision.questionIds.get(index);
                parts.add(new PartAnswer(questionId, agreedChoice(decision, index, questionId), null, "chosen"));
            }
            recordAnswer(decision, parts, "quorum");
        } else {
            deliverToOperator(decision, reason);
        }
    }

    /** @return {@code null} when every question is decided, else the first escalation reason that holds */
    private String decisionRule(Decision decision) {
        var disagreement = false;
        var belowScore = false;
        for (var index = 0; index < decision.questionIds.size(); index++) {
            var quorum = settings.quorums().get(decision.parts().get(index).path("decision_kind").asText());
            String choice = null;
            for (var role : quorum.roles()) {
                var vote = voteOf(decision, role, decision.questionIds.get(index));
                if (vote == null) {
                    return "role_unavailable";
                }
                var chosen = vote.path("choice").toString();
                if (choice != null && !choice.equals(chosen)) {
                    disagreement = true;
                }
                choice = chosen;
                if (vote.path("confidence").asInt() < quorum.score()) {
                    belowScore = true;
                }
            }
        }
        if (disagreement) {
            return "disagreement";
        }
        return belowScore ? "below_score" : null;
    }

    private static JsonNode voteOf(Decision decision, String role, String questionId) {
        for (var answer : decision.votes.path(role)) {
            if (questionId.equals(answer.path(QUESTION_ID).asText())) {
                return answer;
            }
        }
        return null;
    }

    private String agreedChoice(Decision decision, int index, String questionId) {
        var quorum = settings.quorums().get(decision.parts().get(index).path("decision_kind").asText());
        var choice = Objects.requireNonNull(voteOf(decision, quorum.roles().getFirst(), questionId)).path("choice");
        if (choice.isArray()) {
            var keys = new ArrayList<String>();
            choice.forEach(key -> keys.add(key.asText()));
            return String.join("+", keys);
        }
        return choice.asText();
    }

    private void deliverToOperator(Decision decision, String reason) {
        decision.delivered = true;
        var line = JSON.createObjectNode();
        line.put("line", "delivered");
        line.put("decision_id", decision.id);
        line.put(REASON, reason);
        writeRecord(line);
        event("decision_delivered", Map.of(DECISION, decision.id, REASON, reason));
        operatorDecisions.add(decision.id);
    }

    private boolean validAnswer(Decision decision, List<PartAnswer> answers) {
        var byQuestion = new HashMap<String, PartAnswer>();
        for (var answer : answers) {
            if (!decision.questionIds.contains(answer.questionId())
                    || byQuestion.put(answer.questionId(), answer) != null) {
                return false;
            }
        }
        for (var index = 0; index < decision.questionIds.size(); index++) {
            var answer = byQuestion.get(decision.questionIds.get(index));
            if (answer == null || !validPart(decision.parts().get(index), answer)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validPart(JsonNode part, PartAnswer answer) {
        if (NONE_FITS.equals(answer.kind())) {
            return true;
        }
        if ("own".equals(answer.branchKey())) {
            return answer.text() != null && !answer.text().isBlank();
        }
        var keys = optionKeys(part);
        return switch (part.path("answer_type").asText()) {
            case "one" -> keys.contains(answer.branchKey());
            case "several" -> List.of(answer.branchKey().split("\\+")).stream().allMatch(keys::contains);
            default -> answer.text() != null && !answer.text().isBlank();
        };
    }

    private void recordAnswer(Decision decision, List<PartAnswer> answers, String channel) {
        var items = JSON.createArrayNode();
        for (var index = 0; index < decision.questionIds.size(); index++) {
            var questionId = decision.questionIds.get(index);
            var answer = answers.stream().filter(part -> part.questionId().equals(questionId)).findFirst()
                    .orElseThrow();
            var part = decision.parts().get(index);
            var item = items.addObject();
            item.put(QUESTION_ID, questionId);
            if (decision.planTaskId == null) {
                item.putNull("plan_task_id");
            } else {
                item.put("plan_task_id", decision.planTaskId);
            }
            item.put("question_text", part.path("question_text").asText());
            item.put(BRANCH, answer.branchKey());
            item.put("label", label(part, answer));
            if (answer.text() == null) {
                item.putNull(TEXT);
            } else {
                item.put(TEXT, answer.text());
            }
            item.put("kind", answer.kind());
        }
        decision.items = items;
        decision.answered = true;
        var line = JSON.createObjectNode();
        line.put("line", "answered");
        line.put("decision_id", decision.id);
        line.put("channel", channel);
        line.set("items", items.deepCopy());
        writeRecord(line);
        event("answer_recorded", Map.of(DECISION, decision.id, "channel", channel, "items", items));
        continueAfterAnswer(decision);
        changed.signalAll();
    }

    private static String label(JsonNode part, PartAnswer answer) {
        if (NONE_FITS.equals(answer.kind())) {
            return "none fits";
        }
        var labels = new ArrayList<String>();
        for (var key : answer.branchKey().split("\\+")) {
            for (var option : part.path(OPTIONS)) {
                if (key.equals(option.path(BRANCH).asText())) {
                    labels.add(option.path("label").asText());
                }
            }
        }
        return labels.isEmpty() ? answer.branchKey() : String.join(" + ", labels);
    }

    private void continueAfterAnswer(Decision decision) {
        var task = tasks.get(decision.taskId);
        if (task != null && !task.closed && decision.form == Form.HOLD && task.worker != null
                && task.state.endsWith("awaiting_answer")) {
            task.answers = answersFor(task.spec.entryKey());
            task.answerReady = true;
            event("answer_ready", Map.of(TASK, task.spec.taskId(), DECISION, decision.id));
            return;
        }
        if (task != null && !task.closed && decision.form == Form.RESUME && STATE_SUSPENDED.equals(task.state)) {
            task.answers = answersFor(task.spec.entryKey());
            task.state = STATE_RESUMABLE;
            event("resumable", Map.of(TASK, task.spec.taskId(), DECISION, decision.id));
            return;
        }
        continueInNewTask(decision);
    }

    /** The answer reaches a new task of the role exactly once, recorded by the {@code continued} line. */
    private void continueInNewTask(Decision decision) {
        if (decision.continued) {
            return;
        }
        decision.continued = true;
        var base = decision.spec;
        var entity = base.kind() == Kind.ENVELOPE ? nextEnvelope(base.entity()) : base.entity();
        var spec = base.continuing(base.entryKey() + "-" + decision.id, entity, base.cursor());
        var task = new Task(spec);
        task.answers = answersFor(spec.entryKey());
        tasks.put(spec.taskId(), task);
        var line = JSON.createObjectNode();
        line.put("line", "continued");
        line.put("decision_id", decision.id);
        line.put("task_id", spec.taskId());
        writeRecord(line);
        event("continued", Map.of(DECISION, decision.id, TASK, spec.taskId(), "role", spec.role(), "entity",
                entity));
    }

    private ArrayNode answersFor(String entryKey) {
        var answers = JSON.createArrayNode();
        for (var decision : decisions.values()) {
            if (decision.answered && decision.entryKey.equals(entryKey)) {
                decision.items.forEach(item -> answers.add(item.deepCopy()));
            }
        }
        return answers;
    }

    private long asksIn(String entryKey) {
        return decisions.values().stream().filter(decision -> decision.planTaskId == null)
                .filter(decision -> decision.entryKey.equals(entryKey)).count();
    }

    private long asksFor(String entryKey, String planTaskId) {
        return decisions.values().stream().filter(decision -> planTaskId.equals(decision.planTaskId))
                .filter(decision -> decision.entryKey.equals(entryKey)).count();
    }

    private void deliverConsultation(String consultationId, String answer, String status) {
        var asker = tasks.get(consultationAsker.get(consultationId));
        if (asker == null || asker.closed || asker.worker == null) {
            event("consultation_undeliverable", Map.of("consultation", consultationId));
            return;
        }
        for (var entry : asker.consultations) {
            if (consultationId.equals(entry.path("consultation_id").asText())) {
                ((ObjectNode) entry).put("status", status);
                if (answer == null) {
                    ((ObjectNode) entry).putNull("answer");
                } else {
                    ((ObjectNode) entry).put("answer", answer);
                }
            }
        }
        asker.deliveredConsultations.add(consultationId);
        asker.consultationReady = true;
        event("consultation_answered", Map.of("consultation", consultationId, TASK, asker.spec.taskId(), "status",
                status));
        changed.signalAll();
    }

    // --- leases ---

    private void advanceGeneration(String worker, int generation) {
        var current = generations.get(worker);
        if (current == null || generation > current) {
            generations.put(worker, generation);
            if (current != null) {
                event("fenced", Map.of(WORKER, worker, GENERATION, current, "by", generation));
                lost(worker, current, "fenced");
            }
        }
    }

    private boolean fenced(Connection connection) {
        var current = generations.get(connection.worker);
        return current != null && connection.generation < current;
    }

    private void lease(Task task, String worker, int generation) {
        task.worker = worker;
        task.generation = generation;
        task.lastHolder = worker + "#" + generation;
        task.state = STATE_OFFERED;
        task.offeredAt = System.nanoTime();
    }

    private void release(Task task, String reason) {
        var fields = identity(task.worker, task.generation);
        fields.put(TASK, task.spec.taskId());
        fields.put(REASON, reason);
        fields.put("state", task.state);
        task.worker = null;
        if (STATE_SUSPENDED.equals(task.state) || STATE_RESUMABLE.equals(task.state)) {
            event("released", fields);
            return;
        }
        if (task.spec.kind() == Kind.VOTE) {
            close(task, OUTCOME_LOST);
            event(OUTCOME_LOST, fields);
            settleVote(voteDecision.get(task.spec.taskId()), task.spec.taskId());
            return;
        }
        var decision = task.openDecision == null ? null : decisions.get(task.openDecision);
        if (task.answerAckPending && decision != null && decision.form == Form.RESUME && task.sessionStored) {
            task.answerAckPending = false;
            task.state = STATE_RESUMABLE;
            event("resumable", fields);
            return;
        }
        if (task.answerReady || task.answerAckPending || task.state.endsWith("awaiting_answer")) {
            close(task, OUTCOME_LOST);
            event(OUTCOME_LOST, fields);
            if (decision != null && decision.answered) {
                continueInNewTask(decision);
            }
            return;
        }
        task.state = STATE_QUEUED;
        task.answerAckPending = false;
        event("released", fields);
    }

    private Optional<Task> leased(Connection connection, String entity) {
        var key = connection.key();
        Task only = null;
        var count = 0;
        for (var task : tasks.values()) {
            if (task.leasedTo(key)) {
                if (task.spec.entity().equals(entity)) {
                    return Optional.of(task);
                }
                only = task;
                count++;
            }
        }
        return entity.isEmpty() && count == 1 ? Optional.of(only) : Optional.empty();
    }

    private void close(Task task, String outcome) {
        task.closed = true;
        task.outcome = outcome;
        task.state = switch (outcome) {
            case OUTCOME_SUBMITTED -> switch (task.spec.kind()) {
                case ENVELOPE -> "envelope.completed";
                default -> "entity.completed";
            };
            default -> "task." + outcome;
        };
        var fields = identity(task.lastWorker(), task.generation);
        fields.put(TASK, task.spec.taskId());
        fields.put("outcome", outcome);
        event("closed", fields);
    }

    private static String activeState(Kind kind) {
        return switch (kind) {
            case ENVELOPE -> "envelope.task.active";
            default -> "entity.active";
        };
    }

    private static String awaitingState(Kind kind) {
        return kind == Kind.ENVELOPE ? "envelope.task.awaiting_answer" : "entity.awaiting_answer";
    }

    private static String nextEnvelope(String entity) {
        var matcher = ENVELOPE_ENTITY.matcher(entity);
        return matcher.matches() ? "envelope-" + (Integer.parseInt(matcher.group(1)) + 1) : entity;
    }

    private Outcome refused(Connection connection, String reason) {
        var fields = connection.identity();
        fields.put(REASON, reason);
        event("refused", fields);
        return Outcome.refused(reason);
    }

    private static Map<String, Object> identity(String worker, int generation) {
        var identity = new LinkedHashMap<String, Object>();
        identity.put(WORKER, worker == null ? "" : worker);
        identity.put(GENERATION, generation);
        return identity;
    }

    // --- representations ---

    private List<String> offeredLinks(Task task) {
        var names = new ArrayList<String>();
        links(task).forEach(row -> names.add(row.path("link").asText()));
        return names;
    }

    private ArrayNode links(Task task) {
        var links = JSON.createArrayNode();
        if (STATE_OFFERED.equals(task.state) || STATE_ANSWER.equals(task.state)) {
            link(links, "next", "ack", "noop", "");
            return links;
        }
        if (task.closed || !task.state.equals(activeState(task.spec.kind()))) {
            return links;
        }
        var bound = settings.questionBound();
        switch (task.spec.kind()) {
            case ENTITY -> {
                var free = task.spec.consultRoles().isEmpty() ? task.spec.submitFree()
                        : task.spec.submitFree() + ",consultation_ids:list<text[1..64]>";
                link(links, "submit", "submit", "submit", free);
                if (task.spec.ask() && asksIn(task.spec.entryKey()) < bound) {
                    link(links, "submit", "ask", "submit", ENVELOPE_LINK);
                }
                for (var role : task.spec.consultRoles()) {
                    link(links, "consult", "consult:" + role, "submit", "question:text[1..4000]");
                }
                link(links, "submit", "infeasible", "submit", "evidence:text[1..2000]");
            }
            case ENVELOPE -> {
                var planTask = task.planTask();
                link(links, "next", "submit:" + planTask.id(), "submit", planTask.submitFree());
                if (task.spec.ask() && asksFor(task.spec.entryKey(), planTask.id()) < bound) {
                    link(links, "submit", "ask:" + planTask.id(), "submit", ENVELOPE_LINK);
                }
                link(links, "alt", "yield:" + task.spec.entity(), "noop", "");
                link(links, "submit", "infeasible:" + planTask.id(), "submit", "evidence:text[1..2000]");
            }
            case VOTE -> {
                link(links, "submit", "submit:vote", "submit", task.spec.submitFree());
                link(links, "submit", "infeasible", "submit", "evidence:text[1..2000]");
            }
            case CONSULTATION -> {
                link(links, "submit", "submit:answer", "submit", task.spec.submitFree());
                link(links, "submit", "infeasible", "submit", "evidence:text[1..2000]");
            }
        }
        return links;
    }

    private static void link(ArrayNode links, String rel, String link, String form, String free) {
        var row = links.addObject();
        row.put("rel", rel);
        row.put("link", link);
        row.put("form", form);
        row.put("free", free);
    }

    private Outcome rejected(Task task, String link, String reason, String detail) {
        return Outcome.of(render(task, rejection(link, reason, detail)));
    }

    private static ObjectNode rejection(String link, String reason, String detail) {
        var rejected = JSON.createObjectNode();
        rejected.put("link", link);
        rejected.put(REASON, reason);
        rejected.put("detail", detail);
        return rejected;
    }

    private String render(Task task, ObjectNode rejected) {
        var representation = JSON.createObjectNode();
        representation.put("scope", "entity");
        representation.put("plan_id", PLAN_ID);
        representation.put("entity", task.spec.entity());
        representation.put("state", task.state);
        representation.put("phase", "execute");
        representation.put("summary", summary(task));
        if (rejected != null) {
            representation.set("rejected", rejected);
        }
        var facts = task.spec.facts().deepCopy();
        facts.remove("plan_tasks");
        if (task.spec.kind() == Kind.ENVELOPE && task.cursor < task.spec.planTasks().size()) {
            facts.put("task_id", task.planTask().id());
            facts.put(TASK, task.planTask().text());
        }
        if (!task.answers.isEmpty()) {
            facts.set(ANSWERS, task.answers);
        }
        if (!task.consultations.isEmpty()) {
            facts.set("consultations", task.consultations);
        }
        representation.set("facts", facts);
        representation.set("links", links(task));
        return toon(representation);
    }

    private static String summary(Task task) {
        if (task.closed) {
            return switch (task.outcome) {
                case OUTCOME_ASKED -> "Question recorded; this task has ended. End your turn, or call pm_wait if "
                        + "you take task after task.";
                case OUTCOME_SUBMITTED -> "Task completed. End your turn, or call pm_wait if you take task after task.";
                default -> "Task ended " + task.outcome + ".";
            };
        }
        return switch (task.state) {
            case STATE_OFFERED -> "Task offered: acknowledge it with the link ack.";
            case STATE_ANSWER -> "The answer to your question is in facts.answers: acknowledge it with ack, then "
                    + "continue.";
            case STATE_SUSPENDED -> "Question recorded. End your turn now; your session is resumed with the answer.";
            case STATE_RESUMABLE -> "The answer is there; the task waits for its resumed session.";
            case STATE_CONSULTING -> "Consultation sent. Call pm_wait: it returns this task with the answer.";
            case "entity.awaiting_answer", "envelope.task.awaiting_answer" ->
                "Question recorded. Call pm_wait: it returns this task with the answer.";
            default -> task.spec.summary();
        };
    }

    // --- validation ---

    /**
     * Checks a decision envelope against the named schemas {@code decision-envelope} and {@code question-envelope}.
     *
     * @param decision the model-supplied value
     * @param errors   receives one message per violation
     */
    static void validateDecision(JsonNode decision, List<String> errors) {
        if (decision == null || !decision.isObject()) {
            errors.add("decision: an object is required");
            return;
        }
        unknownFields(decision, DECISION_FIELDS, "decision", errors);
        requireText(decision, "title", 3, 80, "", errors);
        requireText(decision, "header", 3, 200, "", errors);
        requireText(decision, "description", 10, 4000, "", errors);
        var evidence = decision.get("evidence");
        if (evidence == null || !evidence.isArray() || evidence.size() > 20) {
            errors.add("evidence: an array of at most 20 pm:// URIs is required");
        } else {
            evidence.forEach(uri -> {
                if (!uri.isTextual() || !uri.asText().startsWith("pm://") || uri.asText().length() > 512) {
                    errors.add("evidence: every item must be a pm:// URI");
                }
            });
        }
        requireEnum(decision, "blocks", Set.of("plan", TASK, "nothing"), "", errors);
        requireEnum(decision, "reversibility", Set.of("reversible", "costly", "irreversible"), "", errors);
        var parts = decision.get("parts");
        if (parts == null || !parts.isArray() || parts.isEmpty() || parts.size() > 6) {
            errors.add("parts: one to six parts are required");
            return;
        }
        for (var index = 0; index < parts.size(); index++) {
            validatePart(parts.get(index), "parts[" + index + "].", errors);
        }
    }

    private static void validatePart(JsonNode part, String prefix, List<String> errors) {
        if (!part.isObject()) {
            errors.add(prefix + ": an object is required");
            return;
        }
        unknownFields(part, PART_FIELDS, prefix, errors);
        requireText(part, "question_text", 10, 500, prefix, errors);
        requireEnum(part, "decision_kind", DECISION_KINDS, prefix, errors);
        requireEnum(part, "answer_type", Set.of("one", "several", "value"), prefix, errors);
        for (var flag : List.of("own_words", "note", "delegation")) {
            if (!part.path(flag).isBoolean()) {
                errors.add(prefix + flag + ": a boolean is required");
            }
        }
        var options = part.get(OPTIONS);
        var answerType = part.path("answer_type").asText();
        var value = "value".equals(answerType);
        if (options == null || !options.isArray()) {
            errors.add(prefix + "options: an array is required");
            return;
        }
        if (value ? !options.isEmpty() : options.size() < 2 || options.size() > 5) {
            errors.add(prefix + "options: " + (value ? "none for value" : "two to five are required"));
        }
        var keys = new HashSet<String>();
        for (var index = 0; index < options.size(); index++) {
            var option = options.get(index);
            var optionPrefix = prefix + "options[" + index + "].";
            unknownFields(option, OPTION_FIELDS, optionPrefix, errors);
            var key = option.path(BRANCH).asText("");
            if (!BRANCH_KEY.matcher(key).matches() || !keys.add(key)) {
                errors.add(optionPrefix + "branch_key: a distinct key matching ^[a-z][a-z0-9_]{1,31}$ is required");
            }
            requireText(option, "label", 2, 80, optionPrefix, errors);
            requireText(option, "description", 5, 240, optionPrefix, errors);
            requireText(option, "consequences", 5, 500, optionPrefix, errors);
        }
        var recommendation = part.get("recommendation");
        if (value) {
            if (recommendation != null && !recommendation.isNull()) {
                errors.add(prefix + "recommendation: null for value");
            }
            return;
        }
        if (recommendation == null || !recommendation.isObject()) {
            errors.add(prefix + "recommendation: exactly one recommended option is required");
            return;
        }
        if (!keys.contains(recommendation.path(BRANCH).asText())) {
            errors.add(prefix + "recommendation.branch_key: must name one of the options");
        }
        var confidence = recommendation.get("confidence");
        if (confidence == null || !confidence.isInt() || confidence.asInt() < 0 || confidence.asInt() > 100) {
            errors.add(prefix + "recommendation.confidence: an integer 0..100 is required");
        }
        requireText(recommendation, "rationale", 10, 500, prefix + "recommendation.", errors);
    }

    private static void unknownFields(JsonNode node, Set<String> allowed, String prefix, List<String> errors) {
        node.properties().forEach(entry -> {
            if (SERVER_OWNED.contains(entry.getKey())) {
                errors.add(prefix + entry.getKey() + ": a server-owned field");
            } else if (!allowed.contains(entry.getKey())) {
                errors.add(prefix + entry.getKey() + ": not a field of the schema");
            }
        });
    }

    private static void requireText(JsonNode node, String field, int min, int max, String prefix,
            List<String> errors) {
        var value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().strip().length() < min
                || value.asText().length() > max) {
            errors.add(prefix + field + ": a text of " + min + " to " + max + " characters is required");
        }
    }

    private static void requireEnum(JsonNode node, String field, Set<String> values, String prefix,
            List<String> errors) {
        if (!values.contains(node.path(field).asText(""))) {
            errors.add(prefix + field + ": one of " + values.stream().sorted().toList() + " is required");
        }
    }

    private static Set<String> optionKeys(JsonNode part) {
        var keys = new HashSet<String>();
        part.path(OPTIONS).forEach(option -> keys.add(option.path(BRANCH).asText()));
        return keys;
    }

    /**
     * Checks the arguments of a submit against its free parameters: a {@code text[min..max]} with a minimum of at
     * least one, an {@code int[min..max]}, an {@code enum[a|b]}, a list and a schema are required.
     */
    static void validateFree(String free, JsonNode args, List<String> errors) {
        if (free.isBlank()) {
            return;
        }
        for (var declaration : free.split(",")) {
            var matcher = FREE.matcher(declaration.strip());
            if (!matcher.matches()) {
                continue;
            }
            var name = matcher.group(1);
            var type = matcher.group(2);
            var bounds = matcher.group(3);
            var value = args.get(name);
            switch (type) {
                case TEXT -> {
                    var range = RANGE.matcher(bounds);
                    var min = range.matches() ? Integer.parseInt(range.group(1)) : 1;
                    var max = range.matches() ? Integer.parseInt(range.group(2)) : Integer.MAX_VALUE;
                    if (min > 0 || value != null) {
                        requireText(args, name, min, max, "", errors);
                    }
                }
                case "int" -> {
                    var range = RANGE.matcher(bounds);
                    if (value == null || !value.isInt() || range.matches()
                            && (value.asInt() < Integer.parseInt(range.group(1))
                            || value.asInt() > Integer.parseInt(range.group(2)))) {
                        errors.add(name + ": an integer " + bounds + " is required");
                    }
                }
                case "enum" -> {
                    var values = Set.of(bounds.replace("[", "").replace("]", "").split("\\|"));
                    if (value == null || !values.contains(value.asText())) {
                        errors.add(name + ": one of " + bounds + " is required");
                    }
                }
                default -> {
                    if (value == null || value.isNull() || "list".equals(type) && !value.isArray()) {
                        errors.add(name + ": a " + type + " is required");
                    }
                }
            }
        }
    }

    private static void checkConsultationReferences(Task task, JsonNode args, List<String> errors) {
        if (task.deliveredConsultations.isEmpty()) {
            return;
        }
        var named = new HashSet<String>();
        args.path("consultation_ids").forEach(id -> named.add(id.asText()));
        if (!named.equals(task.deliveredConsultations)) {
            errors.add("consultation_ids: must list exactly the delivered consultations "
                    + task.deliveredConsultations);
        }
    }

    private static void validateVote(Decision decision, String role, JsonNode answers, List<String> errors) {
        if (answers == null || !answers.isArray()) {
            errors.add("answers: a list is required");
            return;
        }
        var expected = new HashSet<String>();
        var parts = new HashMap<String, JsonNode>();
        for (var index = 0; index < decision.questionIds.size(); index++) {
            expected.add(decision.questionIds.get(index));
            parts.put(decision.questionIds.get(index), decision.parts().get(index));
        }
        var seen = new HashSet<String>();
        for (var answer : answers) {
            var questionId = answer.path(QUESTION_ID).asText("");
            if (!expected.contains(questionId) || !seen.add(questionId)) {
                errors.add("answers: unknown or repeated question " + questionId);
                continue;
            }
            var keys = optionKeys(parts.get(questionId));
            var choice = answer.get("choice");
            var valid = choice != null && (choice.isTextual() && (keys.contains(choice.asText()) || keys.isEmpty())
                    || choice.isArray() && !choice.isEmpty());
            if (choice != null && choice.isArray()) {
                for (var key : choice) {
                    valid &= keys.contains(key.asText());
                }
            }
            if (!valid) {
                errors.add("answers: unknown choice for " + questionId);
            }
            var confidence = answer.get("confidence");
            if (confidence == null || !confidence.isInt() || confidence.asInt() < 0 || confidence.asInt() > 100) {
                errors.add("answers: confidence 0..100 is required for " + questionId);
            }
            requireText(answer, "rationale", 10, 500, "answers." + questionId + ".", errors);
        }
        if (!seen.containsAll(expected)) {
            errors.add("answers: one item per question is required for role " + role);
        }
    }

    private static List<String> questionTexts(Decision decision) {
        var texts = new ArrayList<String>();
        decision.parts().forEach(part -> texts.add(part.path("question_text").asText()));
        return texts;
    }

    // --- records ---

    private void writeRecord(ObjectNode line) {
        var stamped = JSON.createObjectNode();
        stamped.put("at", System.currentTimeMillis());
        stamped.setAll(line);
        append(records, stamped);
    }

    private void append(Path file, ObjectNode node) {
        synchronized (eventLock) {
            try {
                Files.writeString(file, JSON.writeValueAsString(node) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException("cannot append to " + file, e);
            }
        }
    }

    /**
     * Replays {@code questions.jsonl}: open records stay open, an answered one continues exactly once. An open,
     * unanswered decision is put back before the operator in every form, as the stub before the restart had put it: a
     * delivered one is queued again, an undelivered one is delivered with the reason {@code role_unavailable}. A
     * decision of the form {@code RESUME} that has not continued also rebuilds its task, suspended (or resumable when
     * answered) with its stored session, so the operator's answer after the restart makes it resumable. Before
     * 2026-10-23 an open {@code RESUME} decision was rebuilt but not queued, so the driver never saw it after a restart
     * and every restart run of R2 waited out its run limit.
     */
    private void replay() throws IOException {
        if (!Files.exists(records)) {
            return;
        }
        for (var line : Files.readAllLines(records, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            var node = JSON.readTree(line);
            var id = node.path("decision_id").asText();
            switch (node.path("line").asText()) {
                case OUTCOME_ASKED -> replayAsked(node);
                case "delivered" -> decisions.get(id).delivered = true;
                case "answered" -> {
                    var decision = decisions.get(id);
                    decision.answered = true;
                    decision.items = (ArrayNode) node.get("items");
                }
                case "continued" -> decisions.get(id).continued = true;
                default -> {
                    // adjudicated lines carry no state the replay needs
                }
            }
        }
        for (var decision : decisions.values()) {
            if (decision.form == Form.RESUME && !decision.continued) {
                var task = new Task(decision.spec);
                task.sessionStored = true;
                task.state = decision.answered ? STATE_RESUMABLE : STATE_SUSPENDED;
                task.openDecision = decision.id;
                task.answers = answersFor(decision.entryKey);
                tasks.put(decision.spec.taskId(), task);
                if (!decision.answered) {
                    requeueForOperator(decision);
                }
            } else if (decision.answered) {
                continueInNewTask(decision);
            } else {
                requeueForOperator(decision);
            }
        }
    }

    /** Puts an open, unanswered decision back before the operator after a restart. */
    private void requeueForOperator(Decision decision) {
        if (decision.delivered) {
            operatorDecisions.add(decision.id);
        } else {
            deliverToOperator(decision, "role_unavailable");
        }
    }

    private void replayAsked(JsonNode node) throws JsonProcessingException {
        var ids = new ArrayList<String>();
        node.path("question_ids").forEach(id -> ids.add(id.asText()));
        var spec = JSON.treeToValue(node.get("spec"), TaskSpec.class);
        var planTaskId = node.path("plan_task_id").isNull() ? null : node.path("plan_task_id").asText();
        var decision = new Decision(ids.getFirst(), node.path("task_id").asText(), planTaskId,
                node.path("entry_key").asText(), (ObjectNode) node.get("envelope"), spec, ids);
        decisions.put(decision.id, decision);
        for (var id : ids) {
            nextQuestion = Math.max(nextQuestion, Integer.parseInt(id.substring(2)));
        }
    }

    // --- TOON ---

    /**
     * Renders a representation as TOON: nested objects indented by two spaces, arrays of scalars inline, arrays of
     * objects with the same scalar fields as a table, other arrays as list items, an empty array as {@code []}.
     *
     * @param node the representation
     * @return the TOON text
     */
    static String toon(ObjectNode node) {
        var lines = new ArrayList<String>();
        object(node, "", lines);
        return String.join("\n", lines) + "\n";
    }

    private static void object(JsonNode node, String indent, List<String> lines) {
        node.properties().forEach(entry -> field(entry.getKey(), entry.getValue(), indent, lines));
    }

    private static void field(String name, JsonNode value, String indent, List<String> lines) {
        if (value.isObject()) {
            lines.add(indent + name + ":");
            object(value, indent + "  ", lines);
        } else if (value.isArray()) {
            array(name, value, indent, lines);
        } else {
            lines.add(indent + name + ": " + scalar(value));
        }
    }

    private static void array(String name, JsonNode array, String indent, List<String> lines) {
        var size = array.size();
        if (size == 0) {
            lines.add(indent + name + ": []");
            return;
        }
        var scalars = true;
        for (var item : array) {
            scalars &= item.isValueNode();
        }
        if (scalars) {
            var values = new ArrayList<String>();
            array.forEach(item -> values.add(scalar(item)));
            lines.add(indent + name + "[" + size + "]: " + String.join(",", values));
            return;
        }
        var header = tableHeader(array);
        if (header.isPresent()) {
            lines.add(indent + name + "[" + size + "]{" + String.join(",", header.get()) + "}:");
            for (var item : array) {
                var values = new ArrayList<String>();
                header.get().forEach(key -> values.add(scalar(item.get(key))));
                lines.add(indent + "  " + String.join(",", values));
            }
            return;
        }
        lines.add(indent + name + "[" + size + "]:");
        for (var item : array) {
            if (item.isObject()) {
                var itemLines = new ArrayList<String>();
                object(item, "", itemLines);
                for (var index = 0; index < itemLines.size(); index++) {
                    lines.add(indent + "  " + (index == 0 ? "- " : "  ") + itemLines.get(index));
                }
            } else {
                lines.add(indent + "  - " + scalar(item));
            }
        }
    }

    private static Optional<List<String>> tableHeader(JsonNode array) {
        List<String> header = null;
        for (var item : array) {
            if (!item.isObject()) {
                return Optional.empty();
            }
            var keys = new ArrayList<String>();
            for (var entry : item.properties()) {
                if (!entry.getValue().isValueNode()) {
                    return Optional.empty();
                }
                keys.add(entry.getKey());
            }
            if (header == null) {
                header = keys;
            } else if (!header.equals(keys)) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(header);
    }

    private static String scalar(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (!value.isTextual()) {
            return value.asText();
        }
        var text = value.asText();
        var plain = !text.isEmpty() && text.equals(text.strip()) && !text.startsWith("-")
                && !Set.of("true", "false", "null").contains(text) && !text.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")
                && text.chars().noneMatch(c -> ",:\"\\#[]{}\n\r\t".indexOf(c) >= 0);
        if (plain) {
            return text;
        }
        var escaped = new StringBuilder("\"");
        for (var c : text.toCharArray()) {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c);
            }
        }
        return escaped.append('"').toString();
    }

    // --- relay ---

    /**
     * @param socket   the stub's socket
     * @param worker   the worker
     * @param generation its generation
     * @param role     its role
     * @param harness  its harness
     * @param log      the relay's host-side log
     * @return the environment the relay reads its identity from, which the harness passes on
     */
    static Map<String, String> relayEnvironment(Path socket, String worker, int generation, String role,
            String harness, Path log) {
        var environment = new LinkedHashMap<String, String>();
        environment.put("PM_Q_SOCKET", socket.toString());
        environment.put("PM_Q_WORKER", worker);
        environment.put("PM_Q_GENERATION", Integer.toString(generation));
        environment.put("PM_Q_ROLE", role);
        environment.put("PM_Q_HARNESS", harness);
        environment.put("PM_Q_RELAY_LOG", log.toString());
        return environment;
    }

    /**
     * @return the relay command line: the JVM of this run with the test classes only (the relay needs the JDK alone)
     */
    static List<String> relayCommand() {
        try {
            var classes = Path.of(Relay.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            return List.of(java, "-cp", classes.toString(), Relay.class.getName());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot locate the test classes", e);
        }
    }

    /**
     * The worker's MCP server as its harness starts it: forwards newline-delimited JSON-RPC between the harness
     * ({@code stdin}/{@code stdout}) and the stub's socket, after a first line with the worker's identity, role and
     * generation, taken from its environment; logs every line host-side with its arrival time. It ends when either
     * side closes, so a stopped stub ends the worker's server.
     */
    static final class Relay {

        private Relay() {
        }

        /**
         * @param args unused; the relay reads its environment
         * @throws IOException if the socket cannot be reached or the log not written
         */
        public static void main(String[] args) throws IOException {
            var environment = System.getenv();
            var socket = Path.of(environment.get("PM_Q_SOCKET"));
            var log = Files.newBufferedWriter(Path.of(environment.get("PM_Q_RELAY_LOG")), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socket));
            var toStub = Channels.newOutputStream(channel);
            var hello = "{\"worker\":" + quote(environment.get("PM_Q_WORKER")) + ",\"generation\":"
                    + Integer.parseInt(environment.get("PM_Q_GENERATION")) + ",\"role\":"
                    + quote(environment.get("PM_Q_ROLE")) + ",\"harness\":" + quote(environment.get("PM_Q_HARNESS"))
                    + "}\n";
            toStub.write(hello.getBytes(StandardCharsets.UTF_8));
            toStub.flush();
            logLine(log, "connect", hello.strip());
            var toHost = new FileOutputStream(FileDescriptor.out);
            Thread.ofPlatform().start(() -> {
                pump(Channels.newInputStream(channel), toHost, log, "to_host");
                logLine(log, "stub_closed", "");
                Runtime.getRuntime().halt(3);
            });
            pump(System.in, toStub, log, "from_host");
            logLine(log, "host_closed", "");
            channel.close();
            Runtime.getRuntime().halt(0);
        }

        private static void pump(InputStream from, OutputStream to, Writer log, String direction) {
            try {
                var reader = new BufferedReader(new InputStreamReader(from, StandardCharsets.UTF_8));
                for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                    logLine(log, direction, line);
                    to.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    to.flush();
                }
            } catch (IOException e) {
                logLine(log, direction + "_failed", String.valueOf(e.getMessage()));
            }
        }

        private static void logLine(Writer log, String direction, String line) {
            synchronized (log) {
                try {
                    log.write("{\"at\":" + System.currentTimeMillis() + ",\"dir\":" + quote(direction)
                            + ",\"line\":" + quote(line) + "}\n");
                    log.flush();
                } catch (IOException e) {
                    throw new IllegalStateException("relay log not writable", e);
                }
            }
        }

        static String quote(String value) {
            var quoted = new StringBuilder("\"");
            for (var c : value.toCharArray()) {
                switch (c) {
                    case '"' -> quoted.append("\\\"");
                    case '\\' -> quoted.append("\\\\");
                    case '\n' -> quoted.append("\\n");
                    case '\r' -> quoted.append("\\r");
                    case '\t' -> quoted.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            quoted.append("\\u%04x".formatted((int) c));
                        } else {
                            quoted.append(c);
                        }
                    }
                }
            }
            return quoted.append('"').toString();
        }
    }
}
