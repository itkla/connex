package ooo.klae.connex.backend.ai.mission;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.mission.AiAgentCheckpoint.CompletedFact;
import ooo.klae.connex.backend.ai.mission.AiAgentCheckpoint.Continuation;
import ooo.klae.connex.backend.ai.mission.AiAgentCheckpoint.Coverage;
import ooo.klae.connex.backend.ai.mission.AiAgentContinuationCodec.Snapshot;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.DurableText;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.EvidenceReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure screened replacement preparation; authoritative persistence commits the prepared snapshot. */
@Component
public final class AiAgentPlanService {
    private final AiAgentContinuationCodec codec;
    private final JsonMapper json = JsonMapper.builder().build();

    public AiAgentPlanService(AiAgentContinuationCodec codec) {
        this.codec = Objects.requireNonNull(codec);
    }

    /** Screened values admitted before persistence; arguments and results retain server-side provenance. */
    public record Prepared(Snapshot snapshot, JsonNode arguments, JsonNode result) {
        public Prepared {
            Objects.requireNonNull(snapshot);
            arguments = Objects.requireNonNull(arguments).deepCopy();
            result = Objects.requireNonNull(result).deepCopy();
        }

        @Override
        public JsonNode arguments() {
            return arguments.deepCopy();
        }

        @Override
        public JsonNode result() {
            return result.deepCopy();
        }
    }

    /** Provider-safe plan shape; canonical member and evidence identities cannot be serialized here. */
    public record ModelPlan(int schemaVersion, String revision, int nextItemOrdinal,
            List<ModelItem> items, List<String> displayOrder) {
        public ModelPlan {
            items = List.copyOf(items);
            displayOrder = List.copyOf(displayOrder);
        }
    }

    /** The model sees only the server-issued plan key, freshly masked label, and status. */
    public record ModelItem(String key, String label, String status) {
    }

    /** Projects all allocated identities while exposing only freshly masked prose. */
    public ModelPlan modelPlan(AiAgentPlan plan, Function<String, String> mask) {
        Objects.requireNonNull(mask);
        return modelPlanText(plan, text -> mask.apply(text.text()));
    }

    /** Projects each durable field with its own validated provenance. */
    public ModelPlan modelPlanText(AiAgentPlan plan, Function<DurableText, String> mask) {
        List<ModelItem> items = plan.items().stream().map(item -> new ModelItem(item.key(),
                Objects.requireNonNull(mask.apply(item.label())), item.status())).toList();
        return new ModelPlan(plan.schemaVersion(), plan.revision(), plan.nextItemOrdinal(), items, plan.displayOrder());
    }

    /** Strictly prepares a keyed plan replacement and append-only completed-fact ledger. */
    public Prepared prepare(Snapshot current, JsonNode arguments, int segmentNo,
            Function<String, DurableText> screen,
            Function<List<String>, List<EvidenceReference>> citations) {
        return prepare(current, arguments, segmentNo, (path, text) -> screen.apply(text), citations, List.of());
    }

