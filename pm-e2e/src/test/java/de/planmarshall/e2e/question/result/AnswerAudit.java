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

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.planmarshall.e2e.question.fixture.QuestionFixtures;
import de.planmarshall.e2e.question.fixture.QuestionFixtures.ConsistencyHint;
import de.planmarshall.e2e.question.stub.QuestionStub;

/**
 * How the driver answered on the operator's behalf, recorded per run and audited over stored runs without a model: no
 * operator answer may pair a branch with a reply text that contradicts it ({@link QuestionFixtures#composeAnswer}).
 * The audit is written beside the evidence of every invocation ({@code answer-audit.json}, {@link QuestionEvidence}).
 */
public final class AnswerAudit {

    /** The field of a run record with how each of its operator answers was composed. */
    public static final String ANSWER_COMPOSITIONS = "answer_compositions";
    /** The field of a run record, and the group figure, of a run whose operator answer fell back to its own option. */
    public static final String ANSWER_FALLBACK_OWN = "answer_fallback_own";
    /** The audit class of an operator answer whose chosen branch contradicts its own reply text. */
    public static final String CONTRADICTING = "contradicting";

    private static final String RUNS = "runs";
    private static final String CONSISTENT = "consistent";
    private static final String UNCLEAR = "unclear";
    private static final String OWN = "own";
    private static final String UNHINTED = "unhinted";
    private static final String QUORUM = "quorum";
    private static final String OPERATOR_ANSWERS = "operator_answers";
    private static final String RECOMPOSED = "recomposed_fallback_own";
    private static final String WITH_ANSWER = "runs_with_operator_answer";
    private static final String CONTRADICTING_RUNS = "contradicting_runs";
    private static final String NOT_FOLLOWING = "contradicting_runs_not_following";
    private static final String CONTRADICTING_IDS = "contradicting_run_ids";
    private static final List<String> COUNTED = List.of(RUNS, WITH_ANSWER, CONTRADICTING_RUNS, NOT_FOLLOWING,
            OPERATOR_ANSWERS, CONTRADICTING, CONSISTENT, UNCLEAR, OWN, UNHINTED, QUORUM, RECOMPOSED);

    private AnswerAudit() {
    }

