package ooo.klae.connex.backend.ai.mission;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.mission.AiAgentCheckpoint.ActionSlotReference;
import ooo.klae.connex.backend.ai.mission.AiAgentCheckpoint.CompletedFact;
import ooo.klae.connex.backend.ai.mission.AiAgentCheckpoint.Continuation;
import ooo.klae.connex.backend.ai.mission.AiAgentCheckpoint.Coverage;
import ooo.klae.connex.backend.ai.mission.AiAgentCheckpoint.CoverageReason;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.DurableText;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.EvidenceReference;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.EvidenceVersion;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.MemberProvenance;
import ooo.klae.connex.backend.beans.AiAgentRun;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict bounded canonical continuation codec; no reconstruction uses fallback defaults. */
@Component
public final class AiAgentContinuationCodec {
    public static final int PLAN_BYTES = 32768;
    public static final int CHECKPOINT_BYTES = 32768;
    public static final int MEMORY_BYTES = 16384;
    public static final int MAX_ITEMS = 20;
    public static final int MAX_EVIDENCE = 64;
    private static final Set<String> RECORD_KINDS = Set.of("person", "company", "deal");
    private static final Set<String> COVERAGE_REASONS = Set.of("access_lost", "source_changed",
            "read_failed", "budget_exhausted", "deadline_exceeded");
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    /** One immutable, mutually validated view carried through the exact claimed execution. */
    public record Snapshot(AiAgentPlan plan, AiAgentCheckpoint checkpoint, AiAgentMemory memory) {
        public Snapshot {
            Objects.requireNonNull(plan);
            Objects.requireNonNull(checkpoint);
            Objects.requireNonNull(memory);
        }
    }

    /** Initializes only a newly created runtime-2 run with its generated immutable identity. */
    public Snapshot initialize(AiAgentRun run) {
        AiAgentMissionCatalog.requireSupported(run);
        require(AiAgentMissionCatalog.continuation(run.getRuntimeContractVersion()) && erased(run));
        require(run.getId() > 0 && run.getWorkspaceId() > 0 && run.getPrivacyGeneration() > 0);
        require(run.getScopeFingerprint() != null && run.getScopeFingerprint().length == 32);
        boolean preparation = AiAgentMissionCatalog.proposals(run);
        var plan = preparation
                ? new AiAgentPlan(1, "1", 2, List.of(new AiAgentPlan.Item("p0001",
                        new DurableText("ja".equals(run.getLocale()) ? "変更案を作成" : "Prepare changes", List.of()), "active")), List.of("p0001"))
                : new AiAgentPlan(1, "1", 1, List.of(), List.of());
        var checkpoint = new AiAgentCheckpoint(preparation ? 2 : 1, 1, run.getWorkspaceId(), run.getId(),
                HexFormat.of().formatHex(run.getScopeFingerprint()), Long.toString(run.getPrivacyGeneration()),
                "1", "1", List.of(), null, 0, List.of(), List.of(), List.of(),
                new Coverage(0, 0, 0, 1, List.of()), new Continuation(run.getMissionType(), 1, "reading"),
                preparation ? new AiAgentSourceObservations(1, List.of()) : null);
        var snapshot = new Snapshot(plan, checkpoint, new AiAgentMemory(1,
                new DurableText("", List.of()), List.of()));
        apply(run, snapshot);
        return snapshot;
    }

    /** Decodes a supported run; legacy execution has no continuation and returns null. */
    public Snapshot decode(AiAgentRun run) {
        AiAgentMissionCatalog.requireSupported(run);
        if (run.getRuntimeContractVersion() == 1) {
            require(erased(run));
            return null;
        }
        require(Objects.equals(run.getPlanSchemaVersion(), 1)
                && Objects.equals(run.getCheckpointSchemaVersion(), AiAgentMissionCatalog.proposals(run) ? 2 : 1)
                && Objects.equals(run.getMemorySchemaVersion(), 1));
        var snapshot = new Snapshot(decodePlan(run.getPlanJson()), decodeCheckpoint(run.getCheckpointJson()),
                decodeMemory(run.getMemoryJson()));
        validate(run, snapshot);
        return snapshot;
    }

