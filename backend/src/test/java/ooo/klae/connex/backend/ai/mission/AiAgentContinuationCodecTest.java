package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.mission.AiAgentCheckpoint.CompletedFact;
import ooo.klae.connex.backend.ai.mission.AiAgentCheckpoint.Continuation;
import ooo.klae.connex.backend.ai.mission.AiAgentCheckpoint.Coverage;
import ooo.klae.connex.backend.ai.mission.AiAgentContinuationCodec.Snapshot;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.DurableText;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.EvidenceReference;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.EvidenceVersion;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.MemberProvenance;
import ooo.klae.connex.backend.beans.AiAgentRun;
import tools.jackson.databind.json.JsonMapper;

class AiAgentContinuationCodecTest {
    private final AiAgentContinuationCodec codec = new AiAgentContinuationCodec();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void unknownSchemaDefinitionRuntimeAndContinuationVersionsFailClosed() {
        for (String dimension : List.of("definition", "runtime", "planColumn", "checkpointColumn", "memoryColumn",
                "planEnvelope", "checkpointEnvelope", "memoryEnvelope", "continuation", "mission", "missingPlan",
                "missingCheckpoint", "missingMemory")) {
            var run = run();
            codec.initialize(run);
            switch (dimension) {
                case "definition" -> run.setDefinitionVersion(2);
                case "runtime" -> run.setRuntimeContractVersion(3);
                case "planColumn" -> run.setPlanSchemaVersion(2);
                case "checkpointColumn" -> run.setCheckpointSchemaVersion(2);
                case "memoryColumn" -> run.setMemorySchemaVersion(2);
                case "planEnvelope" -> run.setPlanJson(run.getPlanJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
                case "checkpointEnvelope" -> run.setCheckpointJson(run.getCheckpointJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
                case "memoryEnvelope" -> run.setMemoryJson(run.getMemoryJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
                case "continuation" -> run.setCheckpointJson(run.getCheckpointJson().replace("\"version\":1", "\"version\":2"));
                case "mission" -> run.setMissionType("record_preparation_v1");
                case "missingPlan" -> run.setPlanJson(null);
                case "missingCheckpoint" -> run.setCheckpointJson(null);
                case "missingMemory" -> run.setMemoryJson(null);
                default -> throw new AssertionError(dimension);
            }
            assertThrows(RuntimeException.class, () -> codec.decode(run), dimension);
        }
    }

    @Test
    void exactByteAndItemLimitsAcceptAndOneOverRefuses() {
        var plan = planBytes(32768);
        String encodedPlan = codec.encodePlan(plan);
        assertEquals(32768, bytes(encodedPlan));
        assertEquals(plan, codec.decodePlan(encodedPlan));
        assertThrows(IllegalArgumentException.class, () -> codec.encodePlan(planBytes(32769)));
        assertThrows(IllegalArgumentException.class, () -> codec.decodePlan(json.writeValueAsString(planBytes(32769))));

        var checkpoint = checkpointBytes(32768);
        String encodedCheckpoint = codec.encodeCheckpoint(checkpoint);
        assertEquals(32768, bytes(encodedCheckpoint));
        assertEquals(checkpoint, codec.decodeCheckpoint(encodedCheckpoint));
        assertThrows(IllegalArgumentException.class, () -> codec.encodeCheckpoint(checkpointBytes(32769)));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeCheckpoint(json.writeValueAsString(checkpointBytes(32769))));

        var memory = memoryBytes(16384);
        String encodedMemory = codec.encodeMemory(memory);
        assertEquals(16384, bytes(encodedMemory));
        assertEquals(memory, codec.decodeMemory(encodedMemory));
        assertThrows(IllegalArgumentException.class, () -> codec.encodeMemory(memoryBytes(16385)));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeMemory(json.writeValueAsString(memoryBytes(16385))));
        assertEquals(20, codec.decodePlan(encodedPlan).items().size());
        var twentyOne = new ArrayList<>(plan.items());
        twentyOne.add(new AiAgentPlan.Item("p0021", text("extra"), "pending"));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encodePlan(new AiAgentPlan(1, "1", 22, twentyOne, List.of())));
    }