    /**
     * @param decisionId the decision answered
     * @param reply      the reply form
     * @param note       how one part was composed
     * @return the fields of one entry of {@value #ANSWER_COMPOSITIONS}
     */
    public static Map<String, Object> compositionFields(String decisionId, QuestionFixtures.Reply reply,
            QuestionFixtures.PartNote note) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("decision", decisionId);
        fields.put("question_id", note.questionId());
        fields.put("reply", reply.name().toLowerCase(Locale.ROOT));
        fields.put("branch_key", note.branchKey());
        fields.put("label", note.label());
        fields.put("hinted", note.hinted());
        fields.put("hint_matched", note.hintMatched());
        fields.put("fallback_own", note.fallbackOwn());
        return fields;
    }

    /**
     * Records how an operator answer of a run was composed: appends one entry per part to
     * {@value #ANSWER_COMPOSITIONS} and sets {@value #ANSWER_FALLBACK_OWN} once any part of any answer of the run fell
     * back to the operator's own option.
     *
     * @param run         the run record, changed in place
     * @param decisionId  the decision answered
     * @param reply       the reply form
     * @param composition the composed answer
     */
    public static void recordComposition(ObjectNode run, String decisionId, QuestionFixtures.Reply reply,
            QuestionFixtures.Composition composition) {
        var entries = run.get(ANSWER_COMPOSITIONS) instanceof ArrayNode existing ? existing
                : run.putArray(ANSWER_COMPOSITIONS);
        for (var note : composition.notes()) {
            entries.add(QuestionStub.JSON.valueToTree(compositionFields(decisionId, reply, note)));
        }
        run.put(ANSWER_FALLBACK_OWN, run.path(ANSWER_FALLBACK_OWN).asBoolean() || composition.fallbackOwn());
    }

    /**
     * The answer audit over stored runs: reads each run's question records and classifies every answer the driver gave
     * on the operator's behalf (channel {@code cli}) by the fixture's consistency hint: the chosen option reads as the
     * reference direction ({@code consistent}), as the opposite one ({@value #CONTRADICTING}), or as neither
     * ({@code unclear}); an answer with the operator's own option chooses no branch ({@code own}); a fixture without a
     * hint counts {@code unhinted}; an answer of a quorum carries no reply text ({@code quorum}). Per group (item, host,
     * model, form, kind, reply, fixture) it counts the runs, the answers per class and the contradicting runs, and lists
     * every option the askers offered per hinted fixture. The records are only read.
     *
     * @param runs the run records
     * @return the audit; {@code totals.contradicting} is 0 for a clean audit
     * @throws IOException if a run's files cannot be read
     */
    public static ObjectNode audit(List<JsonNode> runs) throws IOException {
        var groups = new TreeMap<String, ObjectNode>();
        var affected = new TreeSet<String>();
        var options = new TreeMap<String, TreeMap<String, ObjectNode>>();
        for (var run : runs) {
            if (RunView.setupFailed(run)) {
                continue;
            }
            var group = groups.computeIfAbsent(auditKey(run), _ -> auditGroup(run));
            var view = RunView.read(run);
            var counts = auditRun(view, options);
            group.put(RUNS, group.path(RUNS).asInt() + 1);
            for (var entry : counts.properties()) {
                if (entry.getValue().isArray()) {
                    var list = group.get(entry.getKey()) instanceof ArrayNode existing ? existing
                            : group.putArray(entry.getKey());
                    list.addAll((ArrayNode) entry.getValue());
                } else {
                    group.put(entry.getKey(), group.path(entry.getKey()).asInt() + entry.getValue().asInt());
                }
            }
            if (counts.path(OPERATOR_ANSWERS).asInt() > 0) {
                group.put(WITH_ANSWER, group.path(WITH_ANSWER).asInt() + 1);
            }
            if (counts.path(CONTRADICTING).asInt() > 0) {
                group.put(CONTRADICTING_RUNS, group.path(CONTRADICTING_RUNS).asInt() + 1);
                if (!RunReadings.followsReference(view)) {
                    group.put(NOT_FOLLOWING, group.path(NOT_FOLLOWING).asInt() + 1);
                }
                ((ArrayNode) group.get(CONTRADICTING_IDS)).add(RunView.runId(run));
                affected.add(run.path("item").asText() + " " + QuestionResults.groupKey(run.path("item").asText(),
                        run));
            }
        }
        var audit = QuestionStub.JSON.createObjectNode();
        var array = audit.putArray("groups");
        var totals = audit.putObject("totals");
        for (var group : groups.values()) {
            array.add(group);
            for (var field : COUNTED) {
                totals.put(field, totals.path(field).asInt() + group.path(field).asInt());
            }
        }
        var groupsToRerun = audit.putArray("affected_result_groups");
        affected.forEach(groupsToRerun::add);
        var catalogue = audit.putObject("options");
        options.forEach((fixture, labels) -> {
            var list = catalogue.putArray(fixture);
            labels.values().forEach(list::add);
        });
        return audit;
    }

    private static String auditKey(JsonNode run) {
        var key = new StringBuilder();
        for (var field : List.of("item", "host", "model", "form", "kind", "reply", "fixture")) {
            key.append(field).append('=').append("model".equals(field) ? RunView.model(run) : run.path(field).asText())
                    .append(' ');
        }
        return key.toString().strip();
    }

    private static ObjectNode auditGroup(JsonNode run) {
        var group = QuestionStub.JSON.createObjectNode();
        for (var field : List.of("item", "host", "form", "kind", "reply", "fixture")) {
            group.put(field, run.path(field).asText());
        }
        group.put("model", RunView.model(run));
        group.put("hinted", QuestionFixtures.hint(run.path("fixture").asText()).isPresent());
        COUNTED.forEach(field -> group.put(field, 0));
        group.putArray(CONTRADICTING_IDS);
        return group;
    }

    /**
     * Classifies the answers of one run and adds the options its askers offered to the catalogue.
     *
     * @param view    the run
     * @param options the catalogue per fixture and option label, changed in place
     * @return the counts of the run per class
     */
    static ObjectNode auditRun(RunView view, Map<String, TreeMap<String, ObjectNode>> options) {
        var fixture = view.run().path("fixture").asText();
        var hint = QuestionFixtures.hint(fixture);
        var reply = view.run().path("reply").asText();
        var parts = new HashMap<String, JsonNode>();
        var counts = QuestionStub.JSON.createObjectNode();
        for (var line : view.records()) {
            var name = line.path("line").asText();
            if (RunReadings.ASKED.equals(name)) {
                var ids = line.path("question_ids");
                var envelopeParts = line.path("envelope").path("parts");
                for (var index = 0; index < envelopeParts.size(); index++) {
                    var part = envelopeParts.get(index);
                    parts.put(ids.path(index).asText(), part);
                    hint.ifPresent(value -> catalogue(options, fixture, part, value));
                }
            } else if ("answered".equals(name)) {
                for (var item : line.path("items")) {
                    var part = parts.get(item.path("question_id").asText());
                    var answerClass = answerClass(line.path("channel").asText(), item, part, hint);
                    counts.put(answerClass, counts.path(answerClass).asInt() + 1);
                    if (UNCLEAR.equals(answerClass)) {
                        var unclear = counts.get("unclear_labels") instanceof ArrayNode existing ? existing
                                : counts.putArray("unclear_labels");
                        unclear.add(item.path("label").asText());
                    }
                    if (!QUORUM.equals(answerClass)) {
                        counts.put(OPERATOR_ANSWERS, counts.path(OPERATOR_ANSWERS).asInt() + 1);
                        if (recomposedFallback(reply, part, hint)) {
                            counts.put(RECOMPOSED, counts.path(RECOMPOSED).asInt() + 1);
                        }
                    }
                }
            }
        }
        return counts;
    }

    /**
     * @param channel the channel of the answer ({@code cli} for the driver's, else a quorum)
     * @param item    the recorded answer item ({@code branch_key}, {@code kind})
     * @param part    the part it answers, {@code null} when its record is missing
     * @param hint    the fixture's consistency hint
     * @return {@code quorum}, {@code own}, {@code unhinted}, {@code consistent}, {@value #CONTRADICTING} or
     *         {@code unclear}
     */
    public static String answerClass(String channel, JsonNode item, JsonNode part, Optional<ConsistencyHint> hint) {
        if (!"cli".equals(channel)) {
            return QUORUM;
        }
        var branch = item.path("branch_key").asText();
        if (OWN.equals(branch) || "own_option".equals(item.path("kind").asText())) {
            return OWN;
        }
        if (hint.isEmpty()) {
            return UNHINTED;
        }
        JsonNode chosen = null;
        if (part != null) {
            for (var option : part.path("options")) {
                if (branch.equals(option.path("branch_key").asText())) {
                    chosen = option;
                }
            }
        }
        if (chosen == null) {
            return UNCLEAR;
        }
        return switch (hint.get().classify(chosen)) {
            case CONSISTENT -> CONSISTENT;
            case CONTRADICTING -> CONTRADICTING;
            case UNCLEAR -> UNCLEAR;
        };
    }

    private static boolean recomposedFallback(String reply, JsonNode part, Optional<ConsistencyHint> hint) {
        if (part == null || hint.isEmpty() || part.path("options").isEmpty()
                || !Set.of("option", "default").contains(reply)) {
            return false;
        }
        var recommended = part.path("recommendation").path("branch_key").asText(OWN);
        return QuestionFixtures.consistentChoice(part, hint.get(), recommended, "default".equals(reply)).isEmpty();
    }

    private static void catalogue(Map<String, TreeMap<String, ObjectNode>> options, String fixture, JsonNode part,
            ConsistencyHint hint) {
        var recommended = part.path("recommendation").path("branch_key").asText("");
        for (var option : part.path("options")) {
            var label = option.path("label").asText();
            var node = options.computeIfAbsent(fixture, _ -> new TreeMap<>()).computeIfAbsent(label, _ -> {
                var created = QuestionStub.JSON.createObjectNode();
                created.put("label", label);
                created.put("description", option.path("description").asText());
                created.put("class", hint.classify(option).name().toLowerCase(Locale.ROOT));
                created.put("choosable", hint.consistent(option));
                created.put("offered", 0);
                created.put("recommended", 0);
                return created;
            });
            node.put("offered", node.path("offered").asInt() + 1);
            if (recommended.equals(option.path("branch_key").asText())) {
                node.put("recommended", node.path("recommended").asInt() + 1);
            }
        }
    }
}