    /** Validates exact run bindings and all cross-envelope ledgers without modifying the run. */
    public void validate(AiAgentRun run, Snapshot snapshot) {
        AiAgentMissionCatalog.requireSupported(run);
        require(AiAgentMissionCatalog.continuation(run.getRuntimeContractVersion()) && snapshot != null);
        validatePlan(snapshot.plan());
        validateCheckpoint(snapshot.checkpoint());
        validateMemory(snapshot.memory());
        var plan = snapshot.plan();
        var checkpoint = snapshot.checkpoint();
        require(checkpoint.schemaVersion() == (AiAgentMissionCatalog.proposals(run) ? 2 : 1));
        require(run.getWorkspaceId() == checkpoint.workspaceId() && run.getId() == checkpoint.runId());
        require(run.getDefinitionVersion() == checkpoint.definitionVersion());
        require(run.getScopeFingerprint() != null && run.getScopeFingerprint().length == 32
                && HexFormat.of().formatHex(run.getScopeFingerprint()).equals(checkpoint.scopeFingerprint()));
        require(run.getPrivacyGeneration() == decimal(checkpoint.privacyGeneration()));
        require(run.getPlanRevision() == decimal(plan.revision())
                && run.getPlanRevision() == decimal(checkpoint.planRevision())
                && run.getCheckpointRevision() == decimal(checkpoint.revision())
                && run.getLastCommittedSegmentNo() == checkpoint.lastCommittedSegmentNo());
        Set<String> done = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (var item : plan.items()) {
            keys.add(item.key());
            if ("done".equals(item.status())) {
                done.add(item.key());
            }
        }
        Set<String> completed = new HashSet<>(checkpoint.completedPlanItemKeys());
        Set<String> facts = new HashSet<>();
        for (var fact : checkpoint.completedFacts()) {
            require(facts.add(fact.planItemKey()) && keys.contains(fact.planItemKey()));
            require(fact.recordedSegmentNo() <= Math.min(8, checkpoint.lastCommittedSegmentNo() + 1));
        }
        require(done.equals(completed) && done.equals(facts));
        if (checkpoint.nextPlanItemKey() != null) {
            require(plan.displayOrder().contains(checkpoint.nextPlanItemKey())
                    && !done.contains(checkpoint.nextPlanItemKey()));
        }
        for (var slot : checkpoint.actionSlotReferences()) {
            require(keys.contains(slot.planItemKey()));
        }
        Set<EvidenceReference> canonical = new HashSet<>(checkpoint.evidenceReferences());
        require(canonical.containsAll(snapshot.memory().evidenceReferences()));
        for (var fact : checkpoint.completedFacts()) {
            require(canonical.containsAll(fact.evidenceReferences()));
        }
        JsonNode scope = read(run.getScopeJson(), PLAN_BYTES);
        require(scope.isObject() && "record".equals(string(scope, "kind")));
        String kind = string(scope, "recordKind");
        int id = integer(scope, "recordId");
        require(RECORD_KINDS.contains(kind) && id > 0);
        for (var reference : canonical) {
            require(reference.kind().equals(kind) && reference.id() == id);
        }
        bounded(json.writeValueAsString(plan), PLAN_BYTES);
        bounded(json.writeValueAsString(checkpoint), CHECKPOINT_BYTES);
        bounded(json.writeValueAsString(snapshot.memory()), MEMORY_BYTES);
    }

    /** Runtime 2 has no canonical action inventory and cannot execute reserved references. */
    public void validateExecutable(AiAgentRun run, Snapshot snapshot) {
        if (run.getRuntimeContractVersion() == 1) {
            require(snapshot == null && erased(run));
            AiAgentMissionCatalog.requireSupported(run);
            return;
        }
        validate(run, snapshot);
        require(run.getRuntimeContractVersion() != 2 || snapshot.checkpoint().actionSlotReferences().isEmpty());
    }

