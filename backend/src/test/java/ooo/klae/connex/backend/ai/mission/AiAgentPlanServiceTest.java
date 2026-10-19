package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.mission.AiAgentCheckpoint.ActionSlotReference;
import ooo.klae.connex.backend.ai.mission.AiAgentCheckpoint.CompletedFact;
import ooo.klae.connex.backend.ai.mission.AiAgentContinuationCodec.Snapshot;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.DurableText;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.EvidenceReference;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.MemberProvenance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AiAgentPlanServiceTest {
    private final AiAgentContinuationCodec codec = new AiAgentContinuationCodec();
    private final AiAgentPlanService plans = new AiAgentPlanService(codec);
    private final JsonMapper json = JsonMapper.builder().build();
    private final Function<String, DurableText> screen = AiAgentContinuationCodecTest::text;
    private final Function<List<String>, List<EvidenceReference>> citations =
            handles -> handles.isEmpty() ? List.of() : List.of(AiAgentContinuationCodecTest.reference(1));

    @Test
    void labelEditsAndReorderingRetainServerKeys() {
        var first = plans.prepare(initial(), request("1", "1", item(null, "Read", "pending", null),
                item(null, "Summarize", "pending", null)), 1, screen, citations);
        assertEquals(List.of("p0001", "p0002"), first.snapshot().plan().displayOrder());
        var second = plans.prepare(first.snapshot(), request("2", "2",
                item("p0002", "Prepare final report", "active", null), item("p0001", "Read carefully", "pending", null)),
                1, screen, citations);
        assertEquals(List.of("p0002", "p0001"), second.snapshot().plan().displayOrder());
        assertEquals("Read carefully", second.snapshot().plan().items().getFirst().label().text());
        assertEquals("p0001", second.snapshot().plan().items().getFirst().key());
        assertEquals(3, second.snapshot().plan().nextItemOrdinal());
        assertEquals(0, second.snapshot().checkpoint().lastCommittedSegmentNo());
        assertNotEquals(first.snapshot().checkpoint().revision(), second.snapshot().checkpoint().revision());
    }

    @Test
    void replacementPreservesCompletedFactsAndRejectsDemotion() {
        var first = completed();
        CompletedFact fact = first.checkpoint().completedFacts().getFirst();
        var hidden = plans.prepare(first, request("2", "2"), 1, screen, citations).snapshot();
        assertTrue(hidden.plan().displayOrder().isEmpty());
        assertEquals(List.of(fact), hidden.checkpoint().completedFacts());
        var renamed = plans.prepare(hidden, request("3", "3", item("p0001", "Renamed", "done", null)),
                1, screen, citations).snapshot();
        assertEquals(List.of(fact), renamed.checkpoint().completedFacts());
        assertThrows(IllegalArgumentException.class, () -> plans.prepare(renamed,
                request("4", "4", item("p0001", "Renamed", "pending", null)), 1, screen, citations));
        assertThrows(IllegalArgumentException.class, () -> plans.prepare(renamed,
                request("4", "4", item("p0001", "Renamed", "done", completion("Different"))), 1, screen, citations));
        var identical = plans.prepare(renamed,
                request("4", "4", item("p0001", "Renamed", "done", completion("Observed fact"))), 1, screen, citations);
        assertEquals(List.of(fact), identical.snapshot().checkpoint().completedFacts());
    }

    @Test
    void replacementPreservesServerOwnedActionReferences() {
        var before = completed();
        var cp = before.checkpoint();
        var slots = List.of(new ActionSlotReference("12345678-1234-1234-1234-123456789abc", "p0001"));
        var checkpoint = new AiAgentCheckpoint(cp.schemaVersion(), cp.definitionVersion(), cp.workspaceId(), cp.runId(),
                cp.scopeFingerprint(), cp.privacyGeneration(), cp.revision(), cp.planRevision(), cp.completedPlanItemKeys(),
                cp.nextPlanItemKey(), cp.lastCommittedSegmentNo(), cp.completedFacts(), slots, cp.evidenceReferences(),
                cp.coverage(), cp.continuation());
        var reserved = new Snapshot(before.plan(), checkpoint, before.memory());
        var after = plans.prepare(reserved, request("2", "2"), 1, screen, citations).snapshot();
        assertEquals(slots, after.checkpoint().actionSlotReferences());
        var run = AiAgentContinuationCodecTest.run();
        codec.apply(run, after);
        assertThrows(IllegalArgumentException.class, () -> codec.validateExecutable(run, after));
    }

    @Test
    void hiddenItemsStillConsumeLifetimeItemLimit() {
        Snapshot current = initial();
        for (int index = 0; index < 20; index++) {
            current = plans.prepare(current, request(current.plan().revision(), current.checkpoint().revision(),
                    item(null, "New item", "pending", null)), 1, screen, citations).snapshot();
        }
        assertEquals(20, current.plan().items().size());
        assertEquals(1, current.plan().displayOrder().size());
        Snapshot full = current;
        assertThrows(IllegalArgumentException.class, () -> plans.prepare(full,
                request(full.plan().revision(), full.checkpoint().revision(), item(null, "Twenty one", "pending", null)),
                1, screen, citations));
    }

    @Test
    void providerPlanProjectionContainsOnlyFreshMaskedProseAndPlanIdentities() {
        var snapshot = plans.prepare(initial(), request("1", "1", item(null, "Original Member", "pending", null)),
                1, value -> new DurableText(value, List.of(new MemberProvenance(987654321, "Original Member"))), citations).snapshot();
        String providerJson = json.writeValueAsString(plans.modelPlan(snapshot.plan(), value -> "{MEMBER_1}"));
        assertTrue(providerJson.contains("{MEMBER_1}"));
        assertTrue(providerJson.contains("p0001"));
        for (String forbidden : List.of("987654321", "Original Member", "userId", "originalLabel", "memberProvenance",
                "evidenceReferences", "scopeFingerprint")) {
            assertFalse(providerJson.contains(forbidden), forbidden);
        }
        assertTrue(codec.encodePlan(snapshot.plan()).contains("987654321"));
    }

    @Test
    void strictArgumentsRejectUnknownKeysFieldsStaleRevisionsAndUnscreenedCompletion() {
        var initial = initial();
        assertThrows(IllegalArgumentException.class, () -> plans.prepare(initial,
                request("1", "1", item("p0001", "Unknown", "pending", null)), 1, screen, citations));
        assertThrows(IllegalArgumentException.class, () -> plans.prepare(initial,
                request("2", "1"), 1, screen, citations));
        var unknown = request("1", "1").deepCopy();
        unknown.put("extra", true);
        assertThrows(IllegalArgumentException.class, () -> plans.prepare(initial, unknown, 1, screen, citations));
        assertThrows(IllegalArgumentException.class, () -> plans.prepare(initial,
                request("1", "1", item(null, "Incomplete", "done", null)), 1, screen, citations));
        assertThrows(IllegalArgumentException.class, () -> plans.prepare(initial,
                request("1", "1", item(null, "Rejected", "done", completion("Sensitive"))), 1,
                value -> { throw new IllegalArgumentException("mission_content_refused"); }, citations));
        assertEquals("1", initial.plan().revision());
        assertTrue(initial.checkpoint().completedFacts().isEmpty());
    }

    @Test
    void checkpointAdvancesOnlyTheCursorAndMemoryWhilePreservingFacts() {
        var first = completed();
        var args = json.createObjectNode();
        args.put("expectedPlanRevision", "2");
        args.put("expectedCheckpointRevision", "2");
        args.putNull("nextPlanItemKey");
        args.put("phase", "reporting");
        var memory = args.putObject("memory");
        memory.put("summary", "日本語😀 durable memory");
        memory.putArray("citations").add("record_1");
        var prepared = plans.prepareCheckpoint(first, args, 1, screen, citations);
        assertEquals(1, prepared.snapshot().checkpoint().lastCommittedSegmentNo());
        assertEquals("3", prepared.snapshot().checkpoint().revision());
        assertEquals("2", prepared.snapshot().plan().revision());
        assertEquals(first.checkpoint().completedFacts(), prepared.snapshot().checkpoint().completedFacts());
        assertEquals("日本語😀 durable memory", prepared.snapshot().memory().summary().text());
        assertEquals(prepared.arguments(), plans.canonicalArguments("checkpoint_segment", args, screen, citations));
        assertThrows(IllegalArgumentException.class, () -> plans.prepareCheckpoint(prepared.snapshot(), args, 1, screen, citations));
    }

    @Test
    void canonicalReplayIdentityIsScreenedAndIndependentOfCurrentRevisions() {
        var args = request("1", "1", item(null, "Member label", "done", completion("Observed fact")));
        var prepared = plans.prepare(initial(), args, 1, screen, citations);
        var canonical = plans.canonicalArguments("set_todos", args, screen, citations);
        assertEquals(prepared.arguments(), canonical);
        assertFalse(json.writeValueAsString(canonical).contains("record_1"));
        assertTrue(json.writeValueAsString(canonical).contains("evidenceReferences"));
        assertEquals(canonical, plans.canonicalArguments("set_todos", args, screen, citations));
        assertThrows(IllegalArgumentException.class, () -> plans.prepare(prepared.snapshot(), args, 1, screen, citations));
        var modifiedCopy = prepared.arguments();
        modifiedCopy.get("items").get(0).get("label").asObject().put("text", "Mutated");
        assertNotEquals(modifiedCopy, prepared.arguments());
    }

    @Test
    void authoritativeTransitionRejectsDroppedKeysAndReplacedFacts() {
        var before = completed();
        var after = plans.prepare(before, request("2", "2"), 1, screen, citations).snapshot();
        var removed = new Snapshot(new AiAgentPlan(1, "3", 1, List.of(), List.of()), after.checkpoint(), after.memory());
        assertThrows(IllegalArgumentException.class, () -> codec.validateTransition(before, removed, false));
        var changedFact = new CompletedFact("p0001", AiAgentContinuationCodecTest.text("Changed"),
                before.checkpoint().completedFacts().getFirst().evidenceReferences(), 1);
        var changedCheckpoint = AiAgentContinuationCodecTest.checkpoint(after.checkpoint(), List.of(changedFact),
                after.checkpoint().evidenceReferences());
        assertThrows(IllegalArgumentException.class,
                () -> codec.validateTransition(before, new Snapshot(after.plan(), changedCheckpoint, after.memory()), false));
    }

    private Snapshot initial() {
        return codec.initialize(AiAgentContinuationCodecTest.run());
    }

    private Snapshot completed() {
        return plans.prepare(initial(), request("1", "1", item(null, "Review", "done", completion("Observed fact"))),
                1, screen, citations).snapshot();
    }

    private tools.jackson.databind.node.ObjectNode request(String planRevision, String checkpointRevision, JsonNode... items) {
        var result = json.createObjectNode();
        result.put("expectedPlanRevision", planRevision);
        result.put("expectedCheckpointRevision", checkpointRevision);
        var array = result.putArray("items");
        for (var item : items) {
            array.add(item);
        }
        return result;
    }

    private JsonNode item(String key, String label, String status, JsonNode completion) {
        var result = json.createObjectNode();
        result.put("key", key);
        result.put("label", label);
        result.put("status", status);
        result.set("completion", completion == null ? json.nullNode() : completion);
        return result;
    }

    private JsonNode completion(String summary) {
        var completion = json.createObjectNode();
        completion.put("summary", summary);
        completion.putArray("citations").add("record_1");
        return completion;
    }
}
