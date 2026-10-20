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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
            plans, evidence, tools, workspace, json, ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered());

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

    @ParameterizedTest
    @ValueSource(strings = {"さん", "様", "氏"})
    void parsedMemberProvenanceSurvivesStorageReplayAndFreshProviderContext(String suffix) {
        String name = "Former Member";
        var initial = codec.initialize(contextRun());
        var policy = policy(initial);
        User member = new User();
        member.setId(883742);
        member.setDisplayName(name);
        when(workspace.getMembers(7)).thenReturn(List.of(member));
        when(persistence.snapshot(policy)).thenReturn(initial);
        var context = new MaskingContext();
        context.registerMemberCandidate(name);
        String token = context.tokenFor(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, name);
        context.exposeDispatchedTokens(java.util.Set.of(token));
        var arguments = json.createObjectNode().put("expectedPlanRevision", "1").put("expectedCheckpointRevision", "1");
        arguments.putArray("items").addObject().putNull("key").put("label", "Confirm with " + token + suffix)
                .put("status", "pending").putNull("completion");
        Map<String, List<String>> parsed = new java.util.LinkedHashMap<>();
        ooo.klae.connex.backend.ai.masking.Demasker.demaskTree(arguments, context,
                (path, label) -> parsed.put(path, List.of(label)));
        assertEquals(List.of(name), parsed.get("/items/0/label"));
        assertTrue(context.memberCandidatesIn(arguments.at("/items/0/label").asString()).isEmpty());
        var content = new AiAgentContentScreen(workspace);
        var read = new AiAssistantToolResult(Map.of("name", "Primary Record", "handle", "r1"),
                List.of(new AiAssistantToolResult.Identifier("person", "Primary Record")));
        var reference = evidence.reference("person", 9, "record", 0, read);
        var prepared = plans.prepare(initial, arguments, 1,
                (path, text) -> content.screen(text, context, policy, parsed.getOrDefault(path, List.of())),
                ignored -> List.of(), List.of(reference));
        var run = contextRun();
        codec.apply(run, prepared.snapshot());
        var restored = codec.decode(run);
        assertEquals(List.of(new MemberProvenance(883742, name)), restored.plan().items().getFirst().label().memberProvenance());
        var storedResult = json.readTree(json.writeValueAsString(prepared.result()));
        var replayContext = new MaskingContext();
        var replay = plans.providerResult(storedResult, text -> content.authorize(text, replayContext, policy));
        var toolResult = new AiAssistantToolResult(json.convertValue(replay,
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() { }), plans.resultIdentifiers(storedResult));
        var assembler = new AiAssistantPromptAssembler(json, new AiAssistantToolCatalog());
        String request = json.writeValueAsString(assembler.assemble(List.of(),
                new AiAssistantToolResult(Map.of(), List.of()),
                List.of(new AiAssistantPromptAssembler.ToolTurn(1, 0, "set_todos", toolResult)),
                replayContext, new AiChatResourceRegistry(), AiAssistantToolCatalog.CORE));
        assertFalse(request.contains(name));
        assertFalse(request.contains("883742"));
        assertTrue(request.contains(suffix));
        assertTrue(request.contains("{{M"));
        var next = policy(restored);
        when(persistence.snapshot(next)).thenReturn(restored);
        when(tools.execute(eq("get_record"), any(), any(), anyBoolean(), any())).thenReturn(read);
        assertFalse(outbound(next, new MaskingContext(), new AiChatResourceRegistry()).contains(name));
    }

    @Test
    void evidenceMemberFieldsKeepTheirPointerWithoutPromotingUnrelatedSiblingWords() {
        var snapshot = codec.initialize(contextRun());
        var policy = policy(snapshot);
        when(persistence.snapshot(policy)).thenReturn(snapshot);
        User member = new User();
        member.setId(883742);
        member.setDisplayName("Bob");
        when(workspace.getMembers(7)).thenReturn(List.of(member));
        when(tools.execute(eq("get_record"), any(), any(), anyBoolean(), any())).thenReturn(
                new AiAssistantToolResult(Map.of("note", "Bobさん", "unrelated", "xBob"),
                        List.of(new AiAssistantToolResult.Identifier("member", "Bob", "/note"))));
        String request = outbound(policy, new MaskingContext(), new AiChatResourceRegistry());
        assertFalse(request.contains("Bobさん"));
        assertTrue(request.contains("xBob"));
        assertTrue(request.contains("{{M"));
    }

    @Test
    void pendingRecordLabelAndUncitedCheckpointRefuseRenamedSourceBeforeNextProviderRequest() {
        var initial = codec.initialize(contextRun());
        var original = new AiAssistantToolResult(Map.of("name", "Former Record", "handle", "r1"),
                List.of(new AiAssistantToolResult.Identifier("person", "Former Record")));
        var reference = evidence.reference("person", 9, "record", 0, original);
        var arguments = json.createObjectNode().put("expectedPlanRevision", "1").put("expectedCheckpointRevision", "1");
        arguments.putArray("items").addObject().putNull("key").put("label", "Review Former Record")
                .put("status", "pending").putNull("completion");
        var prepared = plans.prepare(initial, arguments, 1,
                (path, text) -> new DurableText(text, List.of()), ignored -> List.of(), List.of(reference));
        var checkpointArgs = json.createObjectNode().put("expectedPlanRevision", "2")
                .put("expectedCheckpointRevision", "2").put("nextPlanItemKey", "p0001").put("phase", "reading");
        checkpointArgs.putObject("memory").put("summary", "Continue review").putArray("citations");
        var checkpoint = plans.prepareCheckpoint(prepared.snapshot(), checkpointArgs, 1,
                (path, text) -> new DurableText(text, List.of()), ignored -> List.of(), List.of(reference));
        assertEquals(List.of(reference), checkpoint.snapshot().checkpoint().evidenceReferences());
        var policy = policy(checkpoint.snapshot());
        when(persistence.snapshot(policy)).thenReturn(checkpoint.snapshot());
        when(workspace.getMembers(7)).thenReturn(List.of());
        when(tools.execute(eq("get_record"), any(), any(), anyBoolean(), any())).thenReturn(
                new AiAssistantToolResult(Map.of("name", "Renamed Record", "handle", "r1"),
                        List.of(new AiAssistantToolResult.Identifier("person", "Renamed Record"))));
        assertEquals("source_changed", assertThrows(AiAssistantLoopException.class,
                () -> outbound(policy, new MaskingContext(), new AiChatResourceRegistry())).terminalReason());
    }

    @Test
    void carriedTextWithoutSourceBindingRefusesBeforeRetrieval() {
        var snapshot = snapshot();
        var checkpoint = snapshot.checkpoint();
        var unbound = new AiAgentContinuationCodec.Snapshot(snapshot.plan(),
                new AiAgentCheckpoint(checkpoint.schemaVersion(), checkpoint.definitionVersion(), checkpoint.workspaceId(),
                        checkpoint.runId(), checkpoint.scopeFingerprint(), checkpoint.privacyGeneration(), checkpoint.revision(),
                        checkpoint.planRevision(), checkpoint.completedPlanItemKeys(), checkpoint.nextPlanItemKey(),
                        checkpoint.lastCommittedSegmentNo(), checkpoint.completedFacts(), checkpoint.actionSlotReferences(),
                        List.of(), checkpoint.coverage(), checkpoint.continuation()), snapshot.memory());
        var policy = policy(unbound);
        when(persistence.snapshot(policy)).thenReturn(unbound);
        assertEquals("source_changed", assertThrows(AiAssistantLoopException.class,
                () -> outbound(policy, new MaskingContext(), new AiChatResourceRegistry())).terminalReason());
        verify(tools, never()).execute(any(), any(), any(), anyBoolean(), any());
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
                .thenReturn(new AiAssistantToolResult(Map.of("name", "Primary Record", "handle", "r1"),
                        List.of(new AiAssistantToolResult.Identifier("person", "Primary Record"))));
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

    @Test
    void oneHundredEvidenceReadsReuseOneOperationSnapshot() {
        try (var operation = new ooo.klae.connex.backend.support.RecordVisibilityOperationFixture(7)) {
            var initial = codec.initialize(contextRun());
            var checkpoint = initial.checkpoint();
            var read = new AiAssistantToolResult(Map.of("handle", "r1"), List.of());
            var reference = evidence.reference("person", 9, "record", 0, read);
            var references = java.util.stream.IntStream.range(0, 100).mapToObj(i -> reference).toList();
            var snapshot = new AiAgentContinuationCodec.Snapshot(initial.plan(),
                new AiAgentCheckpoint(checkpoint.schemaVersion(), checkpoint.definitionVersion(), checkpoint.workspaceId(),
                    checkpoint.runId(), checkpoint.scopeFingerprint(), checkpoint.privacyGeneration(), checkpoint.revision(),
                    checkpoint.planRevision(), checkpoint.completedPlanItemKeys(), checkpoint.nextPlanItemKey(),
                    checkpoint.lastCommittedSegmentNo(), checkpoint.completedFacts(), checkpoint.actionSlotReferences(),
                    references, checkpoint.coverage(), checkpoint.continuation()), initial.memory());
            var policy = policy(snapshot);
            when(persistence.snapshot(policy)).thenReturn(snapshot);
            when(workspace.getMembers(7)).thenReturn(List.of());
            when(tools.execute(eq("get_record"), any(), any(), anyBoolean(), any())).thenAnswer(call ->
                new org.springframework.transaction.support.TransactionTemplate(operation.transactions).execute(status -> {
                    assertEquals(List.of(7, 17), operation.scopes.getForWorkspace(7).workspaceIds());
                    return read;
                }));
            AiAgentContextBuilder scoped = new AiAgentContextBuilder(plans, evidence, tools, workspace, json, operation.scopes);
            policy.execute(() -> scoped.build(policy, new MaskingContext(), new AiChatResourceRegistry()));
            assertEquals(100, operation.transactions.beginCount());
            org.mockito.Mockito.verify(tools, org.mockito.Mockito.times(100)).execute(
                eq("get_record"), any(), any(), anyBoolean(), any());
            verify(operation.control).findOrganizationWorkspacesForShare(7);
        }
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

    private static ooo.klae.connex.backend.beans.AiAgentRun contextRun() {
        var run = AiAgentContinuationCodecTest.run();
        run.setId(971852);
        run.setWorkspaceId(7);
        run.setScopeJson("{\"kind\":\"record\",\"recordKind\":\"person\",\"recordId\":9}");
        return run;
    }

    private static AiAgentContinuationCodec.Snapshot snapshot() {
        var label = new DurableText("Review with Former Member", List.of(new MemberProvenance(883742,
                "Former Member")));
        var summary = new DurableText("Former Member reviewed Primary Record", label.memberProvenance());
        var plan = new AiAgentPlan(1, "2", 2, List.of(new AiAgentPlan.Item("p0001", label, "done")),
                List.of("p0001"));
        var record = new AiAssistantToolResult(Map.of("name", "Primary Record", "handle", "r1"),
                List.of(new AiAssistantToolResult.Identifier("person", "Primary Record")));
        var reference = new AiAgentEvidenceService(new ObjectMapper()).reference("person", 9, "record", 0, record);
        var checkpoint = new AiAgentCheckpoint(1, 1, 7, 971852, "a".repeat(64), "1", "2", "2",
                List.of("p0001"), null, 0, List.of(new AiAgentCheckpoint.CompletedFact("p0001", summary,
                        List.of(reference), 1)), List.of(), List.of(reference),
                new AiAgentCheckpoint.Coverage(1, 0, 0, 0, List.of()),
                new AiAgentCheckpoint.Continuation("record_review_v1", 1, "reading"));
        return new AiAgentContinuationCodec.Snapshot(plan, checkpoint,
                new AiAgentMemory(1, summary, List.of(reference)));
    }

    private AiAgentExecutionPolicy policy(AiAgentContinuationCodec.Snapshot snapshot) {
        var turn = new AiChatQueuedTurn(7, 11, 13, 17, 19, 1, 1, false,
                List.of(), List.of(), AiPrivacyMode.MASKED, false);
        return new AiAgentExecutionPolicy(971852, 23, 1, turn, new AiChatMessage(),
                new AiAgentScopePolicy("person", 9), 4, Instant.parse("2030-01-01T00:00:00Z"),
                null, null, persistence, null, 2, 1, 1, snapshot, true, 65536);
    }
}