    /** Validates append-only identity and fact ledgers at the authoritative write boundary. */
    public void validateTransition(Snapshot previous, Snapshot next, boolean checkpointAdvance) {
        require(previous != null && next != null);
        validatePlan(previous.plan());
        validatePlan(next.plan());
        validateCheckpoint(previous.checkpoint());
        validateCheckpoint(next.checkpoint());
        validateMemory(next.memory());
        var before = previous.checkpoint();
        var after = next.checkpoint();
        require(after.workspaceId() == before.workspaceId() && after.runId() == before.runId()
                && after.definitionVersion() == before.definitionVersion()
                && after.scopeFingerprint().equals(before.scopeFingerprint())
                && after.privacyGeneration().equals(before.privacyGeneration())
                && decimal(after.revision()) == nextRevision(decimal(before.revision())));
        require(after.actionSlotReferences().equals(before.actionSlotReferences())
                && after.evidenceReferences().containsAll(before.evidenceReferences())
                && (before.sourceObservations() == null ? after.sourceObservations() == null
                        : after.sourceObservations() != null
                            && after.sourceObservations().merge(before.sourceObservations()).equals(after.sourceObservations()))
                && after.completedFacts().size() >= before.completedFacts().size()
                && after.completedFacts().subList(0, before.completedFacts().size()).equals(before.completedFacts())
                && after.completedPlanItemKeys().size() >= before.completedPlanItemKeys().size()
                && after.completedPlanItemKeys().subList(0, before.completedPlanItemKeys().size())
                        .equals(before.completedPlanItemKeys()));
        require(after.planRevision().equals(next.plan().revision()));
        if (checkpointAdvance) {
            require(previous.plan().equals(next.plan()) && after.lastCommittedSegmentNo() == before.lastCommittedSegmentNo() + 1
                    && after.completedFacts().equals(before.completedFacts())
                    && after.completedPlanItemKeys().equals(before.completedPlanItemKeys()));
        } else {
            require(decimal(next.plan().revision()) == nextRevision(decimal(previous.plan().revision()))
                    && after.lastCommittedSegmentNo() == before.lastCommittedSegmentNo()
                    && next.memory().equals(previous.memory())
                    && after.coverage().equals(before.coverage())
                    && after.continuation().equals(before.continuation())
                    && next.plan().nextItemOrdinal() >= previous.plan().nextItemOrdinal());
            for (var item : previous.plan().items()) {
                var replacement = next.plan().items().stream().filter(candidate -> candidate.key().equals(item.key()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("mission_continuation_refused"));
                require(!"done".equals(item.status()) || "done".equals(replacement.status()));
            }
        }
    }

    /** Atomically prepares every serialized value before copying it onto the mutable run bean. */
    public void apply(AiAgentRun run, Snapshot snapshot) {
        String plan = encodePlan(snapshot.plan());
        String checkpoint = encodeCheckpoint(snapshot.checkpoint());
        String memory = encodeMemory(snapshot.memory());
        long planRevision = decimal(snapshot.plan().revision());
        long checkpointRevision = decimal(snapshot.checkpoint().revision());
        AiAgentRun candidate = copyBinding(run, planRevision, checkpointRevision,
                snapshot.checkpoint().lastCommittedSegmentNo());
        validate(candidate, snapshot);
        run.setPlanSchemaVersion(1);
        run.setPlanRevision(planRevision);
        run.setPlanJson(plan);
        run.setCheckpointSchemaVersion(snapshot.checkpoint().schemaVersion());
        run.setCheckpointRevision(checkpointRevision);
        run.setCheckpointJson(checkpoint);
        run.setMemorySchemaVersion(1);
        run.setMemoryJson(memory);
        run.setLastCommittedSegmentNo(snapshot.checkpoint().lastCommittedSegmentNo());
    }

    /** Encodes a complete plan envelope with deterministic record-property and array order. */
    public String encodePlan(AiAgentPlan plan) {
        validatePlan(plan);
        return bounded(json.writeValueAsString(plan), PLAN_BYTES);
    }

    /** Encodes the complete checkpoint, including immutable facts and provenance. */
    public String encodeCheckpoint(AiAgentCheckpoint checkpoint) {
        validateCheckpoint(checkpoint);
        return bounded(json.writeValueAsString(checkpoint), CHECKPOINT_BYTES);
    }

    /** Encodes complete screened memory rather than estimating its prose length. */
    public String encodeMemory(AiAgentMemory memory) {
        validateMemory(memory);
        return bounded(json.writeValueAsString(memory), MEMORY_BYTES);
    }

    /** Strictly reads a plan without coercion, unknown fields, or missing properties. */
    public AiAgentPlan decodePlan(String value) {
        var node = read(value, PLAN_BYTES);
        fields(node, "schemaVersion", "revision", "nextItemOrdinal", "items", "displayOrder");
        List<AiAgentPlan.Item> items = new ArrayList<>();
        for (var item : array(node, "items")) {
            fields(item, "key", "label", "status");
            items.add(new AiAgentPlan.Item(string(item, "key"), durable(item.get("label")), string(item, "status")));
        }
        var plan = new AiAgentPlan(integer(node, "schemaVersion"), string(node, "revision"),
                integer(node, "nextItemOrdinal"), items, strings(node, "displayOrder"));
        validatePlan(plan);
        return plan;
    }

    /** Strictly reads a memory envelope with all canonical evidence bindings retained. */
    public AiAgentMemory decodeMemory(String value) {
        var node = read(value, MEMORY_BYTES);
        fields(node, "schemaVersion", "summary", "evidenceReferences");
        var memory = new AiAgentMemory(integer(node, "schemaVersion"), durable(node.get("summary")),
                references(node, "evidenceReferences"));
        validateMemory(memory);
        return memory;
    }

    /** Strictly reads the complete execution ledger without accepting replacement defaults. */
    public AiAgentCheckpoint decodeCheckpoint(String value) {
        var node = read(value, CHECKPOINT_BYTES);
        if (integer(node, "schemaVersion") == 2) {
            fields(node, "schemaVersion", "definitionVersion", "workspaceId", "runId", "scopeFingerprint",
                    "privacyGeneration", "revision", "planRevision", "completedPlanItemKeys", "nextPlanItemKey",
                    "lastCommittedSegmentNo", "completedFacts", "actionSlotReferences", "evidenceReferences",
                    "coverage", "continuation", "sourceObservations");
        } else {
        fields(node, "schemaVersion", "definitionVersion", "workspaceId", "runId", "scopeFingerprint",
                "privacyGeneration", "revision", "planRevision", "completedPlanItemKeys", "nextPlanItemKey",
                "lastCommittedSegmentNo", "completedFacts", "actionSlotReferences", "evidenceReferences",
                "coverage", "continuation");
        }
        List<CompletedFact> facts = new ArrayList<>();
        for (var fact : array(node, "completedFacts")) {
            fields(fact, "planItemKey", "summary", "evidenceReferences", "recordedSegmentNo");
            facts.add(new CompletedFact(string(fact, "planItemKey"), durable(fact.get("summary")),
                    references(fact, "evidenceReferences"), integer(fact, "recordedSegmentNo")));
        }
        List<ActionSlotReference> slots = new ArrayList<>();
        for (var slot : array(node, "actionSlotReferences")) {
            fields(slot, "slotKey", "planItemKey");
            slots.add(new ActionSlotReference(string(slot, "slotKey"), string(slot, "planItemKey")));
        }
        var coverage = node.get("coverage");
        fields(coverage, "processed", "skipped", "failed", "unprocessed", "reasons");
        List<CoverageReason> reasons = new ArrayList<>();
        for (var reason : array(coverage, "reasons")) {
            fields(reason, "code", "count");
            reasons.add(new CoverageReason(string(reason, "code"), integer(reason, "count")));
        }
        var continuation = node.get("continuation");
        fields(continuation, "type", "version", "phase");
        var checkpoint = new AiAgentCheckpoint(integer(node, "schemaVersion"), integer(node, "definitionVersion"),
                integer(node, "workspaceId"), integer(node, "runId"), string(node, "scopeFingerprint"),
                string(node, "privacyGeneration"), string(node, "revision"), string(node, "planRevision"),
                strings(node, "completedPlanItemKeys"), nullableString(node, "nextPlanItemKey"),
                integer(node, "lastCommittedSegmentNo"), facts, slots, references(node, "evidenceReferences"),
                new Coverage(integer(coverage, "processed"), integer(coverage, "skipped"),
                        integer(coverage, "failed"), integer(coverage, "unprocessed"), reasons),
                new Continuation(string(continuation, "type"), integer(continuation, "version"),
                        string(continuation, "phase")), integer(node, "schemaVersion") == 2
                        ? new AiAgentSourceObservationsCodec().decodeNode(node.get("sourceObservations")) : null);
        validateCheckpoint(checkpoint);
        return checkpoint;
    }

    /** Positive canonical decimal identity refuses signed overflow and leading zeros. */
    public static long decimal(String value) {
        require(value != null && value.matches("[1-9][0-9]*"));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("mission_continuation_refused");
        }
    }