    @Test
    void rejectsUnknownFieldsDuplicatePropertiesAndMalformedUnicode() {
        String valid = codec.encodeMemory(new AiAgentMemory(1, text("valid"), List.of()));
        assertThrows(RuntimeException.class, () -> codec.decodeMemory(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"extra\":0")));
        assertThrows(RuntimeException.class, () -> codec.decodeMemory(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")));
        assertThrows(RuntimeException.class, () -> codec.decodeMemory(valid + " {}"));
        assertThrows(RuntimeException.class, () -> codec.decodeMemory(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")));
        assertThrows(RuntimeException.class, () -> codec.decodeMemory(valid.replace("\"schemaVersion\":1,", "")));
        assertThrows(RuntimeException.class, () -> codec.decodeMemory(valid.replace("valid", "\\ud800")));
        assertThrows(RuntimeException.class, () -> codec.encodeMemory(new AiAgentMemory(1, text("bad\ud800"), List.of())));
        assertThrows(RuntimeException.class, () -> codec.encodeMemory(new AiAgentMemory(1, text("bad\udc00"), List.of())));
        assertEquals("日本語😀", codec.decodeMemory(codec.encodeMemory(new AiAgentMemory(1, text("日本語😀"), List.of()))).summary().text());
    }

    @Test
    void goalLimitRemainsSixteenThousandCharacters() {
        AiAgentContinuationCodec.validateGoal("あ".repeat(16000));
        AiAgentContinuationCodec.validateGoal("😀".repeat(8000));
        assertThrows(IllegalArgumentException.class, () -> AiAgentContinuationCodec.validateGoal("x".repeat(16001)));
        assertThrows(IllegalArgumentException.class, () -> AiAgentContinuationCodec.validateGoal("😀".repeat(8001)));
        assertThrows(IllegalArgumentException.class, () -> AiAgentContinuationCodec.validateGoal("\ud800"));
    }

    @Test
    void runBindingsLedgersAndEvidenceReferencesFailClosed() {
        var run = run();
        var snapshot = codec.initialize(run);
        assertEquals(snapshot, codec.decode(run));
        run.setPrivacyGeneration(2);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(run));
        run.setPrivacyGeneration(1);
        run.setCheckpointRevision(2);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(run));
        run.setCheckpointRevision(1);
        run.setScopeJson("{\"kind\":\"record\",\"recordKind\":\"company\",\"recordId\":4}");
        var reference = reference(1);
        var checkpoint = checkpoint(snapshot.checkpoint(), List.of(), List.of(reference));
        codec.apply(run, new Snapshot(snapshot.plan(), checkpoint, snapshot.memory()));
        run.setScopeJson("{\"kind\":\"record\",\"recordKind\":\"company\",\"recordId\":5}");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(run));
        assertThrows(IllegalArgumentException.class, () -> AiAgentContinuationCodec.nextRevision(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> AiAgentContinuationCodec.decimal("01"));
        assertThrows(IllegalArgumentException.class, () -> AiAgentContinuationCodec.decimal("9223372036854775808"));
        assertThrows(IllegalArgumentException.class, () -> AiAgentContinuationCodec.decimal("0"));
    }

    @Test
    void legacyHasNoContinuationAndOnlyInterruptedReadIsAutomaticallyRetryable() {
        var run = run();
        run.setRuntimeContractVersion(1);
        assertNull(codec.decode(run));
        run.setPlanRevision(1);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(run));
        assertTrue(AiAgentMissionCatalog.automaticRetryReason("interrupted"));
        for (String reason : List.of("member", "access_lost", "source_changed", "provider_failed", "budget_exhausted",
                "mission_context_overflow", "content_refused", "deadline_exceeded", "usage_overrun")) {
            assertFalse(AiAgentMissionCatalog.automaticRetryReason(reason));
        }
        assertEquals(1, AiAgentMissionCatalog.maxSegments(1));
        assertEquals(8, AiAgentMissionCatalog.maxSegments(2));
        assertFalse(AiAgentMissionCatalog.tools(1).contains("set_todos"));
        assertTrue(AiAgentMissionCatalog.tools(2).contains("checkpoint_segment"));
    }

