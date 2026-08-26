package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.AiStructuredRepairAttempt;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import ooo.klae.connex.backend.ai.provider.AiToolCallingMode;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import tools.jackson.databind.json.JsonMapper;

class AiChatMemoryServiceTest {

    @Test
    void compactsWholeEarlyMessagesAndReplaysDurableSummaryForContinuity() {
        AiInvocationService invocationService = mock(AiInvocationService.class);
        AiInvocationAdmissionService admissionService = mock(AiInvocationAdmissionService.class);
        AiInvocationAdmissionService.DirectAdmission admission =
                mock(AiInvocationAdmissionService.DirectAdmission.class);
        AiChatTurnPersistenceService persistenceService = mock(AiChatTurnPersistenceService.class);
        AiProperties properties = new AiProperties();
        properties.setAssistantMaxOutputTokens(1_024);
        var objectMapper = JsonMapper.builder().build();
        var assembler = new AiAssistantPromptAssembler(
                objectMapper, new AiAssistantToolCatalog());
        var summaryGuard = new AiAssistantSummaryGuard();
        var summarySchema = new AiAssistantSummarySchema(objectMapper);
        var stepSchema = new AiAssistantStepSchema(
                objectMapper, new AiAssistantToolCatalog());
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        AiChatMemoryService service = new AiChatMemoryService(
                invocationService,
                admissionService,
                properties,
                assembler,
                emptyToolExecutor(),
                summaryGuard,
                summarySchema,
                stepSchema,
                persistenceService,
                mock(AiWorkspaceGovernanceService.class),
                objectMapper,
                Clock.fixed(now, ZoneOffset.UTC));
        AiChatQueuedTurn turn = new AiChatQueuedTurn(
                3, 12, 5, 7, 104, 4, 9L, false, List.of(), List.of());
        AiChatMessage early = message(
                101, 1, "user",
                "EARLY_FACT_BEGIN " + "quarterly planning preference ".repeat(200)
                        + "EARLY_FACT_END");
        early.setStructuredJson(
                "{\"kind\":\"user_message\",\"resources\":[],"
                        + "\"identifiers\":[{\"kind\":\"person\","
                        + "\"value\":\"quarterly planning\"}]}");
        AiChatMessage recent = message(
                103, 3, "assistant",
                "RECENT_ANSWER_BEGIN " + "grounded relationship update ".repeat(1_100)
                        + "RECENT_ANSWER_END");
        AiChatMessage middle = message(
                102, 2, "user",
                "MIDDLE_FACT_BEGIN " + "second batch continuity ".repeat(200)
                        + "MIDDLE_FACT_END");
        AiChatMessage initiating = message(
                104, 4, "user", "What did I prefer at the start?");
        String firstSummaryContent = "The user prefers quarterly planning. "
                + "Retained context ".repeat(200);
        AiChatMessage firstStoredSummary = message(
                105, 5, "system", firstSummaryContent);
        firstStoredSummary.setStructuredJson(
                "{\"kind\":\"history_summary\",\"sourceFromSeq\":1,"
                        + "\"throughSeq\":1,\"resources\":[],"
                        + "\"identifiers\":[{\"kind\":\"person\","
                        + "\"value\":\"quarterly planning\"}]}");
        AiChatMessage storedSummary = message(
                105, 5, "system",
                "The user prefers quarterly planning and retained the second-batch fact.");
        storedSummary.setStructuredJson(
                "{\"kind\":\"history_summary\",\"sourceFromSeq\":1,"
                        + "\"throughSeq\":2,\"resources\":[],"
                        + "\"identifiers\":[{\"kind\":\"person\","
                        + "\"value\":\"quarterly planning\"}]}");
        when(invocationService.currentProviderCapabilities(AiFeature.ASSISTANT_CHAT))
                .thenReturn(new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.JSON_SCHEMA,
                        AiReasoningMode.TAGGED,
                        200_000,
                        50_000));
        when(invocationService.serializedPromptBytes(
                any(MaskedPrompt.class), same(stepSchema.responseSchema()),
                eq(AiReasoningMode.TAGGED)))
                .thenReturn(8_192);
        when(persistenceService.loadHistory(turn, 100))
                .thenReturn(List.of(early, middle, recent, initiating));
        when(persistenceService.loadHistorySummary(turn)).thenReturn(null);
        when(persistenceService.loadCompactionCandidates(turn, 0, 4, 500))
                .thenReturn(List.of(early));
        when(persistenceService.loadCompactionCandidates(turn, 1, 4, 500))
                .thenReturn(List.of(middle));
        when(persistenceService.loadCompactionCandidates(turn, 2, 4, 500))
                .thenReturn(List.of());
        when(admissionService.acquireDirect()).thenReturn(admission);
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class),
                eq(AiAssistantSummary.class),
                same(summaryGuard),
                same(summarySchema.responseSchema()),
                same(admission), any(Runnable.class)))
                .thenReturn(new AiStructuredRepairAttempt<>(
                        new AiStructuredOutcome.Parsed<>(
                                new AiAssistantSummary(firstSummaryContent),
                                0, 19, 7, "end_turn"),
                        Optional.empty()),
                        new AiStructuredRepairAttempt<>(
                                new AiStructuredOutcome.Parsed<>(
                                        new AiAssistantSummary(
                                                "The user prefers quarterly planning and retained the second-batch fact."),
                                        0, 23, 9, "end_turn"),
                                Optional.empty()));
        when(persistenceService.upsertHistorySummary(
                same(turn), isNull(), eq(0), anyString(), anyString(), eq(19), eq(7)))
                .thenReturn(firstStoredSummary);
        when(persistenceService.upsertHistorySummary(
                same(turn), eq(105), eq(1), anyString(), anyString(), eq(23), eq(9)))
                .thenReturn(storedSummary);

        AiChatMemory memory = service.prepare(
                turn, new MaskingContext(), now.plusSeconds(70));

        ArgumentCaptor<AiInvocation> summaryInvocation =
                ArgumentCaptor.forClass(AiInvocation.class);
        verify(invocationService, times(2)).completeStructuredRepairable(
                summaryInvocation.capture(),
                eq(AiAssistantSummary.class),
                same(summaryGuard),
                same(summarySchema.responseSchema()),
                same(admission),
                any(Runnable.class));
        String firstCompactionPrompt = promptText(
                summaryInvocation.getAllValues().getFirst().prompt());
        String secondCompactionPrompt = promptText(
                summaryInvocation.getAllValues().getLast().prompt());
        assertEquals(
                now.plusSeconds(70),
                summaryInvocation.getAllValues().getFirst().callerDeadline());
        assertEquals(
                summaryInvocation.getAllValues().getFirst().callerDeadline(),
                summaryInvocation.getAllValues().getLast().callerDeadline());
        assertTrue(firstCompactionPrompt.contains("EARLY_FACT_BEGIN"));
        assertTrue(firstCompactionPrompt.contains("EARLY_FACT_END"));
        assertFalse(firstCompactionPrompt.contains("MIDDLE_FACT_BEGIN"));
        assertFalse(secondCompactionPrompt.contains("quarterly planning"));
        assertTrue(secondCompactionPrompt.contains("MIDDLE_FACT_BEGIN"));
        assertTrue(secondCompactionPrompt.contains("MIDDLE_FACT_END"));
        assertFalse(secondCompactionPrompt.contains("RECENT_ANSWER_BEGIN"));
        assertEquals(List.of(storedSummary, recent, initiating), memory.history());
        assertEquals(42, memory.inputTokens());
        assertEquals(16, memory.outputTokens());
        assertFalse(memory.nativeTools());
        ArgumentCaptor<String> persistedMetadata = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).upsertHistorySummary(
                same(turn), isNull(), eq(0), anyString(), persistedMetadata.capture(), eq(19), eq(7));
        assertEquals(
                "quarterly planning",
                objectMapper.readTree(persistedMetadata.getValue())
                        .path("identifiers").path(0).path("value").asString());

        MaskedPrompt replay = assembler.assemble(
                memory.history(),
                new AiAssistantToolResult(Map.of(), List.of()),
                List.of(),
                new MaskingContext(),
                new AiChatResourceRegistry(),
                memory.budget(),
                null);
        assertFalse(promptText(replay).contains("quarterly planning"));
        assertTrue(promptText(replay).contains("{{P1}}"));
    }

    @Test
    void nativeProviderCapabilitySelectsTheNativeFixedEnvelope() {
        AiInvocationService invocationService = mock(AiInvocationService.class);
        AiChatTurnPersistenceService persistenceService = mock(AiChatTurnPersistenceService.class);
        AiProperties properties = new AiProperties();
        properties.setAssistantMaxOutputTokens(8_192);
        var objectMapper = JsonMapper.builder().build();
        var catalog = new AiAssistantToolCatalog();
        var assembler = new AiAssistantPromptAssembler(objectMapper, catalog);
        var stepSchema = new AiAssistantStepSchema(objectMapper, catalog);
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        AiChatMemoryService service = new AiChatMemoryService(
                invocationService,
                mock(AiInvocationAdmissionService.class),
                properties,
                assembler,
                emptyToolExecutor(),
                new AiAssistantSummaryGuard(),
                new AiAssistantSummarySchema(objectMapper),
                stepSchema,
                persistenceService,
                mock(AiWorkspaceGovernanceService.class),
                objectMapper,
                Clock.fixed(now, ZoneOffset.UTC));
        AiChatQueuedTurn turn = new AiChatQueuedTurn(
                3, 12, 5, 7, 104, 4, 9L, false, List.of(), List.of());
        AiChatMessage initiating = message(104, 4, "user", "Summarize the pipeline");
        when(invocationService.currentProviderCapabilities(AiFeature.ASSISTANT_CHAT))
                .thenReturn(new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.JSON_SCHEMA,
                        AiReasoningMode.TAGGED,
                        AiAssistantPromptBudget.ASSISTANT_MIN_CONTEXT_TOKENS,
                        8_192,
                        AiToolCallingMode.NATIVE_FUNCTIONS,
                        AiReasoningMode.NATIVE));
        when(invocationService.serializedPromptBytes(
                any(MaskedPrompt.class),
                same(stepSchema.finalResponseSchema()),
                eq(AiReasoningMode.NATIVE),
                any(AiNativeToolRequest.class)))
                .thenReturn(8_192);
        when(persistenceService.loadHistory(turn, 100)).thenReturn(List.of(initiating));
        when(persistenceService.loadHistorySummary(turn)).thenReturn(null);

        AiChatMemory memory = service.prepare(
                turn, new MaskingContext(), now.plusSeconds(70));

        assertTrue(memory.nativeTools());
        ArgumentCaptor<AiNativeToolRequest> nativeTools =
                ArgumentCaptor.forClass(AiNativeToolRequest.class);
        verify(invocationService).serializedPromptBytes(
                any(MaskedPrompt.class),
                same(stepSchema.finalResponseSchema()),
                eq(AiReasoningMode.NATIVE),
                nativeTools.capture());
        assertEquals(13, nativeTools.getValue().definitions().size());
        verify(invocationService, never()).serializedPromptBytes(
                any(MaskedPrompt.class), same(stepSchema.responseSchema()),
                eq(AiReasoningMode.TAGGED));
    }

    /**
     * The floor is checked at the seam where the configured context size first becomes known.
     *
     * <p>Preparation reads capabilities before it loads any history, so a workspace configured with
     * a 32k model fails this turn without touching the transcript, assembling a prompt, or reaching
     * a provider — and an administrator who configures a larger model fixes the next turn with no
     * restart.
     */
    @Test
    void aContextWindowBelowTheAssistantFloorRefusesPreparationBeforeAnyHistoryIsRead() {
        AiInvocationService invocationService = mock(AiInvocationService.class);
        AiChatTurnPersistenceService persistenceService = mock(AiChatTurnPersistenceService.class);
        AiProperties properties = new AiProperties();
        properties.setAssistantMaxOutputTokens(8_192);
        var objectMapper = JsonMapper.builder().build();
        var catalog = new AiAssistantToolCatalog();
        var assembler = new AiAssistantPromptAssembler(objectMapper, catalog);
        var stepSchema = new AiAssistantStepSchema(objectMapper, catalog);
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        AiChatMemoryService service = new AiChatMemoryService(
                invocationService,
                mock(AiInvocationAdmissionService.class),
                properties,
                assembler,
                emptyToolExecutor(),
                new AiAssistantSummaryGuard(),
                new AiAssistantSummarySchema(objectMapper),
                stepSchema,
                persistenceService,
                mock(AiWorkspaceGovernanceService.class),
                objectMapper,
                Clock.fixed(now, ZoneOffset.UTC));
        AiChatQueuedTurn turn = new AiChatQueuedTurn(
                3, 12, 5, 7, 104, 4, 9L, false, List.of(), List.of());
        when(invocationService.currentProviderCapabilities(AiFeature.ASSISTANT_CHAT))
                .thenReturn(new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.JSON_SCHEMA,
                        AiReasoningMode.TAGGED,
                        32_768,
                        8_192));

        AiAssistantLoopException refused = assertThrows(
                AiAssistantLoopException.class,
                () -> service.prepare(turn, new MaskingContext(), now.plusSeconds(70)));

        assertEquals(AiAssistantTerminalReasons.CONTEXT_WINDOW_TOO_SMALL,
                refused.terminalReason());
        verifyNoInteractions(persistenceService);
    }

    @Test
    void boundedHistoryOmitsOversizedMessagesWholeAndNeverCutsInitiatingMessage() {
        AiChatMessage older = message(
                1, 1, "user", "OLDER_MESSAGE_MUST_NOT_JUMP_THE_GAP");
        AiChatMessage oversized = message(
                2, 2, "assistant", "OVERSIZED_MESSAGE_BEGIN_AND_END");
        AiChatMessage initiating = message(
                3, 3, "user", "INITIATING_MESSAGE_BEGIN_AND_END");

        List<AiChatMessage> bounded = AiChatMemoryService.boundedHistory(
                null, List.of(older, oversized, initiating), initiating, 10);

        assertEquals(List.of(initiating), bounded);
        assertEquals("INITIATING_MESSAGE_BEGIN_AND_END", bounded.getFirst().getContent());
    }

    /**
     * The compaction trigger and the verbatim reservation both multiply {@code historyBytes} by a
     * percentage before dividing, and that history allocation is derived from the provider's
     * context window. A million-token model produces a history budget more than an order of
     * magnitude larger than the floor's, so this pins that a message which would have forced
     * compaction on a small window is now carried verbatim, without overflowing that arithmetic.
     */
    @Test
    void aMillionTokenWindowFundsHistoryWithoutTrippingCompaction() {
        AiInvocationService invocationService = mock(AiInvocationService.class);
        AiChatTurnPersistenceService persistenceService = mock(AiChatTurnPersistenceService.class);
        AiProperties properties = new AiProperties();
        properties.setAssistantMaxOutputTokens(16_384);
        var objectMapper = JsonMapper.builder().build();
        var catalog = new AiAssistantToolCatalog();
        var assembler = new AiAssistantPromptAssembler(objectMapper, catalog);
        var stepSchema = new AiAssistantStepSchema(objectMapper, catalog);
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        AiChatMemoryService service = new AiChatMemoryService(
                invocationService,
                mock(AiInvocationAdmissionService.class),
                properties,
                assembler,
                emptyToolExecutor(),
                new AiAssistantSummaryGuard(),
                new AiAssistantSummarySchema(objectMapper),
                stepSchema,
                persistenceService,
                mock(AiWorkspaceGovernanceService.class),
                objectMapper,
                Clock.fixed(now, ZoneOffset.UTC));
        AiChatQueuedTurn turn = new AiChatQueuedTurn(
                3, 12, 5, 7, 104, 4, 9L, false, List.of(), List.of());
        String content = "a".repeat(60_000);
        AiChatMessage initiating = message(104, 4, "user", content);
        when(invocationService.currentProviderCapabilities(AiFeature.ASSISTANT_CHAT))
                .thenReturn(new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.JSON_SCHEMA,
                        AiReasoningMode.TAGGED,
                        1_000_000,
                        128_000));
        when(invocationService.serializedPromptBytes(
                any(MaskedPrompt.class), same(stepSchema.responseSchema()),
                eq(AiReasoningMode.TAGGED)))
                .thenReturn(16_962);
        when(persistenceService.loadHistory(turn, 100)).thenReturn(List.of(initiating));
        when(persistenceService.loadHistorySummary(turn)).thenReturn(null);

        AiChatMemory memory = service.prepare(
                turn, new MaskingContext(), now.plusSeconds(70));

        assertEquals(1, memory.history().size());
        assertEquals(content, memory.history().getFirst().getContent());
        assertEquals(16_384, memory.budget().maxOutputTokens());
        assertFalse(memory.budget().outputTokensClamped());
        assertTrue(memory.budget().historyBytes() > 60_000,
                "history budget at a million tokens: " + memory.budget().historyBytes());
        assertTrue(memory.budget().historyBytes() <= Integer.MAX_VALUE / 80);
        assertTrue(memory.budget().historyBytes() * 80 / 100 > 60_000);
        assertTrue(memory.budget().toolResultBytes() > 0);
        verify(invocationService, never()).completeStructuredRepairable(
                any(AiInvocation.class),
                eq(AiAssistantSummary.class),
                any(),
                any(),
                any(),
                any(Runnable.class));
    }

    @Test
    void maximumLengthInitiatingMessageUsesAnEphemeralProviderOmission() {
        AiInvocationService invocationService = mock(AiInvocationService.class);
        AiChatTurnPersistenceService persistenceService = mock(AiChatTurnPersistenceService.class);
        AiProperties properties = new AiProperties();
        properties.setAssistantMaxOutputTokens(8_192);
        var objectMapper = JsonMapper.builder().build();
        var catalog = new AiAssistantToolCatalog();
        var assembler = new AiAssistantPromptAssembler(objectMapper, catalog);
        var stepSchema = new AiAssistantStepSchema(objectMapper, catalog);
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        AiChatMemoryService service = new AiChatMemoryService(
                invocationService,
                mock(AiInvocationAdmissionService.class),
                properties,
                assembler,
                emptyToolExecutor(),
                new AiAssistantSummaryGuard(),
                new AiAssistantSummarySchema(objectMapper),
                stepSchema,
                persistenceService,
                mock(AiWorkspaceGovernanceService.class),
                objectMapper,
                Clock.fixed(now, ZoneOffset.UTC));
        AiChatQueuedTurn turn = new AiChatQueuedTurn(
                3, 12, 5, 7, 104, 4, 9L, false, List.of(), List.of());
        String originalContent = "界".repeat(16_000);
        AiChatMessage initiating = message(104, 4, "user", originalContent);
        when(invocationService.currentProviderCapabilities(AiFeature.ASSISTANT_CHAT))
                .thenReturn(new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.JSON_SCHEMA,
                        AiReasoningMode.TAGGED,
                        AiAssistantPromptBudget.ASSISTANT_MIN_CONTEXT_TOKENS,
                        8_192));
        when(invocationService.serializedPromptBytes(
                any(MaskedPrompt.class), same(stepSchema.responseSchema()),
                eq(AiReasoningMode.TAGGED)))
                .thenReturn(8_192);
        when(persistenceService.loadHistory(turn, 100)).thenReturn(List.of(initiating));
        when(persistenceService.loadHistorySummary(turn)).thenReturn(null);

        AiChatMemory memory = service.prepare(
                turn, new MaskingContext(), now.plusSeconds(70));

        assertEquals(1, memory.history().size());
        assertEquals(initiating.getId(), memory.history().getFirst().getId());
        assertEquals(
                "Current request omitted because it exceeded the model input budget. "
                        + "Ask the user to retry with a shorter request.",
                memory.history().getFirst().getContent());
        assertTrue(memory.budget().fits(
                memory.history().getFirst().getContent(), memory.budget().historyBytes()));
        assertEquals(originalContent, initiating.getContent());
        MaskedPrompt providerPrompt = assembler.assemble(
                memory.history(),
                new AiAssistantToolResult(Map.of(), List.of()),
                List.of(),
                new MaskingContext(),
                new AiChatResourceRegistry(),
                memory.budget(),
                null);
        assertFalse(promptText(providerPrompt).contains("界"));
        assertTrue(promptText(providerPrompt).contains("Current request omitted"));
        verify(invocationService, never()).completeStructuredRepairable(
                any(AiInvocation.class),
                eq(AiAssistantSummary.class),
                any(),
                any(),
                any(),
                any(Runnable.class));
    }

    @Test
    void compactionChecksTheTurnDeadlineImmediatelyBeforeSummaryPersistence() {
        AiInvocationService invocationService = mock(AiInvocationService.class);
        AiInvocationAdmissionService admissionService = mock(AiInvocationAdmissionService.class);
        AiInvocationAdmissionService.DirectAdmission admission =
                mock(AiInvocationAdmissionService.DirectAdmission.class);
        AiChatTurnPersistenceService persistenceService = mock(AiChatTurnPersistenceService.class);
        AiAssistantToolExecutor toolExecutor = emptyToolExecutor();
        AiWorkspaceGovernanceService governanceService = mock(AiWorkspaceGovernanceService.class);
        AiProperties properties = new AiProperties();
        properties.setAssistantMaxOutputTokens(1_024);
        var objectMapper = JsonMapper.builder().build();
        var assembler = new AiAssistantPromptAssembler(
                objectMapper, new AiAssistantToolCatalog());
        var summaryGuard = new AiAssistantSummaryGuard();
        var summarySchema = new AiAssistantSummarySchema(objectMapper);
        var stepSchema = new AiAssistantStepSchema(
                objectMapper, new AiAssistantToolCatalog());
        Clock clock = mock(Clock.class);
        Instant start = Instant.parse("2026-08-12T00:00:00Z");
        Instant deadline = start.plusSeconds(70);
        when(clock.instant()).thenReturn(start, start, start, start, start, deadline);
        AiChatMemoryService service = new AiChatMemoryService(
                invocationService,
                admissionService,
                properties,
                assembler,
                toolExecutor,
                summaryGuard,
                summarySchema,
                stepSchema,
                persistenceService,
                governanceService,
                objectMapper,
                clock);
        AiChatQueuedTurn turn = new AiChatQueuedTurn(
                3, 12, 5, 7, 102, 2, 9L, false, List.of(), List.of());
        AiChatMessage early = message(101, 1, "user", "history ".repeat(4_000));
        AiChatMessage initiating = message(102, 2, "user", "Continue");
        when(invocationService.currentProviderCapabilities(AiFeature.ASSISTANT_CHAT))
                .thenReturn(new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.JSON_SCHEMA,
                        AiReasoningMode.TAGGED,
                        200_000,
                        50_000));
        when(invocationService.serializedPromptBytes(
                any(MaskedPrompt.class), same(stepSchema.responseSchema()),
                eq(AiReasoningMode.TAGGED)))
                .thenReturn(8_192);
        when(persistenceService.loadHistory(turn, 100))
                .thenReturn(List.of(early, initiating));
        when(persistenceService.loadHistorySummary(turn)).thenReturn(null);
        when(persistenceService.loadCompactionCandidates(turn, 0, 2, 500))
                .thenReturn(List.of(early));
        when(admissionService.acquireDirect()).thenReturn(admission);
        when(governanceService.isEnabled(turn.workspaceId())).thenReturn(true);
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class),
                eq(AiAssistantSummary.class),
                same(summaryGuard),
                same(summarySchema.responseSchema()),
                same(admission),
                any(Runnable.class)))
                .thenAnswer(invocation -> {
                    Runnable providerGuard = invocation.getArgument(5, Runnable.class);
                    providerGuard.run();
                    return new AiStructuredRepairAttempt<>(
                            new AiStructuredOutcome.Parsed<>(
                                    new AiAssistantSummary("Earlier context summarized."),
                                    0, 7, 3, "end_turn"),
                            Optional.empty());
                });

        AiAssistantLoopException exception = assertThrows(
                AiAssistantLoopException.class,
                () -> service.prepare(turn, new MaskingContext(), deadline));

        assertEquals("turn_deadline_exceeded", exception.terminalReason());
        verify(persistenceService, never()).upsertHistorySummary(
                any(), any(), anyInt(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void singleOversizedHistoricalMessageCompactsAsAWholeOmissionAndAdvances() {
        AiInvocationService invocationService = mock(AiInvocationService.class);
        AiInvocationAdmissionService admissionService = mock(AiInvocationAdmissionService.class);
        AiInvocationAdmissionService.DirectAdmission admission =
                mock(AiInvocationAdmissionService.DirectAdmission.class);
        AiChatTurnPersistenceService persistenceService = mock(AiChatTurnPersistenceService.class);
        AiAssistantToolExecutor toolExecutor = emptyToolExecutor();
        AiProperties properties = new AiProperties();
        properties.setAssistantMaxOutputTokens(1_024);
        var objectMapper = JsonMapper.builder().build();
        var assembler = new AiAssistantPromptAssembler(
                objectMapper, new AiAssistantToolCatalog());
        var summaryGuard = new AiAssistantSummaryGuard();
        var summarySchema = new AiAssistantSummarySchema(objectMapper);
        var stepSchema = new AiAssistantStepSchema(
                objectMapper, new AiAssistantToolCatalog());
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        AiChatMemoryService service = new AiChatMemoryService(
                invocationService,
                admissionService,
                properties,
                assembler,
                toolExecutor,
                summaryGuard,
                summarySchema,
                stepSchema,
                persistenceService,
                mock(AiWorkspaceGovernanceService.class),
                objectMapper,
                Clock.fixed(now, ZoneOffset.UTC));
        AiChatQueuedTurn turn = new AiChatQueuedTurn(
                3, 12, 5, 7, 102, 2, 9L, false, List.of(), List.of());
        String oversizedContent = "OVERSIZED_PRIVATE_HISTORY_" + "x".repeat(25_000);
        AiChatMessage oversized = message(101, 1, "user", oversizedContent);
        AiChatMessage initiating = message(102, 2, "user", "Continue");
        AiChatMessage storedSummary = message(
                103, 3, "system", "One earlier message was omitted safely.");
        storedSummary.setStructuredJson(
                "{\"kind\":\"history_summary\",\"sourceFromSeq\":1,"
                        + "\"throughSeq\":1,\"resources\":[],\"identifiers\":[]}");
        when(invocationService.currentProviderCapabilities(AiFeature.ASSISTANT_CHAT))
                .thenReturn(new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.JSON_SCHEMA,
                        AiReasoningMode.TAGGED,
                        AiAssistantPromptBudget.ASSISTANT_MIN_CONTEXT_TOKENS,
                        8_192));
        when(invocationService.serializedPromptBytes(
                any(MaskedPrompt.class), same(stepSchema.responseSchema()),
                eq(AiReasoningMode.TAGGED)))
                .thenReturn(0);
        when(persistenceService.loadHistory(turn, 100))
                .thenReturn(List.of(oversized, initiating));
        when(persistenceService.loadHistorySummary(turn)).thenReturn(null);
        when(persistenceService.loadCompactionCandidates(turn, 0, 2, 500))
                .thenReturn(List.of(oversized));
        when(persistenceService.loadCompactionCandidates(turn, 1, 2, 500))
                .thenReturn(List.of());
        when(admissionService.acquireDirect()).thenReturn(admission);
        when(invocationService.completeStructuredRepairable(
                any(AiInvocation.class),
                eq(AiAssistantSummary.class),
                same(summaryGuard),
                same(summarySchema.responseSchema()),
                same(admission),
                any(Runnable.class)))
                .thenReturn(
                        new AiStructuredRepairAttempt<>(
                                new AiStructuredOutcome.Parsed<>(
                                new AiAssistantSummary("界".repeat(4_000)),
                                        0, 7, 3, "end_turn"),
                                Optional.empty()),
                        new AiStructuredRepairAttempt<>(
                                new AiStructuredOutcome.Parsed<>(
                                        new AiAssistantSummary(
                                                "One earlier message was omitted safely."),
                                        0, 11, 4, "end_turn"),
                                Optional.empty()));
        when(persistenceService.upsertHistorySummary(
                same(turn), isNull(), eq(0), anyString(), anyString(), eq(11), eq(4)))
                .thenReturn(storedSummary);

        AiAssistantLoopException firstAttempt = assertThrows(
                AiAssistantLoopException.class,
                () -> service.prepare(
                        turn, new MaskingContext(), now.plusSeconds(70)));
        assertEquals("summary_compaction_failed", firstAttempt.terminalReason());
        verify(persistenceService, never()).upsertHistorySummary(
                any(), any(), anyInt(), anyString(), anyString(), anyInt(), anyInt());

        AiChatMemory memory = service.prepare(
                turn, new MaskingContext(), now.plusSeconds(70));

        ArgumentCaptor<AiInvocation> invocation = ArgumentCaptor.forClass(AiInvocation.class);
        verify(invocationService, times(2)).completeStructuredRepairable(
                invocation.capture(),
                eq(AiAssistantSummary.class),
                same(summaryGuard),
                same(summarySchema.responseSchema()),
                same(admission),
                any(Runnable.class));
        String compactionPrompt = promptText(invocation.getAllValues().getLast().prompt());
        assertFalse(compactionPrompt.contains(oversizedContent));
        assertTrue(compactionPrompt.contains(
                "Historical message omitted because it exceeded the compaction input budget."));
        assertEquals(List.of(storedSummary, initiating), memory.history());
        assertEquals(11, memory.inputTokens());
        assertEquals(4, memory.outputTokens());
    }

    private static AiChatMessage message(int id, int seq, String author, String content) {
        AiChatMessage message = new AiChatMessage();
        message.setId(id);
        message.setSessionId(5);
        message.setSeq(seq);
        message.setAuthorKind(author);
        message.setContent(content);
        if ("user".equals(author)) {
            message.setStructuredJson("{\"kind\":\"user_message\",\"resources\":[]}");
        }
        return message;
    }

    private static AiAssistantToolExecutor emptyToolExecutor() {
        AiAssistantToolExecutor executor = mock(AiAssistantToolExecutor.class);
        when(executor.pageContext(any(), any()))
                .thenReturn(new AiAssistantToolResult(Map.of(), List.of()));
        return executor;
    }

    private static String promptText(MaskedPrompt prompt) {
        return prompt.getMessages().stream()
                .map(message -> message.getContent())
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