    /** Refuses wraparound before a durable generation or revision can be incremented. */
    public static long nextRevision(long current) {
        require(current > 0 && current < Long.MAX_VALUE);
        return current + 1;
    }

    /** Checks the existing normalized goal boundary without changing its UTF-16 semantics. */
    public static void validateGoal(String goal) {
        text(goal, 16000);
        require(!goal.isBlank());
    }

    /** Minimal tombstones and runtime-1 rows must contain no continuation holdings. */
    public static boolean erased(AiAgentRun run) {
        return run.getPlanSchemaVersion() == null && run.getPlanRevision() == 0 && run.getPlanJson() == null
                && run.getCheckpointSchemaVersion() == null && run.getCheckpointRevision() == 0
                && run.getCheckpointJson() == null && run.getMemorySchemaVersion() == null
                && run.getMemoryJson() == null && run.getLastCommittedSegmentNo() == 0
                && run.getProposalMembershipRevision() == 0 && run.getProposalFrozenAt() == null
                && run.getProposalFirstPreparedAt() == null && run.getReviewExpiresAt() == null;
    }

    private static AiAgentRun copyBinding(AiAgentRun run, long planRevision, long checkpointRevision, int cursor) {
        var candidate = new AiAgentRun();
        candidate.setId(run.getId());
        candidate.setWorkspaceId(run.getWorkspaceId());
        candidate.setMissionType(run.getMissionType());
        candidate.setDefinitionVersion(run.getDefinitionVersion());
        candidate.setRuntimeContractVersion(run.getRuntimeContractVersion());
        candidate.setScopeFingerprint(run.getScopeFingerprint());
        candidate.setScopeJson(run.getScopeJson());
        candidate.setPrivacyGeneration(run.getPrivacyGeneration());
        candidate.setPlanRevision(planRevision);
        candidate.setCheckpointRevision(checkpointRevision);
        candidate.setLastCommittedSegmentNo(cursor);
        return candidate;
    }