    @Test
    void evidenceAndProvenanceLimitsAreIdenticalOnReadAndWrite() {
        List<EvidenceReference> references = new ArrayList<>();
        for (int index = 1; index <= 64; index++) {
            references.add(reference(index));
        }
        var memory = new AiAgentMemory(1, text("Observed"), references);
        assertEquals(memory, codec.decodeMemory(codec.encodeMemory(memory)));
        references.add(reference(65));
        var excessiveEvidence = new AiAgentMemory(1, text("Observed"), references);
        assertThrows(IllegalArgumentException.class, () -> codec.encodeMemory(excessiveEvidence));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeMemory(json.writeValueAsString(excessiveEvidence)));
        List<MemberProvenance> members = new ArrayList<>();
        for (int index = 1; index <= 20; index++) {
            members.add(new MemberProvenance(index, "x".repeat(200)));
        }
        var bounded = new AiAgentMemory(1, new DurableText("x".repeat(8000), members), List.of());
        assertEquals(bounded, codec.decodeMemory(codec.encodeMemory(bounded)));
        members.add(new MemberProvenance(21, "One too many"));
        var excessiveMembers = new AiAgentMemory(1, new DurableText("Observed", members), List.of());
        assertThrows(IllegalArgumentException.class, () -> codec.encodeMemory(excessiveMembers));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeMemory(json.writeValueAsString(excessiveMembers)));
        assertThrows(IllegalArgumentException.class, () -> codec.encodeMemory(new AiAgentMemory(1,
                new DurableText("Observed", List.of(new MemberProvenance(1, "x".repeat(201)))), List.of())));
        assertThrows(IllegalArgumentException.class, () -> codec.encodeMemory(new AiAgentMemory(1, text("x".repeat(8001)), List.of())));
    }

    @Test
    void inconsistentCompletedLedgerOrUnboundEvidenceCannotPartiallyReplaceRunState() {
        var run = run();
        var initial = codec.initialize(run);
        String previousPlan = run.getPlanJson();
        String previousCheckpoint = run.getCheckpointJson();
        var pending = new AiAgentPlan(1, "1", 2,
                List.of(new AiAgentPlan.Item("p0001", text("Pending"), "pending")), List.of("p0001"));
        var fact = new CompletedFact("p0001", text("Claimed completed"), List.of(reference(1)), 1);
        var unbound = checkpoint(initial.checkpoint(), List.of(fact), List.of());
        assertThrows(IllegalArgumentException.class, () -> codec.apply(run,
                new Snapshot(pending, unbound, initial.memory())));
        assertEquals(previousPlan, run.getPlanJson());
        assertEquals(previousCheckpoint, run.getCheckpointJson());
        var done = new AiAgentPlan(1, "1", 2,
                List.of(new AiAgentPlan.Item("p0001", text("Done"), "done")), List.of("p0001"));
        assertThrows(IllegalArgumentException.class, () -> codec.apply(run,
                new Snapshot(done, unbound, initial.memory())));
        assertEquals(previousPlan, run.getPlanJson());
        var bound = checkpoint(initial.checkpoint(), List.of(fact), List.of(reference(1)));
        codec.apply(run, new Snapshot(done, bound, initial.memory()));
        assertEquals(List.of(fact), codec.decode(run).checkpoint().completedFacts());
    }

    static AiAgentRun run() {
        var run = new AiAgentRun();
        run.setId(3);
        run.setWorkspaceId(2);
        run.setMissionType("record_review_v1");
        run.setDefinitionVersion(1);
        run.setRuntimeContractVersion(2);
        run.setPrivacyGeneration(1);
        run.setScopeFingerprint(new byte[32]);
        run.setScopeJson("{\"kind\":\"record\",\"recordKind\":\"company\",\"recordId\":4}");
        return run;
    }

    static DurableText text(String value) {
        return new DurableText(value, List.of());
    }

    static EvidenceReference reference(int hash) {
        return new EvidenceReference("company", 4, new EvidenceVersion("record", 0,
                String.format(java.util.Locale.ROOT, "%064x", hash)));
    }

    static AiAgentCheckpoint checkpoint(AiAgentCheckpoint previous, List<CompletedFact> facts,
            List<EvidenceReference> references) {
        return new AiAgentCheckpoint(1, 1, previous.workspaceId(), previous.runId(), previous.scopeFingerprint(),
                previous.privacyGeneration(), previous.revision(), previous.planRevision(),
                facts.stream().map(CompletedFact::planItemKey).toList(), null, previous.lastCommittedSegmentNo(),
                facts, previous.actionSlotReferences(), references, previous.coverage(), previous.continuation());
    }

    private AiAgentPlan planBytes(int target) {
        var baseline = paddedPlan(0);
        int padding = target - bytes(json.writeValueAsString(baseline));
        assertTrue(padding >= 0 && padding <= 19900);
        var result = paddedPlan(padding);
        assertEquals(target, bytes(json.writeValueAsString(result)));
        return result;
    }

    private static AiAgentPlan paddedPlan(int padding) {
        List<AiAgentPlan.Item> items = new ArrayList<>();
        for (int index = 1; index <= 20; index++) {
            List<MemberProvenance> members = new ArrayList<>();
            for (int member = 1; member <= 5; member++) {
                int add = Math.min(199, padding);
                padding -= add;
                members.add(new MemberProvenance(member, "x".repeat(1 + add)));
            }
            items.add(new AiAgentPlan.Item(AiAgentContinuationCodec.planKey(index),
                    new DurableText("x".repeat(400), members), "pending"));
        }
        return new AiAgentPlan(1, "1", 21, items, items.stream().map(AiAgentPlan.Item::key).toList());
    }

    private AiAgentCheckpoint checkpointBytes(int target) {
        var baseline = paddedCheckpoint(0);
        int padding = target - bytes(json.writeValueAsString(baseline));
        assertTrue(padding >= 0 && padding <= 19900);
        var result = paddedCheckpoint(padding);
        assertEquals(target, bytes(json.writeValueAsString(result)));
        return result;
    }

    private static AiAgentCheckpoint paddedCheckpoint(int padding) {
        List<CompletedFact> facts = new ArrayList<>();
        for (int index = 1; index <= 20; index++) {
            List<MemberProvenance> members = new ArrayList<>();
            for (int member = 1; member <= 5; member++) {
                int add = Math.min(199, padding);
                padding -= add;
                members.add(new MemberProvenance(member, "x".repeat(1 + add)));
            }
            facts.add(new CompletedFact(AiAgentContinuationCodec.planKey(index),
                    new DurableText("x".repeat(1000), members), List.of(), 1));
        }
        return new AiAgentCheckpoint(1, 1, 2, 3, "0".repeat(64), "1", "1", "1",
                facts.stream().map(CompletedFact::planItemKey).toList(), null, 1, facts, List.of(), List.of(),
                new Coverage(1, 0, 0, 0, List.of()), new Continuation("record_review_v1", 1, "reading"));
    }

    private AiAgentMemory memoryBytes(int target) {
        var baseline = new AiAgentMemory(1, text(""), List.of());
        int proseBytes = target - bytes(json.writeValueAsString(baseline));
        int japanese = proseBytes / 3;
        int ascii = proseBytes % 3;
        return new AiAgentMemory(1, text("あ".repeat(japanese) + "x".repeat(ascii)), List.of());
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
