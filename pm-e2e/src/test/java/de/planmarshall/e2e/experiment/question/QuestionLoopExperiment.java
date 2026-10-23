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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.experiment.question.QuestionDriver.Fault;
import de.planmarshall.e2e.experiment.question.QuestionDriver.Host;
import de.planmarshall.e2e.experiment.question.QuestionDriver.Launch;
import de.planmarshall.e2e.experiment.question.QuestionDriver.OpenCodeDataHome;
import de.planmarshall.e2e.experiment.question.QuestionDriver.RecoveryStep;
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
 * only), {@code pm.experiment.model.<host>} (default {@code claude-haiku-4-5} for Claude Code,
 * {@code opencode/gpt-5.4-nano} for OpenCode on the global data home, the harness default otherwise:
 * {@link #defaultModel(Host, OpenCodeDataHome)}), {@code pm.experiment.opencode.data-home} ({@code global}, the
 * default, or {@code worker}: {@link OpenCodeDataHome}), {@code pm.experiment.runs} (20), {@code pm.experiment.fixtures} (a limit on the fixtures per item, all
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
 * pass and never a timeout ({@link QuestionDriver#launchFailure()}). A worker whose relay connected but that exits
 * without a single call to the stub ends the run at once as {@code early_exit}, with the exit code and the first
 * harness line of its {@code stderr} or the last line of its output: never a pass, never a timeout and never counted as
 * asked ({@link QuestionDriver#earlyExit()}). A continuation generation, started after its worker asked, that
 * connects and exits without a call of its own ends the run at once as {@code continuation_early_exit} with the same
 * detail: the ask stays counted, and the run counts neither as {@code early_exit} nor as a timeout. A worker whose
 * harness answers with a quota or limit error
 * ({@link QuestionResults#QUOTA_PATTERN} in its own output or {@code stderr}) ends the run at once as
 * {@code quota_error}, before either of these and whether or not it called the stub
 * ({@link QuestionDriver#quotaError()}).
 * <p>
 * The answer of an ask is awaited alike in every run ({@link QuestionDriver#awaitAnswer}): a decision delivered to the
 * operator is answered on the operator's behalf after the run's hold; in a vote run of Q6 a set its quorum decided is
 * taken as answered at once, without an operator, and the task continues with the decided answer as after an
 * operator's answer (hold, resume, or the fallback task with {@code facts.answers}); a set the quorum escalates reaches
 * the operator and is answered so. The consult runs of Q6 have no quorum, and their questions take the operator's path.
 * When the quorum decides while the asker still holds {@code pm_wait}, the stub offers the continuing task to that
 * generation within the hold; once the asker has taken and closed it, the driver starts no new generation for it
 * ({@link QuestionDriver#continuedByHold(String)}): the run records the continuation as made by hold
 * ({@value QuestionDriver#CONTINUED_BY_HOLD}), counts it as continued and submitted as any continuation by hold, and
 * ends when its entry is done, a hold still kept being ended with {@code wait: end}. A decision that comes after the
 * asker exited leaves the continuation queued, and a new generation takes it as before.
 * Each vote task gets one voter, never restarted, and is never offered again: a voter that exits without a vote closes
 * its vote task {@code lost} at once, and the set escalates with {@code role_unavailable}, the decision rule of the
 * specification (a missing vote never counts as agreement). A voter that connected and exited without a single call is
 * recorded as {@value QuestionResults#VOTER_EARLY_EXIT} ({@link QuestionDriver#voterEarlyExits()}); it does not end the
 * run, which keeps its ask. Before a newer generation of a worker is launched into the worker directory its generations
 * share, the driver ends the older generations that still run, so that none reaches the stub under the newer
 * generation's identity ({@link QuestionDriver#SUPERSEDED_ENDED}).
 * <p>
 * Q7 measures the server-side loop of the fallback: the record open for its open time with no worker of the task
 * connected, a stub restart in it, and the answer delivered exactly once after it. Its fresh asker is therefore ended
 * by the driver at its silence limit after the ask if it has not ended its turn, before the open time starts
 * ({@link QuestionDriver#endAsker}, recorded as {@value QuestionDriver#ASKER_ENDED_BY_DRIVER}): a harness action, as
 * in the other fresh items, where an asker still running is ended once its silence limit has passed, at the latest by
 * the launch of the continuation. A model that does not end its turn after the ask is a finding of the run, not of
 * the server ({@link QuestionResults#limitOfQ7}).
 * <p>
 * R3 injects each fault where the criterion places it ({@link Fault}). The stop before the answer comes once the stub
 * shows the asker holding {@code pm_wait} after its ask ({@link QuestionDriver#stopWhileHolding}); an asker that never
 * holds is not stopped and its run says so. The call cut is made by the host, never simulated: the held call gets no
 * progress frame and the operator answers {@link QuestionDriver#CALL_CUT_MARGIN} after the host's cut, which is a
 * tool timeout of 60 s on Claude Code ({@code MCP_TOOL_TIMEOUT}) and OpenCode (the MCP server's {@code timeout}) and
 * Antigravity's fixed 180 s call limit, with the stub's window raised above it ({@link QuestionDriver#cutSetting});
 * the run records whether the logs show the cut ({@link QuestionDriver#awaitCallCut}). After a loss the driver reads
 * what the stub made of the task before it starts a generation ({@link QuestionDriver#recovery}): none for a task the
 * stub continued or offered again and a generation holds or closed, one leased on the stub's queued continuation, and
 * otherwise the step it took before (a resume again of a resumable task, the continuation of a held one).
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
 * <p>
 * OpenCode runs on the launch verified on 2026-10-21 with OpenCode 2.1.2 against the stub ({@link OpenCodeProbeTest},
 * {@link QuestionDriver#OPENCODE_ARGV}); {@code -Dpm.experiment.opencode.argv} (a JSON array with {@code {prompt}},
 * {@code {model}}, {@code {session_id}}) overrides it. What was verified, and what was not:
 * <ul>
 * <li>Command: {@code opencode run --standalone --format json --agent pull-worker [--model <provider/model>]
 * [--session <id>] <prompt>}. There is no {@code --dir}: the worker directory is the process working directory, and
 * OpenCode takes the project directory from {@code PWD}, which the driver sets to it (with {@code PWD} inherited from
 * the test JVM, OpenCode worked in {@code pm-e2e} of this repository and found no agent {@code pull-worker}).</li>
 * <li>Configuration: OpenCode reads the {@code opencode.json} of the worker directory beside the operator's global
 * one; the stub's server ({@code mcp.plan-marshall}, {@code type: local}) connected with the relay's identity from the
 * server's {@code environment}. OpenCode offers MCP tools only through its code tool {@code execute}
 * ({@code tools['plan-marshall'].pm_wait()}); the agent permits {@code execute} and {@code plan-marshall*}, and
 * {@code pm_state} and {@code pm_wait} reached the stub. Permitting {@code plan-marshall.pm_wait} and its siblings by
 * name did not expose them. The small models do not always find the tools behind {@code execute}: in 2 of 4 runs of
 * {@code opencode/gpt-5.4-nano} as {@code pull-worker} the model ended without calling them.</li>
 * <li>Background service versus standalone: without {@code --standalone} the run goes to the operator's background
 * service ({@code opencode service status}); the session lands in the service's data directory and not in the
 * worker's {@code XDG_DATA_HOME}. With {@code --standalone} a private server keeps the session in the worker's data
 * home and resumes from it. The operator's login does not carry into a worker's data home (copying
 * {@code auth.json} is not enough): there only the free models of the provider {@code opencode} are routable
 * ({@code opencode models --standalone}), and {@code opencode/deepseek-v4.1-flash-free} ran against the stub; a model
 * of the login fails with {@code Model unavailable} unless the data home is the operator's. The harness default
 * ({@code opencode/flint-40b-free}) failed with {@code 503 Endpoint is unavailable} in the one run that used it.
 * OpenCode therefore runs on the operator's global data home by default ({@code pm.experiment.opencode.data-home},
 * {@link OpenCodeDataHome#GLOBAL}) with {@code opencode/gpt-5.4-nano}, a model of the login verified to run there: a
 * departure from the per-worker data home of the work package, accepted by the operator and recorded in the result's
 * limits. The sessions of the runs stay in the operator's history. Every OpenCode worker then writes the one SQLite
 * database of that data home, and concurrent standalone servers on it are not verified, so the runs that start
 * OpenCode run one at a time ({@link #serialOpenCode(Config)}); the other runs keep {@code pm.experiment.parallel}.
 * {@code pm.experiment.opencode.data-home=worker} restores the per-worker data home and the harness default
 * model.</li>
 * <li>Session id: every line of {@code --format json} carries the top-level {@code sessionID}, error lines
 * included.</li>
 * <li>Resume: {@code --session <id>} continued the session under the same id (it named the entity of the earlier
 * turn).</li>
 * <li>Missing id: {@code --session ses_<26 characters>} that names no session creates one under that id, runs the
 * prompt and exits 0; R2 classifies it as {@code negative_new_session}, as Antigravity's
 * ({@link QuestionResults#startedNewSession}). An id of another form ({@code not a session id!}, a UUID) fails at once, exit 1,
 * with {@code Expected a string starting with "ses"}; R2 therefore names a missing session in OpenCode's own form
 * ({@link QuestionDriver#missingSessionId(Host)}).</li>
 * <li>The held call: a {@code pm_wait} the stub held for 150 s returned {@code wait: again} after 150.0 s, uncut; one
 * held for 330 s was uncut after about 260 s, when the probe was ended by its own build bound. Longer holds and
 * OpenCode's own limit on a held call are not verified.</li>
 * <li>Permissions: no {@code --auto}; the agent's allowed tools ran without a prompt, and a read outside the worker
 * directory was refused ({@code Permission denied: external_directory}). The question tool is denied by the agent's
 * {@code "*": false}; the models' own listings of their tools never named it.</li>
 * <li>Not verified: the launch through {@code pm-exec} (the probe started OpenCode directly), and whether OpenCode
 * passes its own environment to a local MCP server (the relay's identity is written into the server's
 * {@code environment}).</li>
 * </ul>
 * <p>
 * Bookkeeping: {@code runs.jsonl} is append-only and read per item, so the records of an item are cleared by the
 * operator before its first invocation; R4 runs as two invocations that share the item's records. Every invocation
 * appends its settings to {@code invocations.jsonl} (runs, fixtures and their limit, hosts, warmth, large runs,
 * parallel runs, R2 ages, run limit, holds, OpenCode models) and every run record carries its invocation id, so the
 * result lists the invocations it aggregates, a record of an earlier pass shows up there, an invocation with fewer
 * records than planned is flagged {@code incomplete}, and {@code atSpecifiedScale} says whether every invocation ran the
 * specified 20 runs per fixture and host without a fixture limit ({@link QuestionResults#scale}). The limits of the
 * result come from all invocations whose records it aggregates ({@link QuestionResults#result}).
 * {@link QuestionReaggregation} writes the result again from the existing records, without a run.
 */
@DisplayName("Question loop experiment: one item with -Dpm.experiment.question=<item>")
@EnabledIfSystemProperty(named = "pm.experiment.question", matches = ".+")
class QuestionLoopExperiment {

    /** The model of Claude Code unless {@code pm.experiment.model.claude} names another. */
    static final String CLAUDE_MODEL = "claude-haiku-4-5";
    /**
     * The model of OpenCode on the global data home unless {@code pm.experiment.model.opencode} names another: a model
     * of the operator's login, verified to run there on 2026-10-21.
     */
    static final String OPENCODE_GLOBAL_MODEL = "opencode/gpt-5.4-nano";
    /** The worker note every launch prompt ends with. */
    static final String WORKTREE_NOTE = "Your working directory is your worktree; files you write stay there.";

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();
    /** One run at a time that starts OpenCode on the global data home: its workers share one SQLite database. */
    private static final ReentrantLock OPENCODE_SERIAL = new ReentrantLock();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ROLE = "implement";
    private static final String TASK_ID = "T-1";
    private static final String EVENT = "event";
    private static final String DECISION = "decision";
    /** The field of a run record that says whether its R3 fault was injected (or, for the call cut, happened). */
    private static final String FAULT_INJECTED_FIELD = "fault_injected";
    private static final Duration POLL = Duration.ofSeconds(1);
    private static final Duration ANSWER_WAIT = Duration.ofMinutes(15);
    private static final String R2_AGES = "pm.experiment.r2.ages";
    private static final String R2_AGES_DEFAULT = "PT5M,PT1H,PT24H";
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
     * @param openCodeDataHome where OpenCode workers keep their data
     */
    record Config(List<Host> hosts, Map<Host, String> models, int runs, int fixtures, int parallel, Path cache,
    Path telemetry, String telemetryRef, Path clones, int largeRuns, Path workspace, Path results,
    List<String> exec, Duration runTimeout, Warmth warmth, OpenCodeDataHome openCodeDataHome) {

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
            var dataHome = OpenCodeDataHome.of(System.getProperty(OpenCodeDataHome.PROPERTY));
            var models = new EnumMap<Host, String>(Host.class);
            for (var host : Host.values()) {
                var model = System.getProperty("pm.experiment.model." + host.executable(),
                        defaultModel(host, dataHome));
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
                    Warmth.of(System.getProperty(Warmth.PROPERTY)), dataHome);
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

        var invocation = invocation(item, config, plans);
        QuestionResults.recordInvocation(config.results(), invocation);
        execute(plans, config, invocation.path(QuestionResults.INVOCATION).asText());
        var result = QuestionResults.result(item, QuestionResults.runs(config.results(), item),
                QuestionResults.invocations(config.results(), item),
                () -> QuestionFixtures.unusedScenarios(telemetry(config)).size());
        var file = QuestionResults.write(item, result);

        assertTrue(Files.isRegularFile(file), "no result written for " + item);
    }

    /**
     * The record of one invocation of an item: its id, which every run record of it carries, and the settings it ran
     * with, so the result can say which invocations it aggregates and whether they ran at the specified scale: among
     * them the run limit ({@value QuestionResults#RUN_TIMEOUT}, {@code pm.experiment.run-timeout}) and the holds of its
     * runs ({@code holds_s}; a run waits up to the run limit plus four times its hold), and the OpenCode models its
     * runs start ({@code opencode_models}, the harness default as {@code null}).
     *
     * @param item   the item
     * @param config the configuration
     * @param plans  the runs it plans
     * @return the invocation record
     */
    static ObjectNode invocation(String item, Config config, List<RunPlan> plans) {
        var bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        var record = QuestionStub.JSON.createObjectNode();
        record.put(QuestionResults.INVOCATION, item + "-" + Instant.now().toString().replace(":", "")
                .replace(".", "") + "-" + HexFormat.of().formatHex(bytes));
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
        record.put("parallel", config.parallel());
        record.put("opencode_data_home", config.openCodeDataHome().name().toLowerCase(Locale.ROOT));
        record.put("opencode_serial", serialOpenCode(config) && plans.stream().anyMatch(QuestionLoopExperiment::usesOpenCode));
        var models = record.putArray("opencode_models");
        openCodeModels(plans, config).stream().sorted(Comparator.nullsFirst(Comparator.naturalOrder()))
                .forEach(models::add);
        var ages = record.putArray("r2_ages_s");
        durations(R2_AGES, R2_AGES_DEFAULT).forEach(age -> ages.add(age.toSeconds()));
        record.put(QuestionResults.RUN_TIMEOUT, config.runTimeout().toString());
        var holds = record.putArray("holds_s");
        plans.stream().map(plan -> plan.hold().toSeconds()).distinct().sorted().forEach(holds::add);
        record.put("planned_runs", plans.size());
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
        return System.getProperty("pm.experiment.q6." + role, "quorum-a".equals(role) ? "claude:" + CLAUDE_MODEL
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
        return "vote".equals(plan.kind()) && QUORUMS.values().stream().flatMap(quorum -> quorum.roles().stream())
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
            if ("vote".equals(plan.kind())) {
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
     *         writes the one SQLite database of the operator's data home, and concurrent standalone servers on it are
     *         not verified to be safe
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

    private static List<String> workTools(Host host) {
        return switch (host) {
            case CLAUDE -> List.of("Read", "Write", "Edit", "Glob", "Grep");
            case OPENCODE -> List.of("read", "write", "edit", "glob", "grep");
            case ANTIGRAVITY -> List.of();
        };
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
                for (var age : durations(R2_AGES, R2_AGES_DEFAULT)) {
                    plans.add(RunPlan.of(item, index, host, config.model(host), warm, Form.RESUME, fixture, "entity")
                            .holding(age));
                }
                var base = RunPlan.of(item, index, host, config.model(host), warm, Form.RESUME, fixture, "entity");
                plans.add(base.holding(Duration.ofMinutes(5)).restarting());
                plans.add(base.resumingSession(QuestionDriver.missingSessionId(host)));
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
                                    .holding(r3Hold(host, fault)).faulting(fault));
                        }
                    });
            case "R4" -> plans.addAll(r4(config,
                    () -> limit(QuestionFixtures.largeScenarios(telemetry(config), config.clones()), config)));
            default -> throw new IllegalArgumentException("unknown item " + item + ": Q1 to Q7, R1 to R4");
        }
        return plans;
    }

    /** The operator's hold of an R3 run, beside {@link Fault#CALL_CUT}. */
    static final Duration R3_HOLD = Duration.ofSeconds(30);

    /**
     * @param host  the host
     * @param fault the R3 fault
     * @return the operator's hold of the run: {@link #R3_HOLD}, for {@link Fault#CALL_CUT} the host's cut plus
     *         {@link QuestionDriver#CALL_CUT_MARGIN} ({@link QuestionDriver.CutSetting#hold()}), so the host cuts the
     *         held call before the answer comes
     */
    static Duration r3Hold(Host host, Fault fault) {
        return fault == Fault.CALL_CUT ? QuestionDriver.cutSetting(host).hold() : R3_HOLD;
    }

    /**
     * The stub's settings of a run: the host's bounded wait as the window, the quorums of a vote run, and progress
     * every 5 s. A {@link Fault#CALL_CUT} run gets the window of its cut setting
     * ({@link QuestionDriver.CutSetting#window(Host)}) and no progress frame on a held call
     * ({@link QuestionDriver.CutSetting#noProgress(Duration)}), because Claude Code and OpenCode reset their timeout on
     * progress (evaluation, Worker Parameters per Host).
     *
     * @param plan    the planned run
     * @param stubDir the stub directory
     * @param socket  the stub's socket
     * @return the settings
     */
    static QuestionStub.Settings stubSettings(RunPlan plan, Path stubDir, Path socket) {
        var settings = QuestionStub.Settings.of(stubDir, socket).withWindow(plan.host().boundedWait())
                .withQuorums("vote".equals(plan.kind()) ? QUORUMS : Map.of());
        if (plan.fault() != Fault.CALL_CUT) {
            return settings;
        }
        var window = QuestionDriver.cutSetting(plan.host()).window(plan.host());
        return new QuestionStub.Settings(settings.directory(), settings.socket(), window,
                QuestionDriver.CutSetting.noProgress(window), settings.questionBound(), settings.quorums());
    }

    /**
     * @param plan a planned run
     * @return the tool timeout its first generation is launched with: the cut setting's of a {@link Fault#CALL_CUT}
     *         run ({@code null} on Antigravity, which has none), else {@code null}, the host default
     */
    static Duration toolTimeout(RunPlan plan) {
        return plan.fault() == Fault.CALL_CUT ? QuestionDriver.cutSetting(plan.host()).toolTimeout() : null;
    }

    /**
     * @param plan a planned run
     * @return whether its fresh asker is ended by the driver at its silence limit after the ask, before the open time
     *         starts: the fresh runs of Q7, which measure the server-side loop with the record open and no worker of
     *         the task connected ({@link QuestionDriver#endAsker})
     */
    static boolean endsAskerBeforeOpenTime(RunPlan plan) {
        return "Q7".equals(plan.item()) && !plan.warm() && plan.form() == Form.FALLBACK;
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

    private static List<Duration> durations(String property, String defaults) {
        var durations = new ArrayList<Duration>();
        for (var value : System.getProperty(property, defaults).split(",")) {
            durations.add(Duration.parse(value.strip()));
        }
        return durations;
    }

    // --- execution ---

    private static void execute(List<RunPlan> plans, Config config, String invocation) throws InterruptedException,
            ExecutionException {
        try (var executor = Executors.newFixedThreadPool(config.parallel())) {
            var futures = new ArrayList<Future<?>>();
            for (var plan : plans) {
                var serial = serialOpenCode(config) && usesOpenCode(plan);
                futures.add(executor.submit(() -> {
                    if (serial) {
                        OPENCODE_SERIAL.lock();
                    }
                    try {
                        runOne(plan, config, invocation);
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

    private static void runOne(RunPlan plan, Config config, String invocation) throws IOException {
        var bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        var suffix = HexFormat.of().formatHex(bytes);
        var id = "%s-%s-%s-%s-%03d-%s".formatted(plan.item(), plan.host().executable(), plan.warm() ? "warm" : "fresh",
                plan.fixture().id().replace('/', '_'), plan.index(), suffix);
        var base = config.workspace().resolve(id);
        var stubDir = base.resolve("stub");
        var stubSettings = stubSettings(plan, stubDir, Path.of("/tmp", "pmq-" + suffix + ".sock"));
        var record = runRecord(plan, stubDir);
        record.put(QuestionResults.INVOCATION, invocation);
        try (var driver = QuestionDriver.start(stubSettings, new QuestionDriver.Settings(plan.host(), plan.model(),
                     base.resolve("workers"), config.results().resolve("runs").resolve(id), config.exec(),
                     config.openCodeDataHome()))) {
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
        record.put("model", plan.model() == null ? QuestionResults.HARNESS_DEFAULT : plan.model());
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
        private boolean ended;

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
                while (!ended && !entryDone() && System.currentTimeMillis() < deadline
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
                    var hold = plan.hold();
                    if (rounds == 1 && plan.fault() == Fault.STOP_BEFORE_ANSWER) {
                        stopWhileHolding(current, asked.get());
                    }
                    if (rounds == 1 && plan.fault() == Fault.CALL_CUT) {
                        hold = awaitCut(current, asked.get());
                    }
                    if (!answer(asked.get(), hold)) {
                        break;
                    }
                    current = continueAfter(asked.get(), current);
                }
                awaitEntry(deadline);
                record.put(QuestionResults.TIMED_OUT, !ended && !entryDone());
            } finally {
                running.set(false);
                for (var helper : helpers) {
                    helper.join(POLL.toMillis() * 5);
                }
                recordVoterEarlyExits();
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
            var launch = launch("w1", 1, ROLE, plan.host(), toolTimeout(plan));
            if (plan.warm()) {
                driver.stub().queue(spec);
                return driver.startWarm(launch);
            }
            return driver.startFresh(spec, launch);
        }

        private Launch launch(String worker, int launchGeneration, String role, Host host, Duration toolTimeout) {
            return workerLaunch(worker, launchGeneration, role, host, plan.form() == Form.RESUME, toolTimeout);
        }

        /**
         * {@link Fault#STOP_BEFORE_ANSWER}: stops the asker once it holds {@code pm_wait} for the asked task, before
         * the answer ({@link QuestionDriver#stopWhileHolding}), within its silence limit after the ask, and records
         * whether the stop was injected ({@value #FAULT_INJECTED_FIELD}), when, and the start of the hold it was injected
         * in; an asker that never held is not stopped ({@code fault_not_injected_reason: no_hold}).
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
            } else {
                record.put("fault_held", false);
                record.put("fault_not_injected_reason", "no_hold");
            }
        }

        /**
         * {@link Fault#CALL_CUT}: waits, up to the time the operator's answer is due (the ask plus the plan's hold),
         * for the host to cut the asker's held {@code pm_wait} ({@link QuestionDriver#awaitCallCut}), and records how
         * the run made the host cut ({@code call_cut_means}, {@code call_cut_tool_timeout_ms},
         * {@code call_cut_host_cut_ms}, {@code call_cut_window_s}, {@code call_cut_progress}) and whether the logs
         * show the cut ({@code call_cut}, its evidence, time and milliseconds from the start of the held call).
         *
         * @return the rest of the operator's hold
         */
        private Duration awaitCut(Worker asker, JsonNode asked) throws IOException, InterruptedException {
            var setting = QuestionDriver.cutSetting(plan.host());
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
         * given hold, or, in a vote run, its quorum's decision without an operator; an escalated vote reaches the
         * operator and is answered as such. The caller then continues the task alike for both.
         */
        private boolean answer(JsonNode asked, Duration operatorHold) throws InterruptedException, IOException {
            var hold = operatorHold;
            if (plan.restart()) {
                Thread.sleep(hold.dividedBy(2));
                driver.restartStub();
                hold = hold.dividedBy(2);
            }
            var answered = driver.awaitAnswer(asked.path(DECISION).asText(), ANSWER_WAIT, hold,
                    decision -> QuestionFixtures.answer(plan.fixture(), decision, plan.reply()));
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
            if (recordLoss(current) && recover(current, decision, task).step() == RecoveryStep.TAKEN) {
                return current;
            }
            return continuation(decision, current);
        }

        /**
         * Resumes the asker's session after the answer; a resumed generation whose answer acknowledgement is missing
         * is followed by the step the stub's state gives ({@link QuestionDriver#recovery}): the session resumed again
         * while the task waits resumable (or the stub has not acted yet, as before), a generation leased on the stub's
         * continuation while it is queued, and none once a generation holds or closed it. Before 2026-10-23 the
         * session was always resumed again, also on the task the stub had already closed {@code lost} and continued at
         * the resumed process's exit (run {@code cc787dd3} of the R3 smoke attempt, which then timed out).
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
                case TAKEN -> resumed;
                case CONTINUE -> driver.startLeased(recovery.task(), recovery.entity(),
                        launch(current.id(), ++generation, ROLE, plan.host(), null));
                case RESUME, WAIT -> {
                    var again = driver.resume(current, task, session.get(), ++generation);
                    driver.superviseAnswer(again, Duration.ofMinutes(2));
                    yield again;
                }
            };
        }

        /** Injects a fault ({@link QuestionDriver#inject}) and records it and its time. */
        private void injected(Worker worker, Fault fault) {
            driver.inject(worker, fault);
            record.put(FAULT_INJECTED_FIELD, true);
            record.put(FAULT_INJECTED_FIELD + "_at", System.currentTimeMillis());
        }

        /**
         * Records the first loss the driver detected in the run ({@value QuestionDriver#LOST_DETECTED} of the
         * generation): by what ({@code loss_by}), after how long ({@code loss_after_ms}), and who saw it first
         * ({@code loss_first_by}: the {@code driver} at the acknowledgement deadline, or the {@code stub} at the
         * process's exit, with {@code loss_stub_reason} and {@code loss_stub_after_ms}).
         *
         * @return whether the driver detected a loss of the generation
         */
        private boolean recordLoss(Worker worker) throws IOException {
            var detected = QuestionResults.readJsonLines(driver.stub().events()).stream()
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
            }
            return true;
        }

        /** Reads what the stub made of the lost task ({@link QuestionDriver#recovery}) and records the step. */
        private QuestionDriver.Recovery recover(Worker lost, String decision, String task) throws IOException {
            var recovery = driver.recovery(lost, decision, task);
            if (!record.has("recovery_step")) {
                record.put("recovery_step", recovery.step().name().toLowerCase(Locale.ROOT));
                record.put("recovery_task", recovery.task());
                record.put("recovery_holder", recovery.holder());
            }
            return recovery;
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

        /**
         * Starts a fresh generation of the asking worker on the task that continues the decision, unless the asking
         * worker already took that task over its held {@code pm_wait} and closed it
         * ({@link QuestionDriver#continuedByHold(String)}): then no generation is started, the run records the
         * continuation made by hold ({@link #recordContinuedByHold}), and the run goes on as after any continuation
         * by hold: it ends once its entry is done (a further ask of the continuation is the next round), never at its
         * run limit for want of a generation, and a hold the worker still keeps is ended with {@code wait: end}
         * ({@link #endWarm()}).
         */
        private Worker continuation(String decision, Worker current) throws IOException, InterruptedException {
            var continued = driver.awaitEvent(event -> "continued".equals(event.path(EVENT).asText())
                    && decision.equals(event.path(DECISION).asText()), System.currentTimeMillis()
                    + ANSWER_WAIT.toMillis());
            if (continued.isEmpty()) {
                return current;
            }
            var held = driver.continuedByHold(decision);
            if (held.isPresent()) {
                recordContinuedByHold(held.get());
                return current;
            }
            var host = plan.continueHost() == null ? plan.host() : plan.continueHost();
            var target = host == plan.host() ? driver : sibling(host);
            var launch = launch(current.id(), ++generation, ROLE, host, null);
            return target.startLeased(continued.get().path("task").asText(),
                    continued.get().path("entity").asText(), launch);
        }

        /**
         * Records the first continuation made by hold of the run ({@value QuestionDriver#CONTINUED_BY_HOLD}) with its
         * task, the generation that took and closed it, its outcome, the times of its close and of its submission
         * ({@code -1} without one, epoch milliseconds), and the channel of the answer. The run counts in
         * {@code continued} and {@code submitted} from the stub's events as any continuation by hold does.
         */
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
         * Resumes a missing or over-age session and waits for its end up to the run timeout, so that a harness that
         * starts a new conversation instead of failing (Antigravity: a warning on {@code stderr}, a new id, exit 0 at
         * the end of the turn) is told apart from one that hangs. The run records the exit, the session id the
         * generation reports, and whether it started a new session: a reported id other than the one named, or the
         * harness's warning that the named one was not found.
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
            var warned = QuestionResults.warnsMissingSession(driver.stderr(resumed), plan.negative());
            record.put("negative_not_found_warning", warned);
            record.put(QuestionResults.NEGATIVE_NEW_SESSION, QuestionResults.startedNewSession(plan.host(),
                    plan.negative(), reported.orElse(""), warned));
        }

        private Optional<JsonNode> nextAsked(long deadline) throws IOException, InterruptedException {
            var asked = driver.awaitEventWhileLaunched(event -> "asked".equals(event.path(EVENT).asText())
                    && !seenDecisions.contains(event.path(DECISION).asText()) || entryDone(), deadline);
            if (asked.isEmpty() && ended()) {
                return Optional.empty();
            }
            asked.filter(event -> "asked".equals(event.path(EVENT).asText()))
                    .ifPresent(event -> seenDecisions.add(event.path(DECISION).asText()));
            return asked.filter(event -> "asked".equals(event.path(EVENT).asText()));
        }

        /**
         * Records the run as {@code launch_failed} once a worker failed to launch, with its exit code and the first
         * line of its {@code stderr}, or as {@value QuestionResults#EARLY_EXIT} once a worker exited after its relay
         * connected without a single call to the stub, with its exit code and the first harness line of its
         * {@code stderr} or the last line of its output ({@link QuestionDriver#earlyExit()}): either ends the run at
         * once, a failure of the run, never a pass, never a timeout, and never counted as asked. A continuation
         * generation (one after the generation in which its worker asked) that exits so ends the run at once as
         * {@value QuestionResults#CONTINUATION_EARLY_EXIT}, with the same fields under that prefix: the earlier ask
         * stays counted, and the run counts neither as {@value QuestionResults#EARLY_EXIT} nor as timed out. A
         * generation that called the stub, an asker among them, and then exited keeps the wait as before. A quota or
         * limit error of a
         * worker's harness ({@link QuestionDriver#quotaError()}) comes first: it ends the run at once as
         * {@value QuestionResults#QUOTA_ERROR}, with the worker and the matching line, whether or not the worker
         * called the stub.
         *
         * @return whether the run ended so
         */
        private boolean ended() throws IOException, InterruptedException {
            if (!ended) {
                var quota = driver.quotaError();
                if (quota.isPresent()) {
                    ended = true;
                    var value = quota.get();
                    record.put(QuestionResults.QUOTA_ERROR, true);
                    record.put("quota_error_worker", value.worker());
                    record.put("quota_error_generation", value.generation());
                    record.put("quota_error_detail", value.detail());
                }
            }
            if (!ended) {
                var failure = driver.launchFailure();
                if (failure.isPresent()) {
                    ended = true;
                    var value = failure.get();
                    record.put("launch_failed", true);
                    record.put("launch_worker", value.worker());
                    record.put("launch_generation", value.generation());
                    record.put("launch_exit", value.exit());
                    record.put("launch_stderr", value.stderr());
                    var tail = record.putArray("launch_stderr_tail");
                    value.tail().forEach(tail::add);
                    record.put("error", "launch_failed: exit " + value.exit() + ": " + value.stderr());
                }
            }
            if (!ended) {
                var early = driver.earlyExit();
                if (early.isPresent()) {
                    ended = true;
                    var value = early.get();
                    var field = value.continuation() ? QuestionResults.CONTINUATION_EARLY_EXIT
                            : QuestionResults.EARLY_EXIT;
                    record.put(field, true);
                    record.put(field + "_worker", value.worker());
                    record.put(field + "_generation", value.generation());
                    record.put(field + "_code", value.exit());
                    record.put(field + "_detail", value.detail());
                }
            }
            return ended;
        }

        /**
         * Waits until the asker stopped after its ask: a fresh worker exits, a warm worker calls {@code pm_wait}
         * (Q3), before the answer can start a continuation that changes the worktree. In Q7 the fresh asker is ended
         * by the driver at its silence limit after the ask if it still runs ({@link QuestionDriver#endAsker}), before
         * the open time starts, so that the record is open with no worker of the task connected; the run records
         * whether that was needed ({@value QuestionDriver#ASKER_ENDED_BY_DRIVER}), when, and whether the asker still
         * held {@code pm_wait} then.
         */
        private void awaitStop(Worker asker, JsonNode asked) throws IOException, InterruptedException {
            var at = asked.path("at").asLong();
            if (plan.warm()) {
                driver.awaitEvent(event -> "wait_started".equals(event.path(EVENT).asText())
                                && event.path("at").asLong() >= at && asker.id().equals(event.path("worker").asText())
                                && asker.generation() == event.path("generation").asInt(),
                        System.currentTimeMillis() + plan.host().silenceLimit().toMillis());
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

        /**
         * Starts a fresh worker on every vote task, on the harness and model of its role, once: a voter that exits
         * without a vote is not restarted, and its vote task is not offered again ({@link QuestionStub#lost}).
         */
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
                            var host = Host.of(assignment[0]);
                            var voter = sibling(host, assignment.length > 1 ? assignment[1] : null);
                            voter.startLeased(id, id, voterLaunch(id, role));
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

        /**
         * Records the voters that connected and exited without a call ({@link QuestionDriver#voterEarlyExits()}) in
         * the run record: {@value QuestionResults#VOTER_EARLY_EXIT} with the first one's worker, generation, exit code
         * and detail, and every one in {@code voter_early_exits}. The run is not ended by them: the missing vote
         * escalates the set to the operator.
         */
        private void recordVoterEarlyExits() throws IOException {
            var exits = driver.voterEarlyExits();
            if (exits.isEmpty()) {
                return;
            }
            var first = exits.getFirst();
            var field = QuestionResults.VOTER_EARLY_EXIT;
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
                node.put("stderr", QuestionDriver.stderrFile(worker.output().getParent(), worker.id(),
                        worker.generation()).toString());
                node.put("relay_log", worker.relayLog().toString());
            }
            return array;
        }
    }
}