    private static void validatePlan(AiAgentPlan plan) {
        require(plan != null && plan.schemaVersion() == 1);
        decimal(plan.revision());
        require(plan.nextItemOrdinal() >= 1 && plan.nextItemOrdinal() <= 21
                && plan.items().size() == plan.nextItemOrdinal() - 1);
        Set<String> keys = new HashSet<>();
        for (var item : plan.items()) {
            key(item.key());
            require(keys.add(item.key()));
            durable(item.label(), 400);
            require(Set.of("pending", "active", "done").contains(item.status()));
        }
        for (int ordinal = 1; ordinal < plan.nextItemOrdinal(); ordinal++) {
            require(keys.contains(planKey(ordinal)));
        }
        unique(plan.displayOrder());
        require(keys.containsAll(plan.displayOrder()));
        long active = plan.items().stream().filter(item -> plan.displayOrder().contains(item.key()))
                .filter(item -> "active".equals(item.status())).count();
        require(active <= 1);
    }

    private static void validateMemory(AiAgentMemory memory) {
        require(memory != null && memory.schemaVersion() == 1);
        durable(memory.summary(), 8000);
        evidence(memory.evidenceReferences());
    }

    private static void validateCheckpoint(AiAgentCheckpoint checkpoint) {
        require(checkpoint != null && (checkpoint.schemaVersion() == 1 || checkpoint.schemaVersion() == 2) && checkpoint.definitionVersion() == 1
                && checkpoint.workspaceId() > 0 && checkpoint.runId() > 0);
        require(checkpoint.scopeFingerprint().matches("[0-9a-f]{64}"));
        decimal(checkpoint.privacyGeneration());
        decimal(checkpoint.revision());
        decimal(checkpoint.planRevision());
        require(checkpoint.lastCommittedSegmentNo() >= 0 && checkpoint.lastCommittedSegmentNo() <= 8);
        require(checkpoint.completedPlanItemKeys().size() <= MAX_ITEMS
                && checkpoint.completedFacts().size() <= MAX_ITEMS);
        unique(checkpoint.completedPlanItemKeys());
        checkpoint.completedPlanItemKeys().forEach(AiAgentContinuationCodec::key);
        if (checkpoint.nextPlanItemKey() != null) {
            key(checkpoint.nextPlanItemKey());
        }
        Set<String> factKeys = new HashSet<>();
        for (var fact : checkpoint.completedFacts()) {
            key(fact.planItemKey());
            require(factKeys.add(fact.planItemKey()));
            durable(fact.summary(), 1000);
            evidence(fact.evidenceReferences());
            require(fact.recordedSegmentNo() >= 1 && fact.recordedSegmentNo() <= 8);
        }
        require(factKeys.equals(new HashSet<>(checkpoint.completedPlanItemKeys())));
        require(checkpoint.actionSlotReferences().size() <= (checkpoint.schemaVersion() == 2 ? 50 : MAX_ITEMS));
        Set<String> slots = new HashSet<>();
        for (var slot : checkpoint.actionSlotReferences()) {
            key(slot.planItemKey());
            require(slots.add(slot.slotKey()));
            require(slot.slotKey().equals(UUID.fromString(slot.slotKey()).toString()));
        }
        evidence(checkpoint.evidenceReferences());
        var coverage = checkpoint.coverage();
        require(coverage.processed() >= 0 && coverage.skipped() >= 0 && coverage.failed() >= 0
                && coverage.unprocessed() >= 0 && (long) coverage.processed() + coverage.skipped()
                + coverage.failed() + coverage.unprocessed() == 1);
        Set<String> reasons = new HashSet<>();
        long reasonCount = 0;
        for (var reason : coverage.reasons()) {
            require(COVERAGE_REASONS.contains(reason.code()) && reasons.add(reason.code())
                    && reason.count() >= 0 && reason.count() <= 1);
            reasonCount += reason.count();
        }
        require(reasonCount <= 1);
        var continuation = checkpoint.continuation();
        require((checkpoint.schemaVersion() == 1 ? "record_review_v1" : "record_preparation_v1")
                .equals(continuation.type()) && continuation.version() == 1
                && Set.of("reading", "reporting", "preparing_changes").contains(continuation.phase()));
        require(checkpoint.schemaVersion() == 2 ? checkpoint.sourceObservations() != null
                : checkpoint.sourceObservations() == null);
        if (checkpoint.sourceObservations() != null) {
            var observed = checkpoint.sourceObservations().sources().stream().map(AiAgentSourceObservations.Observation::reference).toList();
            require(observed.containsAll(checkpoint.evidenceReferences()));
        }
    }

