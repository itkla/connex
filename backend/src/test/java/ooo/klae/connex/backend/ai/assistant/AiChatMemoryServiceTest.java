package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
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
        AiAssistantIdentifierResolver identifierResolver = mock(AiAssistantIdentifierResolver.class);
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
        AiChatMemoryService service = new AiChatMemoryService(
                invocationService,
                admissionService,
                properties,
                identifierResolver,
                assembler,
                mock(AiAssistantToolExecutor.class),
                summaryGuard,
                summarySchema,
                stepSchema,
                persistenceService,
                mock(AiWorkspaceGovernanceService.class),
                objectMapper);
        AiChatQueuedTurn turn = new AiChatQueuedTurn(
                3, 12, 5, 7, 104, 4, 9L, false, List.of(), List.of());
        AiChatMessage early = message(
                101, 1, "user",
                "EARLY_FACT_BEGIN " + "quarterly planning preference ".repeat(200)
                        + "EARLY_FACT_END");
        AiChatMessage recent = message(
                103, 3, "assistant",
                "RECENT_ANSWER_BEGIN " + "grounded relationship update ".repeat(200)
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
                        + "\"throughSeq\":1,\"resources\":[]}");
        AiChatMessage storedSummary = message(
                105, 5, "system",
                "The user prefers quarterly planning and retained the second-batch fact.");
        storedSummary.setStructuredJson(
                "{\"kind\":\"history_summary\",\"sourceFromSeq\":1,"
                        + "\"throughSeq\":2,\"resources\":[]}");
        when(invocationService.currentProviderCapabilities(AiFeature.ASSISTANT_CHAT))
                .thenReturn(new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.JSON_SCHEMA,
                        AiReasoningMode.TAGGED,
                        200_000));
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

        AiChatMemory memory = service.prepare(turn, new MaskingContext());

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
        assertTrue(firstCompactionPrompt.contains("EARLY_FACT_BEGIN"));
        assertTrue(firstCompactionPrompt.contains("EARLY_FACT_END"));
        assertFalse(firstCompactionPrompt.contains("MIDDLE_FACT_BEGIN"));
        assertTrue(secondCompactionPrompt.contains("The user prefers quarterly planning."));
        assertTrue(secondCompactionPrompt.contains("MIDDLE_FACT_BEGIN"));
        assertTrue(secondCompactionPrompt.contains("MIDDLE_FACT_END"));
        assertFalse(secondCompactionPrompt.contains("RECENT_ANSWER_BEGIN"));
        assertEquals(List.of(storedSummary, recent, initiating), memory.history());
        assertEquals(42, memory.inputTokens());
        assertEquals(16, memory.outputTokens());

        MaskedPrompt replay = assembler.assemble(
                memory.history(),
                new AiAssistantToolResult(Map.of(), List.of()),
                List.of(),
                new MaskingContext(),
                new AiChatResourceRegistry(),
                memory.budget(),
                null);
        assertTrue(promptText(replay).contains(
                "The user prefers quarterly planning and retained the second-batch fact."));
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

    private static String promptText(MaskedPrompt prompt) {
        return prompt.getMessages().stream()
                .map(message -> message.getContent())
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