    /** Preserves field-scoped parser provenance and binds all carried prose to observed sources. */
    public Prepared prepare(Snapshot current, JsonNode arguments, int segmentNo,
            BiFunction<String, String, DurableText> screen,
            Function<List<String>, List<EvidenceReference>> citations, List<EvidenceReference> observed) {
        Objects.requireNonNull(current);
        Objects.requireNonNull(screen);
        Objects.requireNonNull(citations);
        requireSegment(current, segmentNo);
        AiAgentContinuationCodec.fields(arguments, "expectedPlanRevision", "expectedCheckpointRevision", "items");
        requireRevisions(current, arguments);
        var oldPlan = current.plan();
        var oldCheckpoint = current.checkpoint();
        LinkedHashMap<String, AiAgentPlan.Item> items = new LinkedHashMap<>();
        oldPlan.items().forEach(item -> items.put(item.key(), item));
        LinkedHashMap<String, CompletedFact> facts = new LinkedHashMap<>();
        oldCheckpoint.completedFacts().forEach(fact -> facts.put(fact.planItemKey(), fact));
        Set<EvidenceReference> inventory = new LinkedHashSet<>(oldCheckpoint.evidenceReferences());
        inventory.addAll(observed);
        List<String> order = new ArrayList<>();
        List<JsonNode> canonicalItems = new ArrayList<>();
        int next = oldPlan.nextItemOrdinal();
        var requestedItems = AiAgentContinuationCodec.array(arguments, "items");
        AiAgentContinuationCodec.require(requestedItems.size() <= AiAgentContinuationCodec.MAX_ITEMS);
        for (int index = 0; index < requestedItems.size(); index++) {
            var requested = requestedItems.get(index);
            AiAgentContinuationCodec.fields(requested, "key", "label", "status", "completion");
            String key = AiAgentContinuationCodec.nullableString(requested, "key");
            boolean created = key == null;
            if (created) {
                AiAgentContinuationCodec.require(next <= AiAgentContinuationCodec.MAX_ITEMS);
                key = AiAgentContinuationCodec.planKey(next++);
            } else {
                AiAgentContinuationCodec.key(key);
                AiAgentContinuationCodec.require(items.containsKey(key));
            }
            AiAgentContinuationCodec.require(!order.contains(key));
            order.add(key);
            String status = AiAgentContinuationCodec.string(requested, "status");
            AiAgentContinuationCodec.require(Set.of("pending", "active", "done").contains(status));
            DurableText label = Objects.requireNonNull(screen.apply("/items/" + index + "/label", AiAgentContinuationCodec.string(requested, "label")));
            AiAgentContinuationCodec.require(!label.text().isBlank());
            var previous = items.get(key);
            if (!created && "done".equals(previous.status())) {
                AiAgentContinuationCodec.require("done".equals(status));
            }
            var completion = requested.get("completion");
            AiAgentContinuationCodec.require(completion != null);
            CompletedFact proposedFact = null;
            if (!completion.isNull()) {
                AiAgentContinuationCodec.fields(completion, "summary", "citations");
                AiAgentContinuationCodec.require("done".equals(status));
                DurableText summary = Objects.requireNonNull(screen.apply("/items/" + index + "/completion/summary", AiAgentContinuationCodec.string(completion, "summary")));
                AiAgentContinuationCodec.require(!summary.text().isBlank());
                List<EvidenceReference> references = List.copyOf(Objects.requireNonNull(citations.apply(
                        AiAgentContinuationCodec.strings(completion, "citations"))));
                AiAgentContinuationCodec.require(!references.isEmpty());
                proposedFact = new CompletedFact(key, summary, references, segmentNo);
                var previousFact = facts.get(key);
                if (previousFact != null) {
                    AiAgentContinuationCodec.require(previousFact.summary().equals(summary)
                            && previousFact.evidenceReferences().equals(references));
                } else {
                    facts.put(key, proposedFact);
                    inventory.addAll(references);
                }
            }
            if ("done".equals(status)) {
                AiAgentContinuationCodec.require(facts.containsKey(key));
            } else {
                AiAgentContinuationCodec.require(completion.isNull());
            }
            items.put(key, new AiAgentPlan.Item(key, label, status));
            var canonicalItem = json.createObjectNode();
            canonicalItem.put("key", created ? null : key);
            canonicalItem.set("label", json.valueToTree(label));
            canonicalItem.put("status", status);
            if (proposedFact == null) {
                canonicalItem.putNull("completion");
            } else {
                var canonicalCompletion = canonicalItem.putObject("completion");
                canonicalCompletion.set("summary", json.valueToTree(proposedFact.summary()));
                canonicalCompletion.set("evidenceReferences", json.valueToTree(proposedFact.evidenceReferences()));
            }
            canonicalItems.add(canonicalItem);
        }
        long planRevision = AiAgentContinuationCodec.nextRevision(AiAgentContinuationCodec.decimal(oldPlan.revision()));
        long checkpointRevision = AiAgentContinuationCodec.nextRevision(AiAgentContinuationCodec.decimal(oldCheckpoint.revision()));
        var plan = new AiAgentPlan(1, Long.toString(planRevision), next, List.copyOf(items.values()), order);
        String nextKey = oldCheckpoint.nextPlanItemKey();
        if (nextKey != null && (!order.contains(nextKey) || facts.containsKey(nextKey))) {
            nextKey = null;
        }
        var checkpoint = checkpoint(oldCheckpoint, Long.toString(checkpointRevision), Long.toString(planRevision),
                List.copyOf(facts.values()), nextKey, oldCheckpoint.lastCommittedSegmentNo(),
                List.copyOf(inventory), oldCheckpoint.coverage(), oldCheckpoint.continuation());
        var snapshot = new Snapshot(plan, checkpoint, current.memory());
        codec.validateTransition(current, snapshot, false);
        admit(snapshot);
        var canonicalArguments = json.createObjectNode();
        canonicalArguments.put("expectedPlanRevision", oldPlan.revision());
        canonicalArguments.put("expectedCheckpointRevision", oldCheckpoint.revision());
        var canonicalArray = canonicalArguments.putArray("items");
        canonicalItems.forEach(canonicalArray::add);
        var result = json.createObjectNode();
        result.put("planRevision", plan.revision());
        result.put("checkpointRevision", checkpoint.revision());
        result.set("plan", json.valueToTree(plan));
        return new Prepared(snapshot, canonicalArguments, result);
    }