    private static void evidence(List<EvidenceReference> references) {
        require(references.size() <= MAX_EVIDENCE);
        unique(references);
        for (var reference : references) {
            require(RECORD_KINDS.contains(reference.kind()) && reference.id() > 0);
            var version = reference.version();
            require(version.sha256().matches("[0-9a-f]{64}"));
            require(("record".equals(version.source()) && version.limit() == 0)
                    || (Set.of("activities", "tasks").contains(version.source())
                    && version.limit() >= 1 && version.limit() <= 20));
        }
    }

    private static void durable(DurableText value, int maximum) {
        require(value != null);
        text(value.text(), maximum);
        require(value.memberProvenance().size() <= 20);
        Set<MemberProvenance> provenance = new HashSet<>();
        for (var member : value.memberProvenance()) {
            require(member.userId() > 0 && provenance.add(member));
            text(member.originalLabel(), 200);
            require(!member.originalLabel().isBlank());
        }
    }

    private static DurableText durable(JsonNode node) {
        fields(node, "text", "memberProvenance");
        List<MemberProvenance> members = new ArrayList<>();
        for (var member : array(node, "memberProvenance")) {
            fields(member, "userId", "originalLabel");
            members.add(new MemberProvenance(integer(member, "userId"), string(member, "originalLabel")));
        }
        return new DurableText(string(node, "text"), members);
    }

