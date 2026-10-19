package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptAssembler;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptBudget;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolExecutor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult;
import ooo.klae.connex.backend.ai.assistant.AiChatQueuedTurn;
import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.DurableText;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.MemberProvenance;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.ObjectMapper;

class AiAgentContextBuilderTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AiAgentContinuationCodec codec = new AiAgentContinuationCodec();
    private final AiAgentPlanService plans = new AiAgentPlanService(codec);
    private final AiAgentEvidenceService evidence = new AiAgentEvidenceService(json);
    private final AiAssistantToolExecutor tools = mock(AiAssistantToolExecutor.class);
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final AiAgentRunPersistenceService persistence = mock(AiAgentRunPersistenceService.class);
    private final AiAgentContextBuilder builder = new AiAgentContextBuilder(
            plans, evidence, tools, workspace, json);

    @AfterEach
    void clearObservations() {
        evidence.clear();
    }

    @Test
    void reauthorizesEvidenceAndRemasksStoredMemory() {
        var snapshot = snapshot();
        var policy = policy(snapshot);
        User member = new User();
        member.setId(883742);
        member.setDisplayName("Current Member");
        when(workspace.getMembers(7)).thenReturn(List.of(member));
        when(persistence.snapshot(policy)).thenReturn(snapshot);
        when(tools.execute(eq("get_record"), any(), any(), anyBoolean(), any()))
                .thenAnswer(call -> new AiAssistantToolResult(Map.of("name", "Primary Record", "handle", "r1"),
                        List.of(new AiAssistantToolResult.Identifier("person", "Primary Record"))));
        var firstContext = new MaskingContext();
        var secondContext = new MaskingContext();
        var firstResources = new AiChatResourceRegistry();
        var secondResources = new AiChatResourceRegistry();
        String first = outbound(policy, firstContext, firstResources);
        String second = outbound(policy, secondContext, secondResources);
        assertNotSame(firstContext, secondContext);
        assertNotSame(firstResources, secondResources);
        assertEquals(firstResources.resolve("r1"), secondResources.resolve("r1"));
        assertTrue(first.contains("p0001"));
        assertTrue(first.contains("{{M"));
        for (String request : List.of(first, second)) {
            for (String forbidden : List.of("883742", "971852", "memberProvenance", "originalLabel",
                    "workspaceId", "runId", "scopeFingerprint", "privacyGeneration", "sha256",
                    "Former Member", "Primary Record")) {
                assertFalse(request.contains(forbidden), forbidden);
            }
        }
    }

    @Test
    void outboundPlanToolResultNeverSerializesMemberOrEvidenceBindings() {
        var snapshot = snapshot();
        MaskingContext context = new MaskingContext();
        context.registerMemberCandidate("Former Member");
        var model = plans.modelPlan(snapshot.plan(), java.util.function.Function.identity());
        var result = new AiAssistantToolResult(Map.of("planRevision", "2", "checkpointRevision", "2",
                "plan", model), List.of());
        var assembler = new AiAssistantPromptAssembler(json, new AiAssistantToolCatalog());
        var prompt = assembler.assemble(List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                List.of(new AiAssistantPromptAssembler.ToolTurn(1, 0, "set_todos", result)), context,
                new AiChatResourceRegistry(), AiAssistantToolCatalog.CORE);
        String outbound = json.writeValueAsString(prompt);
        assertTrue(outbound.contains("{{M"));
        assertTrue(outbound.contains("p0001"));
        for (String forbidden : List.of("883742", "memberProvenance", "originalLabel",
                "evidenceReferences", "Former Member")) {
            assertFalse(outbound.contains(forbidden), forbidden);
        }
    }

    @Test
    void rebuildsWithoutSessionHistoryOrPreviousToolResults() {
        var snapshot = snapshot();
        var policy = policy(snapshot);
        User member = new User();
        member.setId(883742);
        member.setDisplayName("Current Member");
        when(workspace.getMembers(7)).thenReturn(List.of(member));
        when(persistence.snapshot(policy)).thenReturn(snapshot);
        when(tools.execute(eq("get_record"), any(), any(), anyBoolean(), any()))
                .thenReturn(new AiAssistantToolResult(Map.of("handle", "r1"), List.of()));
        policy.execute(() -> builder.build(policy, new MaskingContext(), new AiChatResourceRegistry()));
        verify(persistence).snapshot(policy);
        org.mockito.Mockito.verifyNoMoreInteractions(persistence);
    }

    @Test
    void changedOrInaccessibleEvidenceRefusesBeforePromptAssembly() {
        var original = snapshot();
        var changed = new AiAgentCheckpoint(original.checkpoint().schemaVersion(), 1, 7, 971852,
                "a".repeat(64), "1", "2", "2", List.of("p0001"), null, 0,
                original.checkpoint().completedFacts(), List.of(), List.of(
                        new AiAgentMemory.EvidenceReference("person", 9,
                                new AiAgentMemory.EvidenceVersion("record", 0, "b".repeat(64)))),
                original.checkpoint().coverage(), original.checkpoint().continuation());
        var snapshot = new AiAgentContinuationCodec.Snapshot(original.plan(), changed, original.memory());
        var policy = policy(snapshot);
        User member = new User();
        member.setId(883742);
        when(workspace.getMembers(7)).thenReturn(List.of(member));
        when(persistence.snapshot(policy)).thenReturn(snapshot);
        when(tools.execute(eq("get_record"), any(), any(), anyBoolean(), any()))
                .thenReturn(new AiAssistantToolResult(Map.of("name", "changed"), List.of()));
        assertEquals("source_changed", assertThrows(AiAssistantLoopException.class,
                () -> policy.execute(() -> builder.build(policy, new MaskingContext(),
                        new AiChatResourceRegistry()))).terminalReason());
        when(tools.execute(eq("get_record"), any(), any(), anyBoolean(), any()))
                .thenThrow(AiAssistantLoopException.accessRevoked("unavailable"));
        assertEquals("access_lost", assertThrows(AiAssistantLoopException.class,
                () -> policy.execute(() -> builder.build(policy, new MaskingContext(),
                        new AiChatResourceRegistry()))).terminalReason());
    }

    @Test
    void lostMemberAuthorityRefusesBeforeEvidenceOrProviderAssembly() {
        var snapshot = snapshot();
        var policy = policy(snapshot);
        when(persistence.snapshot(policy)).thenReturn(snapshot);
        when(workspace.getMembers(7)).thenReturn(List.of());
        assertEquals("access_lost", assertThrows(AiAssistantLoopException.class,
                () -> policy.execute(() -> builder.build(policy, new MaskingContext(),
                        new AiChatResourceRegistry()))).terminalReason());
        verify(tools, never()).execute(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void reusesTheRequiredPrimaryReadWithoutFetchingChangedDataAgain() {
        var original = snapshot();
        var record = new AiAssistantToolResult(Map.of("name", "Original", "handle", "r1"), List.of());
        var reference = evidence.reference("person", 9, "record", 0, record);
        var checkpoint = original.checkpoint();
        var required = new AiAgentCheckpoint(checkpoint.schemaVersion(), checkpoint.definitionVersion(),
                checkpoint.workspaceId(), checkpoint.runId(), checkpoint.scopeFingerprint(),
                checkpoint.privacyGeneration(), checkpoint.revision(), checkpoint.planRevision(),
                checkpoint.completedPlanItemKeys(), checkpoint.nextPlanItemKey(),
                checkpoint.lastCommittedSegmentNo(), checkpoint.completedFacts(),
                checkpoint.actionSlotReferences(), List.of(reference), checkpoint.coverage(),
                checkpoint.continuation());
        var snapshot = new AiAgentContinuationCodec.Snapshot(original.plan(), required, original.memory());
        var policy = policy(snapshot);
        User member = new User();
        member.setId(883742);
        when(workspace.getMembers(7)).thenReturn(List.of(member));
        when(persistence.snapshot(policy)).thenReturn(snapshot);
        when(tools.execute(eq("get_record"), any(), any(), anyBoolean(), any()))
                .thenReturn(record, new AiAssistantToolResult(Map.of("name", "Changed", "handle", "r1"),
                        List.of()));
        var context = policy.execute(() -> builder.build(policy, new MaskingContext(),
                new AiChatResourceRegistry()));
        String projection = json.writeValueAsString(context.data());
        assertTrue(projection.contains("Original"));
        assertFalse(projection.contains("Changed"));
        verify(tools, org.mockito.Mockito.times(1)).execute(
                eq("get_record"), any(), any(), anyBoolean(), any());
    }

    @Test
    void completeRequiredContextOverflowRefusesWithoutDroppingFacts() {
        var assembler = new AiAssistantPromptAssembler(json, new AiAssistantToolCatalog());
        var context = new AiAssistantToolResult(Map.of("missionContext", Map.of("completedFacts",
                List.of(Map.of("summary", "x".repeat(3000))))), List.of());
        var budget = new AiAssistantPromptBudget(100, 10000, 1000, 100, 10000, 10000);
        assertEquals("mission_context_overflow", assertThrows(AiAssistantLoopException.class,
                () -> assembler.assemble(List.of(), context, List.of(), new MaskingContext(),
                        new AiChatResourceRegistry(), List.of(), budget, null, AiAssistantToolCatalog.CORE)).terminalReason());
    }

    private String outbound(AiAgentExecutionPolicy policy, MaskingContext context,
            AiChatResourceRegistry resources) {
        return policy.execute(() -> {
            AiAssistantToolResult projection = builder.build(policy, context, resources);
            var assembler = new AiAssistantPromptAssembler(json, new AiAssistantToolCatalog());
            var prompt = assembler.assemble(List.of(), projection, List.of(), context, resources,
                    List.of(), new AiAssistantPromptBudget(1000, 30000, 1000, 30000, 30000, 30000), null, AiAssistantToolCatalog.CORE);
            return json.writeValueAsString(prompt);
        });
    }

    private static AiAgentContinuationCodec.Snapshot snapshot() {
        var label = new DurableText("Review with Former Member", List.of(new MemberProvenance(883742,
                "Former Member")));
        var summary = new DurableText("Former Member reviewed Primary Record", label.memberProvenance());
        var plan = new AiAgentPlan(1, "2", 2, List.of(new AiAgentPlan.Item("p0001", label, "done")),
                List.of("p0001"));
        var checkpoint = new AiAgentCheckpoint(1, 1, 7, 971852, "a".repeat(64), "1", "2", "2",
                List.of("p0001"), null, 0, List.of(new AiAgentCheckpoint.CompletedFact("p0001", summary,
                        List.of(), 1)), List.of(), List.of(),
                new AiAgentCheckpoint.Coverage(1, 0, 0, 0, List.of()),
                new AiAgentCheckpoint.Continuation("record_review_v1", 1, "reading"));
        return new AiAgentContinuationCodec.Snapshot(plan, checkpoint,
                new AiAgentMemory(1, summary, List.of()));
    }

    private AiAgentExecutionPolicy policy(AiAgentContinuationCodec.Snapshot snapshot) {
        var turn = new AiChatQueuedTurn(7, 11, 13, 17, 19, 1, 1, false,
                List.of(), List.of(), AiPrivacyMode.MASKED, false);
        return new AiAgentExecutionPolicy(971852, 23, 1, turn, new AiChatMessage(),
                new AiAgentScopePolicy("person", 9), 4, Instant.parse("2030-01-01T00:00:00Z"),
                null, null, persistence, null, 2, 1, 1, snapshot, true, 65536);
    }
}
