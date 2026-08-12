package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import ooo.klae.connex.backend.ai.AiGenerationTaskResult;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.DirectAdmissionRejectedException;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.Rejection;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRawOutputGuard;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.AiStructuredRepair;
import ooo.klae.connex.backend.ai.AiStructuredRepairAttempt;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult.Identifier;
import ooo.klae.connex.backend.ai.masking.Demasker;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiResponseSchema;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.notifications.AiChatRealtimePublisher;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AiChatAgentLoopServiceTest {
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 1, 23L, true, List.of());

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private AiInvocationService invocationService;
    private AiInvocationAdmissionService invocationAdmissionService;
    private AiInvocationAdmissionService.DirectAdmission directAdmission;
    private AiProperties aiProperties;
    private AiAssistantToolExecutor toolExecutor;
    private AiAssistantWriteToolService writeToolService;
    private AiChatTurnPersistenceService persistenceService;
    private AiRestrictionEpoch restrictionEpoch;
    private WorkspaceService workspaceService;
    private Clock clock;
    private AiChatAgentLoopService service;

    @BeforeEach
    void setUp() {
        invocationService = mock(AiInvocationService.class);
        invocationAdmissionService = mock(AiInvocationAdmissionService.class);
        directAdmission = mock(AiInvocationAdmissionService.DirectAdmission.class);
        aiProperties = new AiProperties();
        toolExecutor = mock(AiAssistantToolExecutor.class);
        writeToolService = mock(AiAssistantWriteToolService.class);
        persistenceService = mock(AiChatTurnPersistenceService.class);
        restrictionEpoch = mock(AiRestrictionEpoch.class);
        workspaceService = mock(WorkspaceService.class);
        clock = mock(Clock.class);
        var catalog = new AiAssistantToolCatalog();
        var promptAssembler = new AiAssistantPromptAssembler(objectMapper, catalog);
        var identifierResolver = mock(AiAssistantIdentifierResolver.class);
        var publishers = new StaticListableBeanFactory()
                .getBeanProvider(AiChatRealtimePublisher.class);
        service = new AiChatAgentLoopService(
                invocationService,
                invocationAdmissionService,
                aiProperties,
                new AiAssistantStepGuard(catalog),
                catalog,
                new AiAssistantStepSchema(objectMapper, catalog),
                toolExecutor,
                writeToolService,
                identifierResolver,
                promptAssembler,
                persistenceService,
                restrictionEpoch,
                workspaceService,
                objectMapper,
                clock,
                publishers);
        AiChatMessage userMessage = new AiChatMessage();
        userMessage.setId(TURN.userMessageId());
        userMessage.setAuthorKind("user");
        userMessage.setContent("Summarize my pipeline");
        when(persistenceService.markRunning(TURN)).thenReturn(true);
        doReturn(directAdmission).when(invocationAdmissionService).acquireDirect();
        when(persistenceService.loadHistory(TURN, 50)).thenReturn(List.of(userMessage));
        when(toolExecutor.pageContext(any(), any())).thenReturn(
                new AiAssistantToolResult(Map.of(), List.of()));
        when(toolExecutor.execute(any(), any(), any(), any(Boolean.class))).thenReturn(
                new AiAssistantToolResult(Map.of("records", List.of()), List.of()));
        when(persistenceService.proposeTool(eq(TURN), anyInt(), any(), any())).thenReturn(29);
        when(persistenceService.finishTool(eq(TURN), anyInt(), any(), any())).thenReturn(true);
        when(restrictionEpoch.current(TURN.workspaceId())).thenReturn(TURN.restrictionEpoch());
        when(clock.instant()).thenReturn(Instant.parse("2026-08-11T00:00:00Z"));
    }

    @Test
    void repeatedIdenticalToolCallsUseTheCacheThenStopForNoProgress() throws Exception {
        AiAssistantStep toolStep = new AiAssistantStep(
                new AiAssistantStep.Tool(
                        "search_records",
                        objectMapper.readTree("{\"query\":\"pipeline\",\"kinds\":[\"deal\"]}")),
                null);
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class),
                any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenReturn(parsed(toolStep));

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.FAILED, result.outcome());
        assertEquals("no_progress", result.reason());
        verify(invocationService, times(3)).completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class),
                any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission));
        verify(toolExecutor).execute(
                eq("search_records"), any(JsonNode.class), any(), eq(true));
        verify(persistenceService, never()).resolve(
                eq(TURN), any(), any(), anyInt(), anyInt());
    }

    @Test
    void repeatedIdenticalAutoWritesExecuteOnlyOnceThenStopForNoProgress() throws Exception {
        JsonNode args = objectMapper.readTree(
                "{\"handle\":\"r1\",\"content\":\"Follow up\"}");
        AiAssistantStep toolStep = new AiAssistantStep(
                new AiAssistantStep.Tool("create_note", args), null);
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class),
                any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenReturn(parsed(toolStep));
        AiAssistantPreparedWrite write = new AiAssistantPreparedWrite(
                "create_note", AiAssistantToolCatalog.ToolTier.AUTO,
                "person", 41, "{\"resolved\":true}");
        AiAssistantToolProposal proposal =
                new AiAssistantToolProposal(29, "proposed", null, true);
        AiAssistantToolResult toolResult = new AiAssistantToolResult(
                Map.of("toolCallId", 29, "status", "executed"), List.of());
        when(writeToolService.prepare(
                eq("create_note"), eq(args), any(), eq(TURN.restrictionEpoch())))
                .thenReturn(write);
        when(persistenceService.proposeWriteTool(TURN, 1, write)).thenReturn(proposal);
        when(writeToolService.executeAuto(TURN, 29)).thenReturn(
                new AiAssistantWriteToolService.WriteExecution(null, toolResult));

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.FAILED, result.outcome());
        assertEquals("no_progress", result.reason());
        verify(invocationService, times(3)).completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class),
                any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission));
        verify(persistenceService).proposeWriteTool(TURN, 1, write);
        verify(writeToolService).executeAuto(TURN, 29);
    }

    @Test
    void confirmTierToolPersistsApprovalCardWithoutAutoExecution() throws Exception {
        JsonNode args = objectMapper.readTree(
                "{\"handle\":\"r1\",\"owner\":\"Grace Hopper\"}");
        AiAssistantStep toolStep = new AiAssistantStep(
                new AiAssistantStep.Tool("assign_owner", args), null);
        AiAssistantStep finalStep = new AiAssistantStep(
                null, new AiAssistantStep.FinalAnswer("Approval is required.", List.of()));
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class),
                any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenReturn(parsed(toolStep), parsed(finalStep));
        AiAssistantPreparedWrite write = new AiAssistantPreparedWrite(
                "assign_owner", AiAssistantToolCatalog.ToolTier.CONFIRM,
                "deal", 41, "{\"resolved\":true}");
        AiAssistantToolProposal proposal =
                new AiAssistantToolProposal(29, "proposed", null, true);
        when(writeToolService.prepare(
                eq("assign_owner"), eq(args), any(), eq(TURN.restrictionEpoch())))
                .thenReturn(write);
        when(persistenceService.proposeWriteTool(TURN, 1, write)).thenReturn(proposal);
        when(writeToolService.proposalResult(write, proposal)).thenReturn(
                new AiAssistantToolResult(
                        Map.of("toolCallId", 29, "status", "approval_required"), List.of()));
        when(persistenceService.resolve(
                eq(TURN), any(), any(), anyInt(), anyInt())).thenReturn(true);

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        verify(persistenceService).proposeWriteTool(TURN, 1, write);
        verify(writeToolService, never()).executeAuto(TURN, 29);
    }

    @Test
    void toolResultToolResultFinalCompletesWithinOneTurn() throws Exception {
        AiAssistantStep firstTool = toolStep(
                "search_records", "{\"query\":\"pipeline\",\"kinds\":[\"deal\"]}");
        AiAssistantStep secondTool = toolStep(
                "aggregate_metric", "{\"metric\":\"deal_metrics\"}");
        AiAssistantStep finalStep = new AiAssistantStep(
                null, new AiAssistantStep.FinalAnswer("Pipeline is healthy.", List.of()));
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class),
                any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenReturn(parsed(firstTool))
                .thenReturn(parsed(secondTool))
                .thenReturn(parsed(finalStep));
        when(toolExecutor.execute(any(), any(), any(), any(Boolean.class)))
                .thenReturn(
                        new AiAssistantToolResult(Map.of("records", List.of("r1")), List.of()),
                        new AiAssistantToolResult(Map.of("count", 1), List.of()));
        when(persistenceService.resolve(
                eq(TURN), any(), any(), anyInt(), anyInt())).thenReturn(true);

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        verify(invocationService, times(3)).completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class),
                any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission));
        verify(toolExecutor, times(2)).execute(any(), any(), any(), eq(true));
        verify(persistenceService).resolve(
                eq(TURN), eq("Pipeline is healthy."), any(), eq(9), eq(15));
    }

    @Test
    void schemaViolationGetsExactlyOneRepairThenFailsDistinctly() {
        AiStructuredRepair repair = AiStructuredRepair.from(
                "exclusive_step", "{\"tool\":null,\"final\":null}");
        AiStructuredRepairAttempt<AiAssistantStep> malformed = new AiStructuredRepairAttempt<>(
                new AiStructuredOutcome.Malformed<>("malformed_output", 2, 3, "stop"),
                Optional.of(repair));
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class),
                any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenReturn(malformed);

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.FAILED, result.outcome());
        assertEquals("schema_repair_failed", result.reason());
        verify(invocationService, times(2)).completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class),
                any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission));
    }

    @Test
    void schemaRepairPreservesIssuedPlaceholderThroughDemaskingAndTranscriptPersistence() {
        when(toolExecutor.pageContext(any(), any())).thenReturn(new AiAssistantToolResult(
                Map.of("records", List.of(Map.of(
                        "handle", "r1", "kind", "person", "name", "Mina Patel"))),
                List.of(new Identifier("person", "Mina Patel"))));
        AiStructuredRepair repair = AiStructuredRepair.from(
                "exclusive_step",
                "{\"tool\":null,\"final\":{\"text\":\"Follow up with {{P1}}.\"}}");
        AiStructuredRepairAttempt<AiAssistantStep> malformed = new AiStructuredRepairAttempt<>(
                new AiStructuredOutcome.Malformed<>("malformed_output", 2, 3, "stop"),
                Optional.of(repair));
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class),
                any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenReturn(malformed)
                .thenAnswer(invocation -> {
                    AiInvocation repairedInvocation = invocation.getArgument(0);
                    String repairPrompt = repairedInvocation.prompt().getMessages().getLast().getContent();
                    assertTrue(repairPrompt.contains("{{P1}}"));
                    assertFalse(repairPrompt.matches("(?s).*?(?<!\\{\\{)P1(?!}}).*"));
                    Demasker.DemaskResult demasked = Demasker.demask(
                            "Follow up with {{P1}}.", repairedInvocation.context());
                    assertEquals(0, demasked.warnings());
                    return parsed(new AiAssistantStep(
                            null, new AiAssistantStep.FinalAnswer(demasked.text(), List.of())));
                });
        when(persistenceService.resolve(
                eq(TURN), any(), any(), anyInt(), anyInt())).thenReturn(true);

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        verify(persistenceService).resolve(
                eq(TURN), eq("Follow up with Mina Patel."), any(), eq(5), eq(8));
    }

    @Test
    void schemaRepairRejectsBareIssuedPlaceholderBeforeTranscriptPersistence() throws Exception {
        when(toolExecutor.pageContext(any(), any())).thenReturn(new AiAssistantToolResult(
                Map.of("records", List.of(Map.of(
                        "handle", "r1", "kind", "person", "name", "Mina Patel"))),
                List.of(new Identifier("person", "Mina Patel"))));
        AiStructuredRepair repair = AiStructuredRepair.from(
                "exclusive_step",
                "{\"tool\":null,\"final\":{\"text\":\"Follow up with {{P1}}.\"}}");
        AiStructuredRepairAttempt<AiAssistantStep> malformed = new AiStructuredRepairAttempt<>(
                new AiStructuredOutcome.Malformed<>("malformed_output", 2, 3, "stop"),
                Optional.of(repair));
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class),
                any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenReturn(malformed)
                .thenAnswer(invocation -> {
                    AiRawOutputGuard outputGuard = invocation.getArgument(2);
                    assertEquals("bare_placeholder", outputGuard.rejectionReason(objectMapper.readTree(
                            "{\"tool\":null,\"final\":{\"text\":\"Follow up with P1.\","
                                    + "\"citations\":[],\"suggestions\":[],\"title\":null}}")));
                    return new AiStructuredRepairAttempt<>(
                            new AiStructuredOutcome.Malformed<>(
                                    "malformed_output", 3, 4, "stop"),
                            Optional.empty());
                });

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.FAILED, result.outcome());
        assertEquals("schema_repair_failed", result.reason());
        verify(persistenceService, never()).resolve(
                eq(TURN), any(), any(), anyInt(), anyInt());
    }

    @Test
    void hardBackstopHasItsOwnTerminalReason() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class),
                any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenAnswer(invocation -> parsed(toolStep(
                        "search_records",
                        "{\"query\":\"pipeline-" + calls.incrementAndGet()
                                + "\",\"kinds\":[\"deal\"]}")));
        AtomicInteger results = new AtomicInteger();
        when(toolExecutor.execute(any(), any(), any(), any(Boolean.class)))
                .thenAnswer(invocation -> new AiAssistantToolResult(
                        Map.of("result", results.incrementAndGet()), List.of()));

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.FAILED, result.outcome());
        assertEquals("agent_backstop_exceeded", result.reason());
        verify(invocationService, times(AiChatAgentLoopService.MAX_STEPS))
                .completeStructuredRepairable(
                        any(AiInvocation.class), eq(AiAssistantStep.class),
                        any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission));
    }

    @Test
    void wallClockDeadlineHasItsOwnTerminalReason() {
        when(clock.instant()).thenReturn(
                Instant.parse("2026-08-11T00:00:00Z"),
                Instant.parse("2026-08-11T00:01:10Z"));

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.TIMED_OUT, result.outcome());
        assertEquals("turn_deadline_exceeded", result.reason());
        verify(invocationService, never()).completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class),
                any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission));
    }

    @Test
    void resolvedFinalPersistsTheDemaskedAnswerWithoutFillerSuggestions() throws Exception {
        AiAssistantStep finalStep = new AiAssistantStep(
                null, new AiAssistantStep.FinalAnswer("Pipeline is healthy.", List.of()));
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenReturn(parsed(finalStep));
        when(persistenceService.resolve(
                eq(TURN), any(), any(), anyInt(), anyInt())).thenReturn(true);

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).resolve(
                eq(TURN), eq("Pipeline is healthy."), metadata.capture(), eq(3), eq(5));
        assertEquals(0, objectMapper.readTree(metadata.getValue()).get("citations").size());
        assertEquals(0, objectMapper.readTree(metadata.getValue()).get("suggestions").size());
        assertEquals(0, objectMapper.readTree(metadata.getValue()).get("resources").size());
    }

    @Test
    void configuredOutputTokenLimitReachesEveryProviderInvocation() {
        aiProperties.setAssistantMaxOutputTokens(7777);
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class),
                any(AiResponseSchema.class), eq(directAdmission)))
                .thenReturn(parsed(new AiAssistantStep(
                        null, new AiAssistantStep.FinalAnswer("Complete answer.", List.of()))));
        when(persistenceService.resolve(
                eq(TURN), any(), any(), anyInt(), anyInt())).thenReturn(true);

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        ArgumentCaptor<AiInvocation> invocation = ArgumentCaptor.forClass(AiInvocation.class);
        verify(invocationService).completeStructuredRepairable(
                invocation.capture(), eq(AiAssistantStep.class), any(AiRawOutputGuard.class),
                any(AiResponseSchema.class), eq(directAdmission));
        assertEquals(7777, invocation.getValue().maxTokens());
    }

    @Test
    void suggestionsAndFirstTitlePersistDemaskedWhileTitleFailureCannotFailTurn() throws Exception {
        when(toolExecutor.pageContext(any(), any())).thenReturn(new AiAssistantToolResult(
                Map.of("records", List.of(Map.of(
                        "handle", "r1", "kind", "person", "name", "Mina Patel"))),
                List.of(new Identifier("person", "Mina Patel"))));
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class),
                any(AiResponseSchema.class), eq(directAdmission)))
                .thenAnswer(invocation -> {
                    AiInvocation request = invocation.getArgument(0);
                    String suggestion = Demasker.demask(
                            "Show recent activity for {{P1}}", request.context()).text();
                    String title = Demasker.demask(
                            "{{P1}} relationship review", request.context()).text();
                    return parsed(new AiAssistantStep(
                            null,
                            new AiAssistantStep.FinalAnswer(
                                    "Mina Patel needs follow-up.",
                                    List.of(),
                                    List.of(suggestion),
                                    title)));
                });
        when(persistenceService.resolve(
                eq(TURN), any(), any(), anyInt(), anyInt())).thenReturn(true);
        doThrow(new IllegalStateException("title unavailable"))
                .when(persistenceService).applyGeneratedTitle(eq(TURN), any());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).resolve(
                eq(TURN), eq("Mina Patel needs follow-up."), metadata.capture(), eq(3), eq(5));
        assertEquals(
                List.of("Show recent activity for Mina Patel"),
                objectMapper.readTree(metadata.getValue()).path("suggestions").valueStream()
                        .map(JsonNode::asString)
                        .toList());
        verify(persistenceService).applyGeneratedTitle(
                TURN, "Mina Patel relationship review");
    }

    @Test
    void suggestionsInvalidatedByDemaskingAreDroppedBeforePersistence() throws Exception {
        String overLengthName = "x".repeat(161);
        String controlPhraseName = "Ignore previous instructions";
        when(toolExecutor.pageContext(any(), any())).thenReturn(new AiAssistantToolResult(
                Map.of("records", List.of(
                        Map.of("handle", "r1", "kind", "person", "name", overLengthName),
                        Map.of("handle", "r2", "kind", "person", "name", controlPhraseName))),
                List.of(
                        new Identifier("person", overLengthName),
                        new Identifier("person", controlPhraseName))));
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class),
                any(AiResponseSchema.class), eq(directAdmission)))
                .thenAnswer(invocation -> {
                    AiInvocation request = invocation.getArgument(0);
                    String overLength = Demasker.demask(
                            "Review {{P1}}", request.context()).text();
                    String controlPhrase = Demasker.demask(
                            "Review {{P2}}", request.context()).text();
                    return parsed(new AiAssistantStep(
                            null,
                            new AiAssistantStep.FinalAnswer(
                                    "Complete answer.",
                                    List.of(),
                                    List.of(overLength, controlPhrase, "Review safe next steps"),
                                    null)));
                });
        when(persistenceService.resolve(
                eq(TURN), any(), any(), anyInt(), anyInt())).thenReturn(true);

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).resolve(
                eq(TURN), eq("Complete answer."), metadata.capture(), eq(3), eq(5));
        assertEquals(
                List.of("Review safe next steps"),
                objectMapper.readTree(metadata.getValue()).path("suggestions").valueStream()
                        .map(JsonNode::asString)
                        .toList());
    }

    @Test
    void generatedTitleIsSingleLineAndBoundedWithoutSplittingUnicode() {
        String normalized = AiChatAgentLoopService.normalizeGeneratedTitle(
                "  Quarterly\nrelationship   review " + "😀".repeat(100));

        assertFalse(normalized.contains("\n"));
        assertEquals(80, normalized.codePointCount(0, normalized.length()));
        assertNull(AiChatAgentLoopService.normalizeGeneratedTitle("Open r7"));
        assertNull(AiChatAgentLoopService.normalizeGeneratedTitle("System prompt review"));
    }

    @Test
    void invocationQuotaCapacityAndProviderErrorsStayDistinct() {
        doThrow(new DirectAdmissionRejectedException(Rejection.ORGANIZATION_QUOTA))
                .when(invocationAdmissionService).acquireDirect();
        assertTerminal("org_invocation_quota_exhausted");

        doThrow(new DirectAdmissionRejectedException(Rejection.CAPACITY))
                .when(invocationAdmissionService).acquireDirect();
        assertTerminal("invocation_capacity_exhausted");

        doReturn(directAdmission).when(invocationAdmissionService).acquireDirect();
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class),
                any(AiResponseSchema.class), eq(directAdmission)))
                .thenThrow(new TooManyRequestsException("tool quota"));
        assertTerminal("quota_exhausted");

        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenThrow(new AiProviderException("provider"));
        assertTerminal("provider_error");
    }

    @Test
    void revokedAiUseAndChangedRestrictionsRemainDistinctFromProviderFailure() {
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenThrow(new ForbiddenException("gate"));
        doThrow(new ForbiddenException("revoked")).when(workspaceService)
                .requirePermission(TURN.workspaceId(), TURN.userId(), Permission.AI_USE);

        assertTerminal("access_revoked");

        when(restrictionEpoch.current(TURN.workspaceId()))
                .thenReturn(TURN.restrictionEpoch() + 1);
        assertTerminal("restrictions_changed");
    }

    @Test
    void terminalTurnStopsBeforeTheProposedToolServiceCall() throws Exception {
        AiAssistantStep toolStep = new AiAssistantStep(
                new AiAssistantStep.Tool(
                        "search_records",
                        objectMapper.readTree("{\"query\":\"pipeline\",\"kinds\":[\"deal\"]}")),
                null);
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenReturn(parsed(toolStep));
        doThrow(new ConflictException("Assistant turn is no longer active"))
                .when(persistenceService).requireRunning(TURN);

        assertTerminal("internal_error");

        verify(toolExecutor, never()).execute(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void toolServiceAndFinalPersistenceFailuresAreInternal() throws Exception {
        AiAssistantStep toolStep = new AiAssistantStep(
                new AiAssistantStep.Tool(
                        "search_records",
                        objectMapper.readTree("{\"query\":\"pipeline\",\"kinds\":[\"deal\"]}")),
                null);
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenReturn(parsed(toolStep));
        when(toolExecutor.execute(any(), any(), any(), any(Boolean.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertTerminal("internal_error");

        AiAssistantStep finalStep = new AiAssistantStep(
                null, new AiAssistantStep.FinalAnswer("Pipeline is healthy.", List.of()));
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenReturn(parsed(finalStep));
        doThrow(new IllegalStateException("database unavailable")).when(persistenceService)
                .resolve(eq(TURN), any(), any(), anyInt(), anyInt());

        assertTerminal("internal_error");
    }

    @Test
    void finalCommitEpochChangeKeepsItsDistinctTerminalReason() {
        AiAssistantStep finalStep = new AiAssistantStep(
                null, new AiAssistantStep.FinalAnswer("Pipeline is healthy.", List.of()));
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenReturn(parsed(finalStep));
        doThrow(new AiAssistantLoopException(
                "restrictions_changed", "restrictions_changed")).when(persistenceService)
                .resolve(eq(TURN), any(), any(), anyInt(), anyInt());

        assertTerminal("restrictions_changed");
    }

    @Test
    void unknownCitationFailsBeforeAnAssistantMessageIsCommitted() {
        AiAssistantStep finalStep = new AiAssistantStep(
                null, new AiAssistantStep.FinalAnswer("Use this record.", List.of("r9")));
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenReturn(parsed(finalStep));

        assertTerminal("malformed_output");

        verify(persistenceService, never()).resolve(
                eq(TURN), any(), any(), anyInt(), anyInt());
    }

    @Test
    void unknownToolHandleFailsBeforeDurableProposalOrDomainExecution() throws Exception {
        var catalog = new AiAssistantToolCatalog();
        AiAssistantToolExecutor realExecutor = new AiAssistantToolExecutor(
                catalog,
                mock(ooo.klae.connex.backend.services.SearchService.class),
                mock(ooo.klae.connex.backend.services.PersonService.class),
                mock(ooo.klae.connex.backend.services.CompanyService.class),
                mock(ooo.klae.connex.backend.services.DealService.class),
                mock(ooo.klae.connex.backend.services.ActivityService.class),
                mock(ooo.klae.connex.backend.services.TaskService.class),
                mock(ooo.klae.connex.backend.services.ScoringService.class),
                workspaceService,
                mock(PersonMapper.class),
                mock(CompanyMapper.class),
                mock(DealMapper.class),
                mock(AiAssistantDateResolver.class));
        service = new AiChatAgentLoopService(
                invocationService,
                invocationAdmissionService,
                aiProperties,
                new AiAssistantStepGuard(catalog),
                catalog,
                new AiAssistantStepSchema(objectMapper, catalog),
                realExecutor,
                writeToolService,
                mock(AiAssistantIdentifierResolver.class),
                new AiAssistantPromptAssembler(objectMapper, catalog),
                persistenceService,
                restrictionEpoch,
                workspaceService,
                objectMapper,
                clock,
                new StaticListableBeanFactory().getBeanProvider(AiChatRealtimePublisher.class));
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class), any(AiResponseSchema.class), eq(directAdmission)))
                .thenReturn(parsed(new AiAssistantStep(
                        new AiAssistantStep.Tool(
                                "get_record", objectMapper.readTree("{\"handle\":\"r9\"}")),
                        null)));

        assertTerminal("malformed_output");

        verify(persistenceService, never()).proposeTool(eq(TURN), anyInt(), any(), any());
    }

    @Test
    void historyCharacterBudgetKeepsNewestUserMessageWholeAndTruncatesTheOldestBoundary() {
        AiChatMessage oldest = message(1, "a".repeat(60_000));
        AiChatMessage recent = message(2, "b".repeat(20_000));
        AiChatMessage initiating = message(TURN.userMessageId(), "c".repeat(16_000));

        List<AiChatMessage> bounded = AiChatAgentLoopService.boundedHistory(
                List.of(oldest, recent, initiating), TURN);

        assertEquals(3, bounded.size());
        assertEquals(64_000, bounded.stream().mapToInt(message -> message.getContent().length()).sum());
        assertEquals("a".repeat(28_000), bounded.get(0).getContent());
        assertEquals(recent.getContent(), bounded.get(1).getContent());
        assertEquals(initiating.getContent(), bounded.get(2).getContent());
    }

    @Test
    void oversizedNewestUserMessageRemainsIntactWhileAllOlderContentIsTrimmed() {
        AiChatMessage oldest = message(1, "a".repeat(10_000));
        AiChatMessage initiating = message(TURN.userMessageId(), "c".repeat(80_000));

        List<AiChatMessage> bounded = AiChatAgentLoopService.boundedHistory(
                List.of(oldest, initiating), TURN);

        assertEquals(1, bounded.size());
        assertEquals(initiating.getContent(), bounded.getFirst().getContent());
    }

    private void assertTerminal(String reason) {
        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);
        assertEquals(AiGenerationTaskResult.Outcome.FAILED, result.outcome());
        assertEquals(reason, result.reason());
    }

    private AiAssistantStep toolStep(String name, String arguments) throws JacksonException {
        return new AiAssistantStep(
                new AiAssistantStep.Tool(name, objectMapper.readTree(arguments)),
                null);
    }

    private static AiStructuredRepairAttempt<AiAssistantStep> parsed(AiAssistantStep step) {
        return new AiStructuredRepairAttempt<>(
                new AiStructuredOutcome.Parsed<>(step, 0, 3, 5, "stop"),
                Optional.empty());
    }

    private static AiChatMessage message(int id, String content) {
        AiChatMessage message = new AiChatMessage();
        message.setId(id);
        message.setAuthorKind("user");
        message.setContent(content);
        return message;
    }
}