    /** Prepares memory and exactly one committed logical cursor advance, preserving all ledgers. */
    public Prepared prepareCheckpoint(Snapshot current, JsonNode arguments, int segmentNo,
            Function<String, DurableText> screen,
            Function<List<String>, List<EvidenceReference>> citations) {
        return prepareCheckpoint(current, arguments, segmentNo, (path, text) -> screen.apply(text), citations, List.of());
    }

    /** Preserves field-scoped parser provenance and binds all carried prose to observed sources. */
    public Prepared prepareCheckpoint(Snapshot current, JsonNode arguments, int segmentNo,
            BiFunction<String, String, DurableText> screen,
            Function<List<String>, List<EvidenceReference>> citations, List<EvidenceReference> observed) {
        Objects.requireNonNull(current);
        Objects.requireNonNull(screen);
        Objects.requireNonNull(citations);
        requireSegment(current, segmentNo);
        AiAgentContinuationCodec.fields(arguments, "expectedPlanRevision", "expectedCheckpointRevision",
                "nextPlanItemKey", "memory", "phase");
        requireRevisions(current, arguments);
        String nextKey = AiAgentContinuationCodec.nullableString(arguments, "nextPlanItemKey");
        if (nextKey != null) {
            String selectedKey = nextKey;
            AiAgentContinuationCodec.key(nextKey);
            AiAgentContinuationCodec.require(current.plan().displayOrder().contains(nextKey)
                    && current.plan().items().stream().anyMatch(item -> item.key().equals(selectedKey)
                    && !"done".equals(item.status())));
        }
        String phase = AiAgentContinuationCodec.string(arguments, "phase");
        AiAgentContinuationCodec.require(Set.of("reading", "reporting").contains(phase));
        var requestedMemory = arguments.get("memory");
        AiAgentContinuationCodec.fields(requestedMemory, "summary", "citations");
        DurableText summary = Objects.requireNonNull(screen.apply("/memory/summary", AiAgentContinuationCodec.string(requestedMemory, "summary")));
        List<EvidenceReference> references = List.copyOf(Objects.requireNonNull(citations.apply(
                AiAgentContinuationCodec.strings(requestedMemory, "citations"))));
        var memory = new AiAgentMemory(1, summary, references);
        var inventory = new LinkedHashSet<>(current.checkpoint().evidenceReferences());
        inventory.addAll(references);
        inventory.addAll(observed);
        var oldCheckpoint = current.checkpoint();
        var checkpoint = checkpoint(oldCheckpoint,
                Long.toString(AiAgentContinuationCodec.nextRevision(AiAgentContinuationCodec.decimal(oldCheckpoint.revision()))),
                current.plan().revision(), oldCheckpoint.completedFacts(), nextKey, segmentNo,
                List.copyOf(inventory), inventory.isEmpty() ? new Coverage(0, 0, 0, 1, List.of())
                        : new Coverage(1, 0, 0, 0, List.of()),
                new Continuation("record_review_v1", 1, phase));
        var snapshot = new Snapshot(current.plan(), checkpoint, memory);
        codec.validateTransition(current, snapshot, true);
        admit(snapshot);
        var canonicalArguments = json.createObjectNode();
        canonicalArguments.put("expectedPlanRevision", current.plan().revision());
        canonicalArguments.put("expectedCheckpointRevision", oldCheckpoint.revision());
        canonicalArguments.put("nextPlanItemKey", nextKey);
        var canonicalMemory = canonicalArguments.putObject("memory");
        canonicalMemory.set("summary", json.valueToTree(memory.summary()));
        canonicalMemory.set("evidenceReferences", json.valueToTree(memory.evidenceReferences()));
        canonicalArguments.put("phase", phase);
        var result = json.createObjectNode();
        result.put("planRevision", current.plan().revision());
        result.put("checkpointRevision", checkpoint.revision());
        result.put("nextPlanItemKey", nextKey);
        result.put("phase", phase);
        result.set("memory", json.valueToTree(summary));
        return new Prepared(snapshot, canonicalArguments, result);
    }