    private static List<EvidenceReference> references(JsonNode node, String field) {
        List<EvidenceReference> result = new ArrayList<>();
        for (var reference : array(node, field)) {
            fields(reference, "kind", "id", "version");
            var version = reference.get("version");
            fields(version, "source", "limit", "sha256");
            result.add(new EvidenceReference(string(reference, "kind"), integer(reference, "id"),
                    new EvidenceVersion(string(version, "source"), integer(version, "limit"), string(version, "sha256"))));
        }
        return List.copyOf(result);
    }

    private JsonNode read(String value, int maximum) {
        bounded(value, maximum);
        try {
            return json.readTree(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("mission_continuation_refused");
        }
    }

    private static String bounded(String value, int maximum) {
        text(value, maximum);
        require(value.getBytes(StandardCharsets.UTF_8).length <= maximum);
        return value;
    }

    static void fields(JsonNode node, String... names) {
        require(node != null && node.isObject() && node.size() == names.length);
        for (String name : names) {
            require(node.has(name));
        }
    }

    static String string(JsonNode node, String field) {
        var value = node.get(field);
        require(value != null && value.isString());
        String result = value.stringValue();
        text(result, Integer.MAX_VALUE);
        return result;
    }

    static String nullableString(JsonNode node, String field) {
        var value = node.get(field);
        require(value != null);
        return value.isNull() ? null : string(node, field);
    }

    static int integer(JsonNode node, String field) {
        var value = node.get(field);
        require(value != null && value.isIntegralNumber() && value.canConvertToInt());
        return value.intValue();
    }

    static JsonNode array(JsonNode node, String field) {
        var value = node.get(field);
        require(value != null && value.isArray());
        return value;
    }

    static List<String> strings(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        for (var value : array(node, field)) {
            require(value.isString());
            text(value.stringValue(), Integer.MAX_VALUE);
            result.add(value.stringValue());
        }
        return List.copyOf(result);
    }

    static void text(String value, int maximum) {
        require(value != null && value.length() <= maximum);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                require(index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1)));
                index++;
            } else {
                require(!Character.isLowSurrogate(current));
            }
        }
    }

    static void key(String value) {
        require(value != null && value.matches("p00(?:0[1-9]|1[0-9]|20)"));
    }

    static String planKey(int ordinal) {
        require(ordinal >= 1 && ordinal <= MAX_ITEMS);
        return String.format(java.util.Locale.ROOT, "p%04d", ordinal);
    }

    private static <T> void unique(List<T> values) {
        require(new LinkedHashSet<>(values).size() == values.size());
    }

    static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("mission_continuation_refused");
        }
    }
}