    /** Screens a request identity before replay lookup without applying stale revision checks. */
    public JsonNode canonicalArguments(String tool, JsonNode arguments,
            Function<String, DurableText> screen,
            Function<List<String>, List<EvidenceReference>> citations) {
        return canonicalArgumentsWithProvenance(tool, arguments, (path, text) -> screen.apply(text), citations);
    }

    /** Screens each argument at its original parser pointer before replay comparison. */
    public JsonNode canonicalArgumentsWithProvenance(String tool, JsonNode arguments,
            BiFunction<String, String, DurableText> screen,
            Function<List<String>, List<EvidenceReference>> citations) {
        Objects.requireNonNull(screen);
        Objects.requireNonNull(citations);
        boolean plan = "set_todos".equals(tool);
        AiAgentContinuationCodec.require(plan || "checkpoint_segment".equals(tool));
        if (plan) {
            AiAgentContinuationCodec.fields(arguments, "expectedPlanRevision", "expectedCheckpointRevision", "items");
        } else {
            AiAgentContinuationCodec.fields(arguments, "expectedPlanRevision", "expectedCheckpointRevision",
                    "nextPlanItemKey", "memory", "phase");
        }
        String planRevision = AiAgentContinuationCodec.string(arguments, "expectedPlanRevision");
        String checkpointRevision = AiAgentContinuationCodec.string(arguments, "expectedCheckpointRevision");
        AiAgentContinuationCodec.decimal(planRevision);
        AiAgentContinuationCodec.decimal(checkpointRevision);
        var canonical = json.createObjectNode();
        canonical.put("expectedPlanRevision", planRevision);
        canonical.put("expectedCheckpointRevision", checkpointRevision);
        if (plan) {
            var array = canonical.putArray("items");
            Set<String> keys = new HashSet<>();
            var requested = AiAgentContinuationCodec.array(arguments, "items");
            AiAgentContinuationCodec.require(requested.size() <= AiAgentContinuationCodec.MAX_ITEMS);
            int active = 0;
            for (int index = 0; index < requested.size(); index++) {
                var item = requested.get(index);
                AiAgentContinuationCodec.fields(item, "key", "label", "status", "completion");
                String key = AiAgentContinuationCodec.nullableString(item, "key");
                if (key != null) {
                    AiAgentContinuationCodec.key(key);
                    AiAgentContinuationCodec.require(keys.add(key));
                }
                String status = AiAgentContinuationCodec.string(item, "status");
                AiAgentContinuationCodec.require(Set.of("pending", "active", "done").contains(status));
                active += "active".equals(status) ? 1 : 0;
                AiAgentContinuationCodec.require(active <= 1);
                DurableText label = screened("/items/" + index + "/label", AiAgentContinuationCodec.string(item, "label"), 400, screen);
                AiAgentContinuationCodec.require(!label.text().isBlank());
                var result = array.addObject();
                result.put("key", key);
                result.set("label", json.valueToTree(label));
                result.put("status", status);
                var completion = item.get("completion");
                AiAgentContinuationCodec.require(completion != null);
                if (completion.isNull()) {
                    result.putNull("completion");
                } else {
                    AiAgentContinuationCodec.require("done".equals(status));
                    AiAgentContinuationCodec.fields(completion, "summary", "citations");
                    var screenedCompletion = result.putObject("completion");
                    DurableText summary = screened("/items/" + index + "/completion/summary", AiAgentContinuationCodec.string(completion, "summary"), 1000, screen);
                    AiAgentContinuationCodec.require(!summary.text().isBlank());
                    var references = List.copyOf(Objects.requireNonNull(citations.apply(
                            AiAgentContinuationCodec.strings(completion, "citations"))));
                    AiAgentContinuationCodec.require(!references.isEmpty());
                    codec.encodeMemory(new AiAgentMemory(1, new DurableText("", List.of()), references));
                    screenedCompletion.set("summary", json.valueToTree(summary));
                    screenedCompletion.set("evidenceReferences", json.valueToTree(references));
                }
            }
        } else {
            String nextKey = AiAgentContinuationCodec.nullableString(arguments, "nextPlanItemKey");
            if (nextKey != null) {
                AiAgentContinuationCodec.key(nextKey);
            }
            String phase = AiAgentContinuationCodec.string(arguments, "phase");
            AiAgentContinuationCodec.require(Set.of("reading", "reporting").contains(phase));
            var memory = arguments.get("memory");
            AiAgentContinuationCodec.fields(memory, "summary", "citations");
            DurableText summary = screened("/memory/summary", AiAgentContinuationCodec.string(memory, "summary"), 8000, screen);
            var references = List.copyOf(Objects.requireNonNull(citations.apply(
                    AiAgentContinuationCodec.strings(memory, "citations"))));
            codec.encodeMemory(new AiAgentMemory(1, summary, references));
            canonical.put("nextPlanItemKey", nextKey);
            var result = canonical.putObject("memory");
            result.set("summary", json.valueToTree(summary));
            result.set("evidenceReferences", json.valueToTree(references));
            canonical.put("phase", phase);
        }
        return canonical;
    }

    /** Projects a canonical stored result through freshly authorized text masking on every replay. */
    public JsonNode providerResult(JsonNode stored, Function<DurableText, String> mask) {
        var result = json.createObjectNode();
        result.put("planRevision", AiAgentContinuationCodec.string(stored, "planRevision"));
        result.put("checkpointRevision", AiAgentContinuationCodec.string(stored, "checkpointRevision"));
        if (stored.has("plan")) {
            var plan = json.treeToValue(stored.get("plan"), AiAgentPlan.class);
            codec.encodePlan(plan);
            result.set("plan", json.valueToTree(modelPlanText(plan, mask)));
        } else {
            result.put("nextPlanItemKey", AiAgentContinuationCodec.nullableString(stored, "nextPlanItemKey"));
            result.put("phase", AiAgentContinuationCodec.string(stored, "phase"));
            var memory = json.treeToValue(stored.get("memory"), DurableText.class);
            codec.encodeMemory(new AiAgentMemory(1, memory, List.of()));
            result.put("memory", Objects.requireNonNull(mask.apply(memory)));
        }
        return result;
    }

    /** Restores precise result-field metadata for the prompt assembler without exposing canonical IDs. */
    public List<ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult.Identifier> resultIdentifiers(JsonNode stored) {
        List<ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult.Identifier> identifiers = new ArrayList<>();
        if (stored.has("plan")) {
            var plan = json.treeToValue(stored.get("plan"), AiAgentPlan.class);
            codec.encodePlan(plan);
            for (int index = 0; index < plan.items().size(); index++) {
                identifiers.addAll(AiAgentContentScreen.identifiers(plan.items().get(index).label(),
                        "/plan/items/" + index + "/label"));
            }
        } else {
            var memory = json.treeToValue(stored.get("memory"), DurableText.class);
            codec.encodeMemory(new AiAgentMemory(1, memory, List.of()));
            identifiers.addAll(AiAgentContentScreen.identifiers(memory, "/memory"));
        }
        return List.copyOf(identifiers);
    }

    private DurableText screened(String path, String value, int maximum, BiFunction<String, String, DurableText> screen) {
        DurableText result = Objects.requireNonNull(screen.apply(path, value));
        AiAgentContinuationCodec.text(result.text(), maximum);
        codec.encodeMemory(new AiAgentMemory(1, result, List.of()));
        return result;
    }

    private void admit(Snapshot snapshot) {
        codec.encodePlan(snapshot.plan());
        codec.encodeCheckpoint(snapshot.checkpoint());
        codec.encodeMemory(snapshot.memory());
    }

    private static AiAgentCheckpoint checkpoint(AiAgentCheckpoint previous, String revision,
            String planRevision, List<CompletedFact> facts, String nextKey, int cursor,
            List<EvidenceReference> inventory, Coverage coverage, Continuation continuation) {
        return new AiAgentCheckpoint(previous.schemaVersion(), previous.definitionVersion(), previous.workspaceId(),
                previous.runId(), previous.scopeFingerprint(), previous.privacyGeneration(), revision, planRevision,
                facts.stream().map(CompletedFact::planItemKey).toList(), nextKey, cursor, facts,
                previous.actionSlotReferences(), inventory, coverage, continuation);
    }

    private static void requireRevisions(Snapshot current, JsonNode arguments) {
        String plan = AiAgentContinuationCodec.string(arguments, "expectedPlanRevision");
        String checkpoint = AiAgentContinuationCodec.string(arguments, "expectedCheckpointRevision");
        AiAgentContinuationCodec.decimal(plan);
        AiAgentContinuationCodec.decimal(checkpoint);
        AiAgentContinuationCodec.require(plan.equals(current.plan().revision())
                && checkpoint.equals(current.checkpoint().revision()));
    }

    private static void requireSegment(Snapshot current, int segmentNo) {
        AiAgentContinuationCodec.require(segmentNo >= 1 && segmentNo <= 8
                && segmentNo == current.checkpoint().lastCommittedSegmentNo() + 1);
    }
}
